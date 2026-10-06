package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long text speedup tests.
 */
class LongTextSpeedupTest {

    @Test
    fun boundaryCheck_disabled_alwaysReturnsOne() {
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(0, enabled = false),
            0.0f
        )
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(299, enabled = false),
            0.0f
        )
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(300, enabled = false),
            0.0f
        )
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(301, enabled = false),
            0.0f
        )
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(1000, enabled = false),
            0.0f
        )
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(5000, enabled = false),
            0.0f
        )
    }

    @Test
    fun boundaryCheck_299_and_300_doNotSpeedUp() {
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(299, enabled = true),
            0.0f
        )
        assertEquals(
            1.0f,
            LongTextSpeedup.multiplier(300, enabled = true),
            0.0f
        )
    }

    @Test
    fun boundaryCheck_301_startsSpeedUp() {
        val mult301 = LongTextSpeedup.multiplier(301, enabled = true)
        assertTrue(mult301 > 1.0f)
        val expected = 1.0f + (1.0f / 1000.0f) * 0.15f
        assertEquals(expected, mult301, 0.00001f)
    }

    @Test
    fun rampCheck_gradualIncrease_andCapAtMax() {
        val mult800 = LongTextSpeedup.multiplier(800, enabled = true)
        assertEquals(1.075f, mult800, 0.0001f)

        val mult1300 = LongTextSpeedup.multiplier(1300, enabled = true)
        assertEquals(1.15f, mult1300, 0.0001f)

        val mult5000 = LongTextSpeedup.multiplier(5000, enabled = true)
        assertEquals(1.15f, mult5000, 0.0001f)
    }

    @Test
    fun applySpeedup_scalesBaseRateCorrectly() {
        val baseRate = 1.2f
        val rate299 = LongTextSpeedup.applySpeedup(
            baseRate, 299, enabled = true
        )
        assertEquals(baseRate, rate299, 0.0001f)

        val rate300 = LongTextSpeedup.applySpeedup(
            baseRate, 300, enabled = true
        )
        assertEquals(baseRate, rate300, 0.0001f)

        val rate301 = LongTextSpeedup.applySpeedup(
            baseRate, 301, enabled = true
        )
        assertTrue(rate301 > baseRate)

        val rate1300 = LongTextSpeedup.applySpeedup(
            baseRate, 1300, enabled = true
        )
        assertEquals(baseRate * 1.15f, rate1300, 0.0001f)
    }
}