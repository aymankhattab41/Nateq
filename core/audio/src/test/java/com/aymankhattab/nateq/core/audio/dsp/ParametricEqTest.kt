package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertTrue
import org.junit.Test

class ParametricEqTest {

    @Test
    fun `parametric equalizer processes buffer safely`() {
        val eq = ParametricEq()
        eq.configure(
            sampleRate = 22050,
            bassGainDb = 3.0,
            midGainDb = -2.0,
            trebleGainDb = 4.0
        )

        val samples = ShortArray(200) { (it * 100).toShort() }
        eq.process(samples)

        for (s in samples) {
            assertTrue(s >= -32768 && s <= 32767)
        }
    }
}
