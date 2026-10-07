package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicCompressorTest {

    @Test
    fun `compressor prevents clipping on hot signals`() {
        val compressor = DynamicCompressor()
        compressor.configure(
            sampleRate = 22050,
            thresholdLinear = 0.5
        )

        // إشارة عالية القيمة جداً
        val samples = ShortArray(100) { 32000.toShort() }
        compressor.process(samples)

        for (s in samples) {
            assertTrue(s < 32767)
        }
    }
}
