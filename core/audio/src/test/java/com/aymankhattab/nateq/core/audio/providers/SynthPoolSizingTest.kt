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
    fun noEngines_fallsBackToMinThreads() {
        // افتراضياً ذاكرة غير محدودة >= 3GB فيكون الحد الأدنى 2
        assertEquals(2, resolvedPoolSize(0))
        val twoGb = 2L * 1024 * 1024 * 1024
        assertEquals(1, resolvedPoolSize(0, twoGb))
    }

    @Test
    fun singleEngine_resolvesThreads() {
        // بذاكرة كافية >= 3GB يكون الحد الأدنى 2
        // حتى لا تُعطّل التدفئة طلباً حقيقياً
        assertEquals(2, resolvedPoolSize(1))
        val twoGb = 2L * 1024 * 1024 * 1024
        assertEquals(1, resolvedPoolSize(1, twoGb))
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

    @Test
    fun lowMemoryDevice_capsAtTwo() {
        val twoGb = 2L * 1024 * 1024 * 1024
        assertEquals(2, resolvedPoolSize(4, twoGb))
        assertEquals(2, resolvedPoolSize(50, twoGb))
        assertEquals(1, resolvedPoolSize(1, twoGb))
    }

    @Test
    fun midMemoryDevice_capsAtThree() {
        val threeAndHalfGb = (3.5 * 1024 * 1024 * 1024).toLong()
        assertEquals(3, resolvedPoolSize(4, threeAndHalfGb))
        assertEquals(2, resolvedPoolSize(1, threeAndHalfGb))
    }

    @Test
    fun ampleMemoryDevice_keepsEngineCountCap() {
        val eightGb = 8L * 1024 * 1024 * 1024
        assertEquals(4, resolvedPoolSize(4, eightGb))
        assertEquals(MAX_SYNTH_POOL, resolvedPoolSize(50, eightGb))
        assertEquals(2, resolvedPoolSize(2, eightGb))
        assertEquals(2, resolvedPoolSize(1, eightGb))
    }
}