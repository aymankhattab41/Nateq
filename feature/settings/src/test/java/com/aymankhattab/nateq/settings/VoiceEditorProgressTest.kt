package com.aymankhattab.nateq.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * حارس حسابات محرّر الصوت الكامل (ع٤١-ب14 توسعة): تحويل قيم السرعة/النبرة/
 * المستوى إلى تقدّم المؤشر والعكس — يمنع انحراف المحرّر عن شريط الإعدادات.
 */
class VoiceEditorProgressTest {

    @Test
    fun factorToProgress_matchesSpeedFactorInverse() {
        assertEquals(100, VoiceEditorProgress.factorToProgress(1.0f, 200))
        assertEquals(200, VoiceEditorProgress.factorToProgress(2.0f, 200))
        assertEquals(25, VoiceEditorProgress.factorToProgress(0.25f, 200))
    }

    @Test
    fun factorToProgress_nullDefaultsToOne() {
        assertEquals(100, VoiceEditorProgress.factorToProgress(null, 200))
    }

    @Test
    fun factorToProgress_clampsBelowMinimum() {
        // معامل 0.1 يرفع للحد الأدنى 0.25 (تقدّم 25).
        assertEquals(25, VoiceEditorProgress.factorToProgress(0.1f, 200))
    }

    @Test
    fun volumeToProgress_mapsPercentage() {
        assertEquals(0, VoiceEditorProgress.volumeToProgress(0.0f))
        assertEquals(50, VoiceEditorProgress.volumeToProgress(0.5f))
        assertEquals(100, VoiceEditorProgress.volumeToProgress(1.0f))
    }

    @Test
    fun volumeToProgress_nullDefaultsToFull() {
        assertEquals(100, VoiceEditorProgress.volumeToProgress(null))
    }

    @Test
    fun volumeToProgress_clampsOutOfRange() {
        assertEquals(100, VoiceEditorProgress.volumeToProgress(2.0f))
        assertEquals(0, VoiceEditorProgress.volumeToProgress(-1.0f))
    }

    @Test
    fun factorLabel_usesDotDecimalSeparator() {
        assertEquals("1.00", VoiceEditorProgress.factorLabel(1.0f))
        assertEquals("0.50", VoiceEditorProgress.factorLabel(0.5f))
    }

    @Test
    fun progressRoundTripsThroughSpeedFactor() {
        // التقدّم ← معامل ← تقدّم يبقى ثابتاً للقيم الصحيحة.
        val progress = 150
        val factor = progress.speedFactor()
        assertEquals(
            progress,
            VoiceEditorProgress.factorToProgress(factor, 200)
        )
    }
}
