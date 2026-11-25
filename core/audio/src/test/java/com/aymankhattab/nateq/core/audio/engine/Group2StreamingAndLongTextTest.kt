package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات وحدة لإصلاحات المجموعة 2:
 * 4) مسار البث عبر الملف: دورة الاستطلاع 25ms وعتبة الشريحة الأولى 4KB.
 * 6) تقسيم النصوص الطويلة (فوق 220-280 حرفاً) عند الحدود اللغوية الآمنة
 *    لتسريع أول شريحة صوتية وتفادي تأخير وبطء الجمل الطويلة مع الحفاظ
 *    على عدم بتر الكلمات.
 */
class Group2StreamingAndLongTextTest {

    @Test
    fun streamingConstants_matchLatencyRequirements() {
        // دورة الاستطلاع 25ms لتسريع التقاط الشريحة
        assertEquals(25L, SystemVoiceProvider.CANCELLATION_POLL_MS)
        // عتبة الشريحة الأولى 4KB لتسريع أول صوت فوراً
        assertEquals(4 * 1024, SystemVoiceProvider.STREAM_FIRST_CHUNK_BYTES)
        // العتبة اللاحقة 16KB لعدم إجهاد المعالج
        assertEquals(16 * 1024, SystemVoiceProvider.STREAM_CHUNK_BYTES)
    }

    @Test
    fun splitSentenceChunks_shortText_remainsSingle() {
        val short = "هذا نص قصير لا يتطلب أي تجزئة."
        val chunks = splitSentenceChunks(short, 280)
        assertEquals(1, chunks.size)
        assertEquals(short, chunks[0])
    }

    @Test
    fun splitSentenceChunks_longTextWithPunctuation_splitsAtBoundaries() {
        val s1 = "الجملة الأولى تحتوي على تفاصيل كافية لتوضيح الفكرة للقارئ."
        val s2 = "الجملة الثانية تشرح بعد ذلك السياق والمضمون بشكل وافٍ ومفصل."
        val s3 = "أما الجملة الثالثة فتختم هذا النص الطويل المنطوق بوضوح تام."
        val full = "$s1 $s2 $s3"
        // نضع عتبة 100 حرف للتجربة
        val chunks = splitSentenceChunks(full, 100)
        assertTrue("يجب أن يُقسّم النص: ${chunks.size}", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(
                "طول الجزء يجب ألا يتجاوز العتبة: ${chunk.length}",
                chunk.length <= 100
            )
        }
        // التأكد من عدم بتر الكلمات: الكلمات يجب أن تُستعاد كاملة
        val joined = chunks.joinToString(" ")
        val originalWords = full.split(Regex("\\s+")).filter { it.isNotBlank() }
        val chunkWords = joined.split(Regex("\\s+")).filter { it.isNotBlank() }
        assertEquals(originalWords, chunkWords)
    }

    @Test
    fun splitSentenceChunks_noPunctuation_splitsAtWordBoundaries() {
        val words = (1..60).map { "كلمة$it" }.joinToString(" ")
        assertTrue("النص طويل: ${words.length}", words.length > 280)
        val chunks = splitSentenceChunks(words, 200)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("جزء طويل: ${chunk.length}", chunk.length <= 200)
        }
        val joined = chunks.joinToString(" ")
        assertEquals(words, joined)
    }

    @Test
    fun splitSentenceChunks_restoresOriginalTextFully() {
        val text = "فقرة أولى مفصلة. فقرة ثانية تشرح ما سبق!" +
            " وفقرة ثالثة تختم الكلام؟"
        val chunks = splitSentenceChunks(text, 30)
        assertTrue(chunks.size > 1)
        val rejoined = chunks.joinToString(" ")
        val cleanOriginal = text.replace(Regex("\\s+"), " ").trim()
        val cleanRejoined = rejoined.replace(Regex("\\s+"), " ").trim()
        assertEquals(cleanOriginal, cleanRejoined)
    }
}
