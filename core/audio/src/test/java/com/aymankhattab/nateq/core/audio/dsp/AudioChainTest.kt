package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertNotNull
import org.junit.Test

class AudioChainTest {

    @Test
    fun `audio chain processes short and byte buffers seamlessly`() {
        val chain = AudioChain()
        chain.configure(
            sampleRate = 22050,
            eqEnabled = true,
            compressorEnabled = true,
            reverbEnabled = true
        )

        val samples = ShortArray(200) { 1000.toShort() }
        chain.process(samples)
        assertNotNull(samples)

        val bytes = ByteArray(400)
        chain.processBytes(bytes)
        assertNotNull(bytes)
    }
}
