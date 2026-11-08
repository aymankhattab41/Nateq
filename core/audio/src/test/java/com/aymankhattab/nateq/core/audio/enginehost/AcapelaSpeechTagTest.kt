package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات التحقق من صحة وسوم سرعة ونبرة النطق لمحرك Acapela.
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
    fun formatAcapelaText_prependsNativeTags() {
        val result = AcapelaHostSession.formatAcapelaText(
            text = "مرحبا بالعالم",
            rate = 1.75f,
            pitch = 1.0f
        )
        val expected = "\\rspd=175\\ \\vct=100\\ مرحبا بالعالم"
        assertEquals(expected, result)
        assertTrue(result.startsWith("\\rspd=175\\ \\vct=100\\ "))
    }

    @Test
    fun formatAcapelaText_respectsBoundaries() {
        val slow = AcapelaHostSession.formatAcapelaText(
            text = "نص تجريبي",
            rate = 0.1f,
            pitch = 0.1f
        )
        assertEquals("\\rspd=30\\ \\vct=50\\ نص تجريبي", slow)

        val fast = AcapelaHostSession.formatAcapelaText(
            text = "نص سريع",
            rate = 10.0f,
            pitch = 10.0f
        )
        assertEquals("\\rspd=400\\ \\vct=200\\ نص سريع", fast)
    }
}
