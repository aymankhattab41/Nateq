package com.aymankhattab.nateq.core.audio

import android.content.Context
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * تكوين صوت الطوارئ الاحتياطي (الأمر 1).
 */
data class EmergencyVoiceConfig(
    val engine: String? = null,
    val voice: String? = null,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val isSaved: Boolean = false
)

/**
 * قراءة وحفظ صوت الطوارئ في ملف تفضيلات مستقل بالتخزين المحمي
 * بالجهاز (الأمر 1).
 */
object EmergencyVoice {

    const val PREFS_NAME = "nateq_emergency_voice"
    const val KEY_EMERGENCY_ENGINE = "emergency_engine"
    const val KEY_EMERGENCY_VOICE = "emergency_voice"
    const val KEY_EMERGENCY_RATE = "emergency_rate"
    const val KEY_EMERGENCY_PITCH = "emergency_pitch"
    const val KEY_EMERGENCY_VOLUME = "emergency_volume"
    const val KEY_EMERGENCY_SAVED = "emergency_saved"

    private fun getStorageContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext ?: context
        }

    /**
     * قراءة صوت الطوارئ مباشرة من التخزين المحمي بالجهاز (يعمل قبل فتح القفل).
     */
    fun load(context: Context): EmergencyVoiceConfig {
        val prefs = try {
            getStorageContext(context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            return EmergencyVoiceConfig()
        }
        val isSaved = prefs.getBoolean(KEY_EMERGENCY_SAVED, false)
        if (!isSaved) {
            return EmergencyVoiceConfig(
                engine = null,
                voice = null,
                rate = 1.0f,
                pitch = 1.0f,
                volume = 1.0f,
                isSaved = false
            )
        }
        val engine = prefs.getString(KEY_EMERGENCY_ENGINE, null)
        val voice = prefs.getString(KEY_EMERGENCY_VOICE, null)
        val rate = prefs.getFloat(KEY_EMERGENCY_RATE, 1.0f).coerceIn(0.5f, 3.0f)
        val pitch = prefs.getFloat(KEY_EMERGENCY_PITCH, 1.0f)
            .coerceIn(0.5f, 2.0f)
        val volume = prefs.getFloat(KEY_EMERGENCY_VOLUME, 1.0f)
            .coerceIn(0.0f, 1.0f)
        return EmergencyVoiceConfig(
            engine = engine,
            voice = voice,
            rate = rate,
            pitch = pitch,
            volume = volume,
            isSaved = true
        )
    }

    /**
     * حفظ صوت الطوارئ في التخزين المحمي بالجهاز متزامناً بـ commit()
     * مع نسخة في nateq_boot_mirror (الأمر 1-2).
     */
    fun save(context: Context, config: EmergencyVoiceConfig) {
        val storageCtx = getStorageContext(context)
        val prefs = storageCtx
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_EMERGENCY_ENGINE, config.engine)
            .putString(KEY_EMERGENCY_VOICE, config.voice)
            .putFloat(KEY_EMERGENCY_RATE, config.rate.coerceIn(0.5f, 3.0f))
            .putFloat(KEY_EMERGENCY_PITCH, config.pitch.coerceIn(0.5f, 2.0f))
            .putFloat(KEY_EMERGENCY_VOLUME, config.volume.coerceIn(0.0f, 1.0f))
            .putBoolean(KEY_EMERGENCY_SAVED, config.isSaved)
            .commit()

        val mirrorCtxs = listOfNotNull(
            storageCtx,
            context.applicationContext,
            context
        ).distinct()
        for (ctx in mirrorCtxs) {
            try {
                ctx.getSharedPreferences(
                    "nateq_boot_mirror",
                    Context.MODE_PRIVATE
                ).edit()
                    .putString(KEY_EMERGENCY_ENGINE, config.engine)
                    .putString(KEY_EMERGENCY_VOICE, config.voice)
                    .putFloat(
                        KEY_EMERGENCY_RATE,
                        config.rate.coerceIn(0.5f, 3.0f)
                    )
                    .putFloat(
                        KEY_EMERGENCY_PITCH,
                        config.pitch.coerceIn(0.5f, 2.0f)
                    )
                    .putFloat(
                        KEY_EMERGENCY_VOLUME,
                        config.volume.coerceIn(0.0f, 1.0f)
                    )
                    .putBoolean(KEY_EMERGENCY_SAVED, config.isSaved)
                    .commit()
            } catch (_: Throwable) {}
        }
    }
}

/**
 * متتبع تعثر التخليق للفئات:
 * عند فشل التخليق مرتين متتاليتين خلال 30 ثانية لفئة ما،
 * يُستعمل صوت الطوارئ لتلك الفئة مدة 5 دقائق (الأمر 1-5ب).
 */
object CategoryFailureTracker {

    private const val FAILURE_WINDOW_MS = 30_000L
    private const val EMERGENCY_DURATION_MS = 300_000L // 5 دقائق

    private class CategoryState {
        val failureTimestamps = mutableListOf<Long>()
        var emergencyUntil: Long = 0L
    }

    private val states = ConcurrentHashMap<String, CategoryState>()

    fun recordFailure(
        category: String,
        now: Long = System.currentTimeMillis()
    ) {
        val state = states.getOrPut(category) { CategoryState() }
        synchronized(state) {
            state.failureTimestamps.removeAll { now - it > FAILURE_WINDOW_MS }
            state.failureTimestamps.add(now)
            if (state.failureTimestamps.size >= 2) {
                state.emergencyUntil = now + EMERGENCY_DURATION_MS
                state.failureTimestamps.clear()
            }
        }
    }

    fun recordSuccess(category: String) {
        val state = states[category] ?: return
        synchronized(state) {
            state.failureTimestamps.clear()
        }
    }

    fun isEmergencyActive(
        category: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val state = states[category] ?: return false
        synchronized(state) {
            return now < state.emergencyUntil
        }
    }

    fun reset() {
        states.clear()
    }
}
