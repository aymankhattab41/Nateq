package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * كاشف الهزة (منطق نقي قابل للاختبار): يعتبر المدخلات هزةً حين يتجاوز انحراف
 * مقدار التسارع الكلي عن جاذبية الأرض عتبةً معينة، مع فترة هدوء تمنع
 * تكرار التنبيهات المتتالية من الهزة الواحدة.
 */
internal class ShakeDetector(
    private val threshold: Float = SHAKE_DELTA_THRESHOLD,
    private val cooldownMs: Long = SHAKE_COOLDOWN_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var lastTriggerMs = 0L

    /** يعيد true إذا كان الطرد الحالي هزةً تستحق الإيقاف. */
    fun onAcceleration(x: Float, y: Float, z: Float): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (abs(magnitude - GRAVITY) < threshold) return false
        val current = nowMs()
        if (current - lastTriggerMs < cooldownMs) return false
        lastTriggerMs = current
        return true
    }
}

/** هل قيمة التقارب قريبة بما يكفي (يد/جيب تغطي الجهاز) لإيقاف النطق؟ */
internal fun isProximityNear(value: Float, maxRange: Float): Boolean =
    maxRange <= 0f || value <= maxRange * PROXIMITY_RATIO

/**
 * رصد إسكات النطق الفوري أثناء الإعلان: يسجّل مستشعري التسارع (الهز) والتقارب
 * (تغطية الجهاز) فقط ما دام النطق جارياً، ويستدعي [onInterrupt] عند تلقي
 * تنبيه صالح. الدوال [onAcceleration]/[onProximity] مدرجة لاختبار منطق الكشف
 * نقيًّا دون تنصيب مستشعرات (Robolectric لا ينصبها فعلياً).
 */
internal class InterruptionSensors(
    private val shakeEnabled: () -> Boolean,
    private val proximityEnabled: () -> Boolean,
    private val onInterrupt: () -> Unit
) {
    private val shakeDetector = ShakeDetector()
    private var shakeRegistered = false
    private var proximityRegistered = false
    private var sensorManager: SensorManager? = null
    private var mainHandler: Handler? = null
    // حالة التقارب السابقة — يبدأ null (لا قراءة بعد) وتُدار وفق انتقالات.
    private var lastProximityNear: Boolean? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    if (shakeDetector.onAcceleration(
                            values[0], values[1], values[2]
                        )
                    ) {
                        onInterrupt()
                    }
                }
                Sensor.TYPE_PROXIMITY -> {
                    val near = isProximityNear(
                        values[0], event.sensor.maximumRange
                    )
                    val previous = lastProximityNear
                    lastProximityNear = near
                    // **بند 4.6:** أول قراءة من المستشعر تُرسل القيمة الحالية
                    // فور التسجيل — الهاتف في الجيب أو مقلوباً وقت وصول الإعلان
                    // تجعله "قريباً" وكانت توقف النطق قبل أول حرف. يُستجاب
                    // للإيقاف فقط عند **انتقال** الحالة من بعيدٍ إلى قريب
                    // (تغطية لاحقة فعلية باليد/الوجه).
                    if (previous == false && near) onInterrupt()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** بدء الرصد (يُستدعى عند بدء النطق). آمن للتكرار. */
    fun start(context: Context) {
        if (shakeRegistered || proximityRegistered) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE)
        if (sm !is SensorManager) return
        sensorManager = sm
        mainHandler = Handler(Looper.getMainLooper())
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        shakeRegistered = accel != null && shakeEnabled() && runCatching {
            sm.registerListener(
                sensorListener, accel,
                SensorManager.SENSOR_DELAY_UI, mainHandler
            )
        }.getOrDefault(false)
        proximityRegistered =
            prox != null && proximityEnabled() &&
                runCatching {
                    sm.registerListener(
                        sensorListener, prox,
                        SensorManager.SENSOR_DELAY_NORMAL, mainHandler
                    )
                }.getOrDefault(false)
    }

    /** إيقاف الرصد (يُستدعى عند انتهاء النطق). آمن للتكرار. */
    fun stop() {
        val sm = sensorManager ?: return
        runCatching {
            if (shakeRegistered) {
                sm.unregisterListener(
                    sensorListener,
                    sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                )
            }
            if (proximityRegistered) {
                sm.unregisterListener(
                    sensorListener,
                    sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                )
            }
        }
        shakeRegistered = false
        proximityRegistered = false
        // يُصفَّر عند كل دورة رصد: القراءةُ الأولى بعد `start` لا تُعتبر
        // انتقالاً مهما بلغت قيمتها (يُلغي مفعول سجلّ الحالة السابقة).
        lastProximityNear = null
    }
}

/** ثابت الجاذبية الأرضية (م/ث²) لتحديد انحراف الهزة عنه. */
private const val GRAVITY = 9.8f

/** عتبة انحراف (م/ث²) تُعتبر معها الحركة هزة توقف النطق. */
private const val SHAKE_DELTA_THRESHOLD = 12f

/** فترة هدوء بين هزتين متعاقبتين تعتبر كل منهما إيقافاً مستقلاً (ملي/ث). */
private const val SHAKE_COOLDOWN_MS = 1500L

/** نسبة المدى الأقصى لمستشعر التقارب التي يُعتبر دونها الجهاز مكشوفاً. */
private const val PROXIMITY_RATIO = 0.5f