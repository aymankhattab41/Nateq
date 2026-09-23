package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.aymankhattab.nateq.core.data.SettingsRepository
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
 * (تغطية الجهاز) ما دامت الخدمة/الدورة حية، ويستدعي [onInterrupt] عند تلقي
 * تنبيه صالح. [shakeEnabled]/[proximityEnabled] يُقرآن **حيّاً عند كل حدث
 * استشعار** فلا تُجمَّد مفاتيح الإعدادات وقت الإنشاء: تغيير الإعداد يستجيب
 * فوراً دون إعادة تسجيل (بند 8). التسجيل يعتمد على وجود المستشعر في الجهاز
 * فقط لا على قيم المفاتيح؛ الفلتران يُطبَّقان عند الحدث.
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
                Sensor.TYPE_ACCELEROMETER ->
                    onAcceleration(values[0], values[1], values[2])
                Sensor.TYPE_PROXIMITY ->
                    onProximity(values[0], event.sensor.maximumRange)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** معالجة قراءة تسارع (يستدعيها المستمع؛ مكشوفة لاختبار المنطق
     *  نقيًّا دون تنصيب مستشعرات). يقرأ مفتاح الإسكات بالهز **حيّاً**
     *  عند كل قراءة (بند 8): تعطيلٌ لاحقٌ بلا مستخدم يوقفه فوراً،
     *  وتفعيلٌ لاحقٌ يستجيب بلا إعادة تسجيل. */
    internal fun onAcceleration(x: Float, y: Float, z: Float) {
        if (shakeEnabled() &&
            shakeDetector.onAcceleration(x, y, z)
        ) {
            onInterrupt()
        }
    }

    /** معالجة قراءة تقارب (يستدعيها المستمع؛ مكشوفة للاختبار النقي).
     *  يقرأ مفتاح الإسكات بالتقارب حيّاً؛ ويستجيب للإيقاف عند انتقال
     *  الحالة من بعيدٍ إلى قريب فقط. */
    internal fun onProximity(value: Float, maxRange: Float) {
        if (!proximityEnabled()) return
        val near = isProximityNear(value, maxRange)
        val previous = lastProximityNear
        lastProximityNear = near
        // **بند 4.6:** أول قراءة من المستشعر تُرسل القيمة الحالية
        // فور التسجيل — الهاتف في الجيب أو مقلوباً وقت وصول الإعلان
        // تجعله "قريباً" وكانت توقف النطق قبل أول حرف. يُستجاب
        // للإيقاف فقط عند **انتقال** الحالة من بعيدٍ إلى قريب
        // (تغطية لاحقة فعلية باليد/الوجه).
        if (previous == false && near) onInterrupt()
    }

    /** بدء الرصد (يُستدعى عند بدء النطق). آمن للتكرار.
     *  **بند 6.1:** مُزامَن عبر [@Synchronized] — بدءُ دورة نطقٍ على خيط
     *  (ThreadUsage) مع انتهاء سابقتها على خيط النطق (TTS callback)
     *  كان قد يسخّران [sensorManager] و[sensorListener] في وقت واحد. */
    @Synchronized
    fun start(context: Context) {
        if (shakeRegistered || proximityRegistered) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE)
        if (sm !is SensorManager) return
        sensorManager = sm
        mainHandler = Handler(Looper.getMainLooper())
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        // التسجيل يعتمد على وجود المستشعر فقط؛ قيمتا [shakeEnabled] و
        // [proximityEnabled] تُقرآن حيّاً عند كل حدَث (بند 8) فلا تُجمَّد
        // الإعدادات وقت الإنشاء — التغيير يستجيب دون إعادة تسجيل.
        shakeRegistered = accel != null && runCatching {
            sm.registerListener(
                sensorListener, accel,
                SensorManager.SENSOR_DELAY_UI, mainHandler
            )
        }.getOrDefault(false)
        proximityRegistered =
            prox != null &&
                runCatching {
                    sm.registerListener(
                        sensorListener, prox,
                        SensorManager.SENSOR_DELAY_NORMAL, mainHandler
                    )
                }.getOrDefault(false)
    }

    /** إيقاف الرصد (يُستدعى عند انتهاء النطق). آمن للتكرار.
     *  مُزامَن مقابل [start] (بند 6.1). */
    @Synchronized
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
        mainHandler?.removeCallbacksAndMessages(null)
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