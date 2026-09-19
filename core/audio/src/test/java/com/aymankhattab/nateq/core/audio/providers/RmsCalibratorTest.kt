package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مجمّع معايرة RMS (بند الأوامر د.3.3): تجميد المتوسّط بعد
 * [RMS_CALIBRATION_SAMPLES] عيّنات، بذرٌ بالمحفوظ، ورفضٌ للتالف.
 */
class RmsCalibratorTest {

    @Test
    fun settles_afterFiveSamples_withFrozenAverage() {
        val calibrator = RmsCalibrator()
        assertFalse(calibrator.isSettled())
        assertNull(calibrator.current())
        for (i in 1..4) {
            calibrator.observe(2.0f)
            assertFalse("لا تجميد قبل الخامسة", calibrator.isSettled())
        }
        calibrator.observe(2.0f)
        assertTrue(calibrator.isSettled())
        assertEquals(2.0f, calibrator.current()!!, 0.001f)
        // عيّنات لاحقة لا تحرّك المجمَّد.
        calibrator.observe(4.0f)
        assertEquals(2.0f, calibrator.current()!!, 0.001f)
    }

    @Test
    fun runningAverage_reflectsObservedSamples() {
        val calibrator = RmsCalibrator()
        calibrator.observe(1.0f)
        calibrator.observe(3.0f)
        assertEquals(2.0f, calibrator.current()!!, 0.001f)
    }

    @Test
    fun invalidScales_areIgnored() {
        val calibrator = RmsCalibrator()
        calibrator.observe(Float.NaN)
        calibrator.observe(Float.POSITIVE_INFINITY)
        calibrator.observe(0f)
        calibrator.observe(-1f)
        assertNull(calibrator.current())
        assertFalse(calibrator.isSettled())
    }

    @Test
    fun seed_marksSettledImmediately() {
        val calibrator = RmsCalibrator()
        calibrator.seed(1.5f)
        assertTrue(calibrator.isSettled())
        assertEquals(1.5f, calibrator.current()!!, 0.001f)
        calibrator.seed(Float.NaN)
        assertEquals(
            "التالف لا يبدّل البذر", 1.5f, calibrator.current()!!, 0.001f
        )
    }

    @Test
    fun reset_clearsForRecalibration() {
        val calibrator = RmsCalibrator()
        calibrator.seed(3.0f)
        calibrator.reset()
        assertFalse(calibrator.isSettled())
        assertNull(calibrator.current())
    }
}
