package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertTrue
import org.junit.Test

class EarconGeneratorTest {

    @Test
    fun `generateTone returns non-empty PCM samples for all cues`() {
        for (cue in EarconGenerator.CueType.values()) {
            val pcm = EarconGenerator.generateTone(cue, sampleRate = 22050)
            assertTrue(pcm.isNotEmpty())
            // تأكد من أن القيم محصورة في المدى الطبيعي
            for (s in pcm) {
                assertTrue(s in -32768..32767)
            }
        }
    }
}
