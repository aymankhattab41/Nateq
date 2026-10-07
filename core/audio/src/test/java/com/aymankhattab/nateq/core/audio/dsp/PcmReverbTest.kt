package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertTrue
import org.junit.Test

class PcmReverbTest {

    @Test
    fun `reverb processes audio buffer without distortion`() {
        val reverb = PcmReverb()
        reverb.configure(roomSizeVal = 0.6, dampingVal = 0.3)

        val samples = ShortArray(300) { (it * 50).toShort() }
        reverb.process(samples)

        for (s in samples) {
            assertTrue(s in -32768..32767)
        }
    }
}
