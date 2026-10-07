package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialPannerTest {

    @Test
    fun `monoToStereo doubles sample count and applies panning`() {
        val mono = shortArrayOf(1000, 2000, 3000)
        val stereo = SpatialPanner.monoToStereo(mono, pan = 0.0f)

        assertEquals(6, stereo.size)
        // عند الوسط تكون القناتان متساويتين
        assertEquals(stereo[0], stereo[1])
        assertEquals(stereo[2], stereo[3])
    }
}
