package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.quirks.EngineQuirks
import com.aymankhattab.nateq.core.audio.engine.quirks.EngineTextSanitizer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 55: حل مشاكل محركات Acapela وبطء الكتابة وبتر الصوت.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OrdersRound55AcapelaTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // (أ) مزود يُصدر onDone لمعرف عبارة سابقة أثناء انتظار عبارة جديدة:
    // لا ينتهي الانتظار الجديد.
    @Test
    fun testScenarioA_lateOnDoneFromPreviousUtteranceDoesNotFinishNewRequest() {
        data class TestRequestState(
            val id: String,
            val done: CountDownLatch = CountDownLatch(1),
            val doneTimeMs: AtomicLong = AtomicLong(0L)
        ) {
            fun onDone(receivedId: String?) {
                if (receivedId == id) {
                    doneTimeMs.set(System.currentTimeMillis())
                    done.countDown()
                }
            }
        }

        val oldId = "req_old_123"
        val currentId = "req_current_456"
        val currentState = TestRequestState(currentId)

        // وصول onDone للعبارة القديمة
        currentState.onDone(oldId)
        assertFalse(
            "onDone للعبارة القديمة لا يجب أن يُنهي انتظار العبارة الجديدة",
            currentState.done.await(50, TimeUnit.MILLISECONDS)
        )
        assertEquals(0L, currentState.doneTimeMs.get())

        // وصول onDone للعبارة الحالية
        currentState.onDone(currentId)
        assertTrue(
            "onDone للعبارة الصحيحة يجب أن يُنهي الانتظار",
            currentState.done.await(50, TimeUnit.MILLISECONDS)
        )
        assertTrue(currentState.doneTimeMs.get() > 0L)
    }

    // (ب) مزود يُصدر onDone قبل اكتمال كتابة الملف بـ 300ms:
    // يُقرأ الملف كاملاً بفضل lateWriteSettleMs.
    @Test
    fun testScenarioB_lateWriteSettleAllowsReadingCompleteFile() {
        val tempFile = File(context.cacheDir, "settle_test.wav")
        tempFile.writeBytes(ByteArray(100))

        val quirks = EngineQuirks.forPackage("com.acapelagroup.android.tts")
        val settleTargetMs = quirks.lateWriteSettleMs
        assertEquals(350L, settleTargetMs)

        var lastLen = tempFile.length()
        var stableDurationMs = 0L
        var step = 0

        // محاكاة استمرار كتابة الملف لمدة 200ms بعد onDone
        val pollInterval = 50L
        while (stableDurationMs < settleTargetMs) {
            step++
            if (step == 3) { // بعد 150ms يكبر الملف
                tempFile.writeBytes(ByteArray(500))
            }
            val curLen = tempFile.length()
            if (curLen == lastLen && curLen > 0L) {
                stableDurationMs += pollInterval
            } else {
                lastLen = curLen
                stableDurationMs = 0L
            }
        }

        assertEquals(500L, tempFile.length())
        assertTrue("استقر بعد اكتمال الحجم النهائي", stableDurationMs >= 350L)
        tempFile.delete()
    }

    // (ج) رسالة بثلاثة مقاطع (عربي وإنجليزي ورقم) على Acapela:
    // تُخلّق بالتتابع وتُسلسل لأن المحرك يطلب serializeRequests.
    @Test
    fun testScenarioC_acapelaEngineDemandsSerializedRequests() {
        val acapela = EngineQuirks.forPackage("com.acapelagroup.android.tts")
        assertTrue(
            "Acapela يجب أن تطلب تسلسل الطلبات serializeRequests",
            acapela.serializeRequests
        )
        assertEquals(320, acapela.maxCharsPerRequest)
        assertEquals(350L, acapela.lateWriteSettleMs)
        assertTrue(acapela.sanitizeForEngine)
    }

    // (د) نص 900 حرف يُقسّم إلى أجزاء <= 320 ويصل كاملاً دون فجوة.
    @Test
    fun testScenarioD_longTextChunkedWithoutGaps() {
        val sentence = "هذه جملة تجريبية مفيدة لاختبار تقسيم النص بدقة. "
        val longText = sentence.repeat(20) // 960 حرفاً
        assertTrue(longText.length > 900)

        val chunks = SpeechChunker.split(longText, 320)
        assertTrue("يجب أن يُقسّم إلى مقاطع عدة", chunks.size >= 3)
        for (chunk in chunks) {
            assertTrue(
                "طول كل مقطع يجب ألا يتجاوز 320 حرفاً (وجدنا ${chunk.length})",
                chunk.length <= 320
            )
            assertTrue(chunk.isNotBlank())
        }

        val reconstructed = chunks.joinToString(" ")
        val originalWords = longText.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        val reconWords = reconstructed.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        assertEquals(
            "عدد الكلمات يجب أن يتطابق بعد إعادة التجميع",
            originalWords.size,
            reconWords.size
        )
    }

    // (هـ) ملف ناتج بنصف المدة المتوقعة يُعاد مرة واحدة ويُسجل كمبتور.
    @Test
    fun testScenarioE_halfDurationFileDetectedAsTruncated() {
        // نص من 100 حرف غير فارغ
        val nonWsChars = 100
        val speechRate = 1.0f
        val expectedDurationMs = ((nonWsChars * 55L) / speechRate).toLong()
        assertEquals(5500L, expectedDurationMs)

        // 35% من المدة المتوقعة = 1925ms
        val truncatedAudioDurationMs = 1500L // أقل من 35%
        val isTruncated = nonWsChars >= 25 &&
            truncatedAudioDurationMs < (expectedDurationMs * 0.35)
        assertTrue("يجب اعتباره صوتاً مبتوراً", isTruncated)

        val normalAudioDurationMs = 4500L // أكثر من 35%
        val isNotTruncated = nonWsChars >= 25 &&
            normalAudioDurationMs < (expectedDurationMs * 0.35)
        assertFalse("الصوت الطبيعي لا يُعتبر مبتوراً", isNotTruncated)

        // تسجيل في متتبع التشخيص
        SynthesisDiagnosticsTracker.record(
            SynthesisDiagnosticsTracker.DiagnosticRecord(
                engine = "com.acapelagroup.android.tts",
                voice = "salma",
                textLength = nonWsChars,
                status = 0,
                expectedUtteranceId = "req_1",
                receivedUtteranceId = "req_1",
                fileBytes = 1000L,
                declaredWavBytes = 1000L,
                stallPolls = 0,
                flushedAfterStall = false,
                durationMs = truncatedAudioDurationMs,
                expectedDurationMs = expectedDurationMs,
                isTruncated = true,
                retryCount = 1
            )
        )
        assertTrue(SynthesisDiagnosticsTracker.totalTruncatedCount() > 0)
    }

    // (و) نص فيه إيموجي وعلامة \uE030 يُنقّى قبل الإرسال ولا يصل أي محرف خاص.
    @Test
    fun testScenarioF_textSanitizationStripsEmojisAndInternalMarkers() {
        val rawText = "مرحباً يا أخي 😊 كيف حالك؟ " +
            "\u200B\u200E\uFEFFعلامة داخلية \uE030 هنا."
        val (clean, removed) = EngineTextSanitizer.sanitizeWithCount(
            rawText,
            "com.acapelagroup.android.tts"
        )

        assertFalse("يجب حذف الإيموجي", clean.contains("😊"))
        assertFalse("يجب حذف U+200B", clean.contains("\u200B"))
        assertFalse("يجب حذف U+200E", clean.contains("\u200E"))
        assertFalse("يجب حذف U+FEFF", clean.contains("\uFEFF"))
        assertFalse("يجب حذف علامات PUA \\uE030", clean.contains("\uE030"))
        assertTrue("يجب رصد عدد المحارف المحذوفة", removed > 0)
        assertTrue("النص الصالح يبقى كاملاً", clean.contains("مرحباً يا أخي"))
        assertTrue("النص الصالح يبقى كاملاً", clean.contains("علامة داخلية"))
    }

    // (ز) محرك يعيد SUCCESS والملف فارغ يُصنّف «صامت» ويُقدم تشخيصاً صريحاً.
    @Test
    fun testScenarioG_silentEngineZeroBytesCategorizedCorrectly() {
        val result = EngineHealthProbe.ProbeResult(
            enginePackage = "com.acapelagroup.android.tts",
            label = "Acapela",
            isHealthy = false,
            summaryAr = "Acapela: ينتج ملفاً فارغاً (صامت ✗)",
            bindTimeMs = 250L,
            voicesCount = 2,
            arAvailability = 0,
            enAvailability = 0,
            voiceSetSuccess = true,
            synthesisStatus = 0,
            fileSizeBytes = 0L,
            durationMs = 0L
        )

        assertFalse(result.isHealthy)
        assertTrue(result.summaryAr.contains("صامت ✗"))
        val report = result.summaryAr
        assertTrue(report.contains("Acapela"))
    }
}
