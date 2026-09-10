package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات منطق كشف الهزة ومدى التقارب (منطق نقي بلا مستشعرات فعلية). */
class InterruptionSensorsTest {

    @Test
    fun shakeDetector_restPosition_neverTriggers() {
        val detector = ShakeDetector(nowMs = { 1_000_000L })
        assertFalse(detector.onAcceleration(0.5f, 9.8f, 0.2f))
        assertFalse(detector.onAcceleration(9.8f, 0f, 0f))
    }

    @Test
    fun shakeDetector_strongAcceleration_triggers() {
        var clock = 1_000_000L
        val detector = ShakeDetector(nowMs = { clock })
        // x بقوة 25 م/ث²: انحراف 15.2 عن الجاذبية > العتبة 12
        assertTrue(detector.onAcceleration(25f, 0f, 0f))
        assertFalse(detector.onAcceleration(30f, 0f, 0f))
        // بعد فترة الهدوء تُحتسب هزة جديدة
        clock += 2_000
        assertTrue(detector.onAcceleration(0f, 0f, 30f))
    }

    @Test
    fun shakeDetector_weakShake_ignored() {
        val detector = ShakeDetector(nowMs = { 1_000_000L })
        // انحراف 5 فقط: دون العتبة 12
        assertFalse(detector.onAcceleration(13f, 0f, 0f))
    }

    @Test
    fun isProximityNear_usesHalfOfMaxRange() {
        assertTrue(isProximityNear(0f, 5f))
        assertTrue(isProximityNear(2.5f, 5f))
        assertFalse(isProximityNear(2.6f, 5f))
    }

    @Test
    fun isProximityNear_unknownRange_alwaysNear() {
        assertTrue(isProximityNear(0f, 0f))
        assertTrue(isProximityNear(20f, 0f))
        // مدى سالب (قيمة غير متوقعة) يعامل كأنه غير معروف: دائماً قريب
        assertTrue(isProximityNear(20f, -1f))
    }
}