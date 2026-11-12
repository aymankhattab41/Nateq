package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات تحويل السرعة/النبرة وتقسيم النص لقطع محرك Acapela.
 *
 * **عقد التقسيم (MultiTTS):** قطعٌ بطول 200 حرف على حدود الكلمات؛
 * فلا تُبتَر كلمة ولا يُسقَط نص. الضمُّ يستعيد الكلمات كلها بالترتيب.
 */
class AcapelaSpeechTagTest {

    @Test
    fun calculateSpeedPercent_convertsRateProperly() {
        assertEquals(100, AcapelaHostSession.calculateSpeedPercent(1.0f))
        assertEquals(150, AcapelaHostSession.calculateSpeedPercent(1.5f))
        assertEquals(200, AcapelaHostSession.calculateSpeedPercent(2.0f))
        assertEquals(50, AcapelaHostSession.calculateSpeedPercent(0.5f))
        assertEquals(30, AcapelaHostSession.calculateSpeedPercent(0.1f))
        assertEquals(400, AcapelaHostSession.calculateSpeedPercent(5.0f))
    }

    @Test
    fun calculatePitchPercent_convertsPitchProperly() {
        assertEquals(100, AcapelaHostSession.calculatePitchPercent(1.0f))
        assertEquals(120, AcapelaHostSession.calculatePitchPercent(1.2f))
        assertEquals(80, AcapelaHostSession.calculatePitchPercent(0.8f))
        assertEquals(50, AcapelaHostSession.calculatePitchPercent(0.2f))
        assertEquals(200, AcapelaHostSession.calculatePitchPercent(3.0f))
    }

    @Test
    fun splitAcapelaChunks_keepsShortTextWhole() {
        val text = "نصٌّ قصير"
        assertEquals(
            listOf(text),
            AcapelaHostSession.splitAcapelaChunks(text, 200)
        )
    }

    @Test
    fun splitAcapelaChunks_keepsEveryWordForLongText() {
        val words = (1..120).map { "كلمة$it" }
        val text = words.joinToString(" ")

        val chunks = AcapelaHostSession.splitAcapelaChunks(text, 200)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 200 })
        // لا فقدان كلمة ولا بتر: الضمُّ يستعيد الكلمات بالترتيب.
        assertEquals(words, chunks.joinToString(" ").split(" "))
    }

    @Test
    fun splitAcapelaChunks_neverStartsChunkWithSpace() {
        val text = (1..60).joinToString(" ") { "word$it" }

        val chunks = AcapelaHostSession.splitAcapelaChunks(text, 120)

        assertTrue(chunks.all { !it.startsWith(" ") && !it.endsWith(" ") })
    }
}
