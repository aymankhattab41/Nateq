package com.aymankhattab.nateq.core.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTextSpeedupTest {

    @Test
    fun `short texts or disabled setting return 1_0x multiplier`() {
        assertEquals(
            1.0f,
            LongTextSpeedup.calculateSpeedMultiplier(100, isEnabled = true),
            0.001f
        )
        assertEquals(
            1.0f,
            LongTextSpeedup.calculateSpeedMultiplier(500, isEnabled = false),
            0.001f
        )
    }

    @Test
    fun `long texts with enabled setting scale speed up to max boost`() {
        val mult =
            LongTextSpeedup.calculateSpeedMultiplier(800, isEnabled = true)
        assertTrue(mult > 1.0f)
        assertTrue(mult <= 1.0f + LongTextSpeedup.MAX_BOOST)
    }
}
