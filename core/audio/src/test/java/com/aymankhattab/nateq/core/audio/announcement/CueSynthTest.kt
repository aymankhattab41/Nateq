package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات مولّد موجات المؤثرات (منطق نقي — لا أندرويد). */
class CueSynthTest {

    private fun isSilent(pcm: ShortArray): Boolean =
        pcm.all { it == (0).toShort() }

    private fun peakAmplitude(pcm: ShortArray): Int =
        pcm.maxOf { maxOf(it.toInt(), -it.toInt()) }

    @Test
    fun `hourly chime classic bell is audible and matches duration`() {
        val cue = AudioCue(CueType.TIME_HOURLY, "classic_bell", 0.6f)
        val pcm = CueSynth.synthesize(cue)
        val expected = CueSynth.durationMs(cue) * CueSynth.SAMPLE_RATE / 1000
        assertFalse(isSilent(pcm))
        assertEquals(expected, pcm.size)
    }

    @Test
    fun `all cue types produce non-silent audio`() {
        val types = CueType.entries
        for (type in types) {
            val cue = AudioCue(type, volume = 0.8f)
            val pcm = CueSynth.synthesize(cue)
            assertTrue(
                "نوع $type لا يُنتج صمتاً",
                !isSilent(pcm)
            )
            assertEquals(
                CueSynth.durationMs(cue) * CueSynth.SAMPLE_RATE / 1000,
                pcm.size
            )
        }
    }

    @Test
    fun `hourly chime sound variants differ`() {
        val classic = CueSynth.synthesize(
            AudioCue(CueType.TIME_HOURLY, "classic_bell")
        )
        val digital = CueSynth.synthesize(
            AudioCue(CueType.TIME_HOURLY, "digital_chime")
        )
        val soft = CueSynth.synthesize(
            AudioCue(CueType.TIME_HOURLY, "soft_ding")
        )
        assertTrue(classic.contentEquals(digital).not())
        assertTrue(classic.contentEquals(soft).not())
    }

    @Test
    fun `null sound name falls back to classic bell length`() {
        val classic = CueSynth.synthesize(
            AudioCue(CueType.TIME_HOURLY, "classic_bell")
        )
        val fallback = CueSynth.synthesize(
            AudioCue(CueType.TIME_HOURLY, null)
        )
        // نفس المدة الزمنية تعني نفس الطول والموجة الفعلية متطابقة
        assertTrue(classic.contentEquals(fallback))
    }

    @Test
    fun `samples stay within 16-bit short range`() {
        val cue = AudioCue(CueType.BATTERY_CHARGING, volume = 1.0f)
        val pcm = CueSynth.synthesize(cue)
        val peak = peakAmplitude(pcm)
        assertTrue(peak > 0)
        assertTrue(peak <= Short.MAX_VALUE.toInt())
    }
}