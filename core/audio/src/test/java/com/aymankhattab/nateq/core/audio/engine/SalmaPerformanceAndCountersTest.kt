package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.enginehost.AcapelaHostSession
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * اختبارات الأداء والعدادات الدقيقة لمحرك سلمى وAcapela المدمج:
 * 1. دقة تتبع SalmaSpeechTracker وحساب المتوسط وp95 دون إسقاط المقام.
 * 2. خلو الخيط الرئيسي تماماً من قراءة القرص في isConfigured عند تفريغ الكاش.
 * 3. ثبات المسار الدافئ على صفر مسح وقراءة قرص وتجزئة وبلا إعادة تهيئة.
 * 4. إدارة إبطال الكاش عند تعديل الحزم والتعافي الدافئ عند تبديل الصوت.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SalmaPerformanceAndCountersTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var salmaDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AcapelaPerformanceCounters.resetForTest()
        SalmaSpeechTracker.clearForTest()
        AcapelaVoiceRepository.resetForTests()
        AcapelaHostSession.noteWarmVoiceClosed()

        val base = tempFolder.newFolder("voices", "acapela")
        salmaDir = File(base, "salma_lf").apply { mkdirs() }
        File(salmaDir, "salma_lf.inf").writeText(
            "Speaker = \"Salma\"\nVoiceName = \"Salma\"\n"
        )
        File(salmaDir, "salma_lf.ini").writeText(
            "FILE = \"salma.dat\"\nBNF = \"salma.bnf\"\n"
        )
        File(salmaDir, "salma.dat").writeBytes(ByteArray(4096))
        File(salmaDir, "salma.bnf").writeBytes(ByteArray(4096))
        File(salmaDir, "0x12345678").writeText("DUMMY_LICENSE")
    }

    @After
    fun tearDown() {
        AcapelaPerformanceCounters.resetForTest()
        SalmaSpeechTracker.clearForTest()
        AcapelaVoiceRepository.resetForTests()
        AcapelaHostSession.noteWarmVoiceClosed()
    }

    @Test
    fun testTrackerRecordsAndAggregatesMetricsWithoutDroppingDenominator() {
        for (i in 1..10) {
            val req = SalmaSpeechTracker.startRequest(
                correlationId = "test_req_$i",
                category = SalmaSpeechTracker.Category.WARM,
                voiceName = "salma",
                textLength = 20
            )
            req.markQueueWaitStart()
            req.markQueueWaitEnd()
            req.markSpeakCallStart()
            req.markSpeakCallEnd()
            req.markFirstPcm()
            req.markFirstTrackWrite()
            req.complete(SalmaSpeechTracker.Status.SUCCESS)
            SalmaSpeechTracker.notePlaybackHeadAdvance()
        }

        // إضافة حالة ملغاة وحالة فاشلة للتحقق من عدم إسقاط المقام
        val cancelled = SalmaSpeechTracker.startRequest(
            correlationId = "test_cancelled",
            category = SalmaSpeechTracker.Category.WARM,
            voiceName = "salma",
            textLength = 10
        )
        cancelled.complete(
            SalmaSpeechTracker.Status.CANCELLED,
            "User cancelled"
        )

        val failed = SalmaSpeechTracker.startRequest(
            correlationId = "test_failed",
            category = SalmaSpeechTracker.Category.WARM,
            voiceName = "salma",
            textLength = 10
        )
        failed.complete(
            SalmaSpeechTracker.Status.FAILED,
            "Synthesis error"
        )

        val stats = SalmaSpeechTracker.getStats()
        assertEquals(
            "إجمالي الطلبات يشمل الناجحة والملغاة والفاشلة",
            12,
            stats.totalCount
        )
        assertEquals(10, stats.successCount)
        assertEquals(1, stats.cancelledCount)
        assertEquals(1, stats.failedCount)
        assertEquals(0, stats.unmeasuredCount)

        assertTrue(
            "المتوسط يجب أن يكون موجباً",
            stats.avgFirstPcmMs >= 0.0
        )
        assertTrue(
            "p95 يجب أن يكون أكبر أو يساوي المتوسط",
            stats.p95FirstPcmMs >= stats.avgFirstPcmMs
        )
        assertTrue(
            "متوسط كتابة المشغل يجب أن يكون موجباً",
            stats.avgFirstTrackWriteMs >= 0.0
        )
        assertTrue(
            "متوسط تقدم رأس التشغيل يجب أن يكون موجباً",
            stats.avgPlaybackHeadMs >= 0.0
        )
    }

    @Test
    fun testIsConfigured_onMainThread_zeroDiskIo_uninitialized() {
        assertEquals(
            "الاختبار يجب أن يعمل على الخيط الرئيسي",
            Looper.getMainLooper(),
            Looper.myLooper()
        )

        AcapelaVoiceRepository.resetForTests()
        AcapelaPerformanceCounters.resetForTest()

        val provider = AcapelaEmbeddedVoiceProvider(context)
        val configured = provider.isConfigured()

        assertFalse("اللقطة الفارغة يجب ألا تدعي الجاهزية", configured)

        val counters = AcapelaPerformanceCounters.snapshot()
        assertEquals(
            "يجب ألا يحدث مسح للقرص على الخيط الرئيسي",
            0L,
            counters.scans
        )
        assertEquals(
            "يجب ألا تقرأ ملفات تهيئة على الخيط الرئيسي",
            0L,
            counters.configReads
        )
        assertEquals(
            "يجب ألا تحسب بايتات تجزئة على الخيط الرئيسي",
            0L,
            counters.hashBytes
        )
    }

    @Test
    fun testWarmPath_tenUtterances_causesZeroScansZeroConfigReads() {
        // المسار البارد: التحقق الأولي وحفظ التهيئة
        val initVer = AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
            voiceDir = salmaDir,
            expectedSpeakerHint = "Salma",
            computeFullHashes = false
        )
        assertNotNull(initVer)
        AcapelaHostSession.noteWarmVoiceLoaded("salma")

        // تصفير العدادات لقياس المسار الدافئ فقط
        AcapelaPerformanceCounters.resetForTest()

        // محاكاة 10 عبارات في المسار الدافئ
        for (i in 1..10) {
            assertTrue(
                "الصوت يجب أن يكون دافئاً وجاهزاً",
                AcapelaHostSession.isWarmVoiceReady("salma")
            )
            val ver = AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
                voiceDir = salmaDir,
                expectedSpeakerHint = "Salma",
                computeFullHashes = false
            )
            assertNotNull(ver)

            val req = SalmaSpeechTracker.startRequest(
                correlationId = "warm_$i",
                category = SalmaSpeechTracker.Category.WARM,
                voiceName = "salma",
                textLength = 25
            )
            req.markFirstPcm()
            req.markFirstTrackWrite()
            req.complete(SalmaSpeechTracker.Status.SUCCESS)
        }

        val counters = AcapelaPerformanceCounters.snapshot()
        assertEquals(
            "عدد المسح في المسار الدافئ يجب أن يكون صفراً",
            0L,
            counters.scans
        )
        assertEquals(
            "قراءات ملفات التهيئة في المسار الدافئ يجب أن تكون صفراً",
            0L,
            counters.configReads
        )
        assertEquals(
            "بايتات التجزئة في المسار الدافئ يجب أن تكون صفراً",
            0L,
            counters.hashBytes
        )
        assertEquals(
            "إعادة تهيئة المحرك في المسار الدافئ يجب أن تكون صفراً",
            0L,
            counters.engineInitCount
        )
        assertEquals(
            "إغلاق الخدمة في المسار الدافئ يجب أن يكون صفراً",
            0L,
            counters.serviceCloseCount
        )
        assertTrue(
            "إصابات كاش التحقق يجب ألا تقل عن 10",
            counters.verificationCacheHits >= 10L
        )
    }

    @Test
    fun testInvalidation_onPackageModification_andWarmRecovery() {
        AcapelaHostSession.noteWarmVoiceLoaded("salma")
        assertTrue(AcapelaHostSession.isWarmVoiceReady("salma"))

        // إبطال بسبب تعديل ملف
        AcapelaVoiceRepository.invalidate(context, "voice_file_modified")
        val reasons = AcapelaPerformanceCounters.invalidationReasons
        assertTrue(
            "سبب الإبطال يجب أن يسجل بدقة",
            reasons.any { it.contains("voice_file_modified") }
        )

        // إغلاق الصوت القديم والانتقال لصوت جديد (مثلاً ليلى)
        AcapelaHostSession.noteWarmVoiceClosed()
        assertFalse(
            "بعد الإغلاق يجب ألا يكون سلمى دافئاً",
            AcapelaHostSession.isWarmVoiceReady("salma")
        )

        AcapelaHostSession.noteWarmVoiceLoaded("leila")
        assertTrue(
            "ليلى يجب أن تصبح جاهزة",
            AcapelaHostSession.isWarmVoiceReady("leila")
        )
        assertFalse(
            "سلمى لا تزال غير جاهزة",
            AcapelaHostSession.isWarmVoiceReady("salma")
        )
    }
}
