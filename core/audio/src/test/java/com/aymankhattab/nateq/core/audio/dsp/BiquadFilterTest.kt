package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadFilterTest {

    @Test
    fun `filter processes samples without NaN or infinity`() {
        val filter = BiquadFilter()
        filter.configure(
            type = BiquadFilter.Type.LOW_SHELF,
            sampleRate = 22050,
            frequency = 200.0,
            gainDb = 6.0
        )

        var output = 0.0
        for (i in 0 until 100) {
            output = filter.processSample(0.5)
            assertTrue(output.isFinite())
        }
        assertNotNull(output)
    }

    @Test
    fun `filter reset clears internal state`() {
        val filter = BiquadFilter()
        filter.configure(
            type = BiquadFilter.Type.PEAKING,
            sampleRate = 22050,
            frequency = 1000.0,
            gainDb = 3.0
        )
        filter.processSample(1.0)
        filter.reset()
        val afterReset = filter.processSample(0.0)
        assertEquals(0.0, afterReset, 0.0001)
    }
}
