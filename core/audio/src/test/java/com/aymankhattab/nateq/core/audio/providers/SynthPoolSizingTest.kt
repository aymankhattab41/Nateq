package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبار دينامية منفّذ التخليق (مرحلة 7): [resolvedPoolSize] يقيس حجمَ
 * المنفّخ من عدد محركات TTS المثبّتة فعلياً لا عتبةٍ جامدة — نصٌّ مختلط
 * عبر N محركٍ يتوازى حتى N، ضمن حدّ أعلى [MAX_SYNTH_POOL] وأدنى خيطٍ
 * واحد. الدالةُ نقيّة بلا Context فتختبر مباشرةً بلا Robolectric.
 */
class SynthPoolSizingTest {

    @Test
    fun noEngines_fallsBackToOneThread() {
        assertEquals(1, resolvedPoolSize(0))
    }

    @Test
    fun singleEngine_singleThread() {
        assertEquals(1, resolvedPoolSize(1))
    }

    @Test
    fun twoEngines_twoThreads() {
        assertEquals(2, resolvedPoolSize(2))
    }

    @Test
    fun manyEngines_cappedAtPoolMax() {
        assertEquals(MAX_SYNTH_POOL, resolvedPoolSize(50))
        assertEquals(MAX_SYNTH_POOL, resolvedPoolSize(MAX_SYNTH_POOL + 3))
    }
}