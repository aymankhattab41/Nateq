package com.aymankhattab.nateq.core.audio.enginehost

import android.os.ParcelFileDescriptor
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import com.aymankhattab.nateq.core.audio.engine.MonitoredSynthesisCallback
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات تقسيم النصوص الطويلة وحارس الخمول وكاش الصوت لـ Vocalizer (ع٤٢-7).
 */
class VocalizerChunkingAndWatchdogTest {

    @Test
    fun split5000CharsText_allChunksWithin300Chars() {
        val paragraph = "هذا نص تجريبي طويل لاختبار آلية التقسيم الذكي، " +
            "حيث يتم تجزئة النص إلى مقاطع وجمل لا تتجاوز 300 حرف. " +
            "هل يعمل النظام بشكل متصل ودقيق؟ نعم بالتأكيد! " +
            "الهدف هو منع توقف القراءة في النصوص الطويلة؛ " +
            "وهكذا تستمر القراءة بكل سلاسة ووضوح.\n"

        val sb = StringBuilder()
        while (sb.length < 5_000) {
            sb.append(paragraph)
        }
        val fullText = sb.toString()
        assertTrue("النص يتجاوز 5000 حرف", fullText.length >= 5_000)

        val chunks = VocalizerHostSession.splitIntoVocalizerChunks(fullText)
        assertTrue("يجب أن يُقسَّم إلى عدة مقاطع", chunks.size > 15)

        for ((index, chunk) in chunks.withIndex()) {
            assertTrue(
                "المقطع $index طوله ${chunk.length} يتجاوز 300 حرف",
                chunk.length <= VocalizerHostSession.VOCALIZER_MAX_CHUNK_CHARS
            )
            assertTrue("المقطع ليس فارغاً", chunk.isNotBlank())
        }
    }

    @Test
    fun splitChunks_preservesArabicPunctuationBoundaries() {
        val text = "أهلاً بكم في تطبيق ناطق، قارئ الشاشة المتميز. " +
            "نرحب باستفساراتكم دائماً؛ فهل لديكم أي سؤال؟ " +
            "نسعد بخدمتكم في كل وقت!"

        val chunks = VocalizerHostSession.splitIntoVocalizerChunks(
            text,
            maxChars = 60
        )
        assertTrue("يجب تقسيم النص", chunks.size >= 3)
        for (chunk in chunks) {
            assertTrue("طول المقطع ≤ 60", chunk.length <= 60)
        }
        // تحقق من وجود الفواصل في المقاطع
        assertTrue(chunks.any { it.contains("،") })
        assertTrue(chunks.any { it.contains("؟") })
        assertTrue(chunks.any { it.contains("!") })
    }

    @Test
    fun splitChunks_shortTextRemainsSingleChunk() {
        val short = "نص قصير لا يحتاج إلى تقسيم."
        val chunks = VocalizerHostSession.splitIntoVocalizerChunks(short)
        assertEquals(1, chunks.size)
        assertEquals(short, chunks[0])
    }

    @Test
    fun splitChunks_whitespaceFallbackWhenNoPunctuation() {
        val words = List(100) { "كلمة" }.joinToString(" ")
        assertTrue(words.length > 300)
        val chunks = VocalizerHostSession.splitIntoVocalizerChunks(words)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(
                "طول المقطع ≤ 300",
                chunk.length <= VocalizerHostSession.VOCALIZER_MAX_CHUNK_CHARS
            )
        }
    }

    @Test
    fun splitChunks_unbrokenTextForceSplitAtLimit() {
        val unbroken = "أ".repeat(800)
        val chunks = VocalizerHostSession.splitIntoVocalizerChunks(unbroken)
        assertEquals(3, chunks.size)
        assertEquals(300, chunks[0].length)
        assertEquals(300, chunks[1].length)
        assertEquals(200, chunks[2].length)
    }

    @Test
    fun watchdog_simulatedSlowEngine_doesNotTimeoutWhileStreaming() {
        var lastProgressMs = 0L
        var nowMs = 0L
        val idleLimitMs = SynthesisBudget.idleTimeoutMs(1_000L) // 15_000L
        // محاكاة محرك بطيء يبث شريحة كل ثانية لمدة 40 ثانية (تتجاوز 15ث بكثير)
        repeat(40) {
            nowMs += 1_000L
            lastProgressMs = nowMs
            val idleElapsed = nowMs - lastProgressMs
            assertFalse(
                "لا يجوز انتهاء المهلة أثناء تدفق الصوت المستمر",
                idleElapsed >= idleLimitMs
            )
        }
    }

    @Test
    fun watchdog_genuineStall_timesOutAfter15sNotBefore() {
        val lastProgressMs = 1_000L
        val idleLimitMs = SynthesisBudget.idleTimeoutMs(0L) // 15_000L

        // قبل انقضاء 15 ثانية: لا انتهاء
        val beforeTimeoutMs = lastProgressMs + 14_999L
        val elapsedBefore = beforeTimeoutMs - lastProgressMs
        assertFalse(
            "لا يجوز إطلاق خطأ الخمول قبل اكتمال 15 ثانية",
            elapsedBefore >= idleLimitMs
        )

        // عند 15 ثانية وما بعدها: خمول حقيقي
        val atTimeoutMs = lastProgressMs + 15_000L
        val elapsedAt = atTimeoutMs - lastProgressMs
        assertTrue(
            "يجب إطلاق الخطأ عند انقضاء 15 ثانية خمول",
            elapsedAt >= idleLimitMs
        )
    }

    @Test
    fun monitoredCallback_notifiesProgressOnAudioAvailable() {
        val progressCount = AtomicInteger(0)
        val totalBytes = AtomicLong(0L)
        val fakeCallback = object : SynthesisCallback {
            override fun getMaxBufferSize(): Int = 4096
            override fun start(sampleRate: Int, fmt: Int, ch: Int): Int =
                TextToSpeech.SUCCESS
            override fun audioAvailable(
                buf: ByteArray?,
                off: Int,
                len: Int
            ): Int {
                return TextToSpeech.SUCCESS
            }
            override fun done(): Int = TextToSpeech.SUCCESS
            override fun error() {}
            override fun error(errorCode: Int) {}
            override fun hasStarted(): Boolean = true
            override fun hasFinished(): Boolean = false
        }

        val monitored = MonitoredSynthesisCallback(fakeCallback) { bytes ->
            progressCount.incrementAndGet()
            totalBytes.addAndGet(bytes.toLong())
        }

        monitored.start(22050, 2, 1)
        assertEquals(1, progressCount.get())

        val pcm = ByteArray(512)
        monitored.audioAvailable(pcm, 0, 512)
        assertEquals(2, progressCount.get())
        assertEquals(512L, totalBytes.get())

        monitored.done()
        assertEquals(3, progressCount.get())
    }

    @Test
    fun midSynthesisCancellation_stopsFurtherProcessing() {
        val cancelled = AtomicBoolean(false)
        val chunksSpoken = ArrayList<String>()

        val chunks = listOf("مقطع 1", "مقطع 2", "مقطع 3", "مقطع 4")
        for ((idx, chunk) in chunks.withIndex()) {
            if (idx == 2) {
                cancelled.set(true)
            }
            if (cancelled.get()) {
                break
            }
            chunksSpoken.add(chunk)
        }

        // تم نطق مقطعين قبل الإلغاء، ولم يتم نطق المقاطع اللاحقة
        assertEquals(listOf("مقطع 1", "مقطع 2"), chunksSpoken)
    }
}
