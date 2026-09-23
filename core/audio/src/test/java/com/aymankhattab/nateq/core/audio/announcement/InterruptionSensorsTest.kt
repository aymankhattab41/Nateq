package com.aymankhattab.nateq.core.audio.announcement

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorManager
import com.aymankhattab.nateq.core.audio.engine.NateqTtsService
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

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

/**
 * اختبارات بند 8 (Robolectric): «تسجيل واحد فقط» وتفعيل الإعداد الحيّ.
 * 1) عشرات الاستدعاءات المتتالية لبدء الرصد (كانت تتكرر مع كل دورة
 *    onSynthesizeText) تسجّل مستمعَي المستشعرات مرة واحدة فقط؛
 * 2) مفاتيح الإعدادات ([shakeEnabled]/[proximityEnabled]) تُقرأ حيّاً
 *    عند كل حدَث استشعار لا قيمةً مجمَّدة وقت الإنشاء — تغيير الإعداد
 *    يستجيب فوراً بلا إعادة تشغيل الخدمة/إعادة تسجيل.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class InterruptionSensorsRegistrationTest {

    private val app: Application =
        RuntimeEnvironment.getApplication()

    private val sensorManager: SensorManager =
        app.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val shadowSm: ShadowSensorManager = shadowOf(sensorManager)

    /** توفير مستشعري التسارع والتقارب الظليين حتى يكتمل التسجيل. */
    private fun installFakeSensors() {
        shadowSm.addSensor(
            ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        )
        shadowSm.addSensor(
            ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY)
        )
    }

    @Test
    fun repeatedStarts_registerSingleListener() {
        installFakeSensors()
        var interrupts = 0
        val sensors = InterruptionSensors(
            shakeEnabled = { true },
            proximityEnabled = { true },
            onInterrupt = { interrupts++ }
        )
        repeat(50) { sensors.start(app) }
        // مستمعٌ واحد مهما تكرر بدء الرصد — لا تراكم تسجيلات.
        assertEquals(1, shadowSm.getListeners().size)
        assertEquals(0, interrupts)
    }

    @Test
    fun service_repeatedStarts_registerExactlyOnce() {
        installFakeSensors()
        val service = NateqTtsService::class.java
            .getDeclaredConstructor().newInstance()
        // إرفاق سياق التطبيق كي يعمل applicationContext (بدل onCreate).
        val mBase = ContextWrapper::class.java
            .getDeclaredField("mBase")
        mBase.isAccessible = true
        mBase.set(service, app)
        val settingsField = NateqTtsService::class.java
            .getDeclaredField("settings")
        settingsField.isAccessible = true
        settingsField.set(service, SettingsRepository.create(app))
        val start = NateqTtsService::class.java
            .getDeclaredMethod("startInterruptionMonitoring")
        start.isAccessible = true
        val sensorsField = NateqTtsService::class.java
            .getDeclaredField("interruptionSensors")
        sensorsField.isAccessible = true
        // «عشرات الاستدعاءات المتتالية» كما كانت تحدث مع كل onSynthesizeText:
        // كائنُ الاستشعار واحدٌ ثابت والمستمعان مُسجَّلان مرة واحدة.
        val seen = mutableListOf<Any>()
        repeat(50) {
            start.invoke(service)
            seen += sensorsField.get(service)
        }
        assertEquals(1, seen.distinct().size)
        assertEquals(1, shadowSm.getListeners().size)
    }

    @Test
    fun shakeFlag_readLiveAtEachEvent() {
        var shakeOn = false
        var interrupts = 0
        val sensors = InterruptionSensors(
            shakeEnabled = { shakeOn },
            proximityEnabled = { false },
            onInterrupt = { interrupts++ }
        )
        // x بقوة 30 م/ث²: فوق العتبة، لكنها مقروءة بحاجز الحيّة.
        sensors.onAcceleration(30f, 0f, 0f)
        assertEquals(0, interrupts)
        // التفعيل الفوري يستجيب بنفس التسجيل (لا إعادة تسجيل).
        shakeOn = true
        sensors.onAcceleration(30f, 0f, 0f)
        assertEquals(1, interrupts)
        // التعطيل الفوري يوقف الاستجابة فوراً.
        shakeOn = false
        sensors.onAcceleration(30f, 0f, 0f)
        assertEquals(1, interrupts)
    }

    @Test
    fun proximityFlag_readLiveAndTransitionsOnly() {
        var proxOn = false
        var interrupts = 0
        val sensors = InterruptionSensors(
            shakeEnabled = { false },
            proximityEnabled = { proxOn },
            onInterrupt = { interrupts++ }
        )
        // معطّل: القراءات لا تُحدَّث الحالة ولا تقطع.
        sensors.onProximity(0f, 5f)
        assertEquals(0, interrupts)
        // تفعيل: أول قراءة قريبة بداية جديدة — لا تمثّل انتقالاً.
        proxOn = true
        sensors.onProximity(0f, 5f)
        assertEquals(0, interrupts)
        // انتقال فعلي بعيد ← قريب يقطع.
        sensors.onProximity(5f, 5f)
        sensors.onProximity(0f, 5f)
        assertEquals(1, interrupts)
        // تعطيل فوري: القريب لا يقطع بينما المفتاح مغلق.
        proxOn = false
        sensors.onProximity(0f, 5f)
        assertEquals(1, interrupts)
        // إعادة التفعيل تُكمل الرصد الحيّ من آخر حالة (بعيد).
        proxOn = true
        sensors.onProximity(5f, 5f)
        assertEquals(1, interrupts)
        sensors.onProximity(0f, 5f)
        assertEquals(2, interrupts)
    }
}