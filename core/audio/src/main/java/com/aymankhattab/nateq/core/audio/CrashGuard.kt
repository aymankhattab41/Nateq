package com.aymankhattab.nateq.core.audio

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * حارس الانهيارات ووضع الأمان التلقائي (الأمر 1-6).
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    const val PREFS_NAME = "nateq_crash_guard"
    const val KEY_CRASH_TIMESTAMPS = "crash_timestamps"
    const val KEY_PERMANENT_EMERGENCY = "permanent_emergency"
    const val KEY_SAFE_MODE_UNTIL = "safe_mode_until"
    const val KEY_ANNOUNCED_EMERGENCY = "announced_emergency"

    const val MAX_CRASH_RECORDS = 5
    const val CRASH_WINDOW_RECENT_MS = 120_000L // 120 ثانية
    const val CRASH_WINDOW_TRIPLE_MS = 600_000L // 10 دقائق
    const val SAFE_MODE_DURATION_MS = 600_000L  // 10 دقائق

    @Volatile
    private var handlerInstalled = false

    private fun getStorageContext(context: Context): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext ?: context
        }

    /**
     * تثبيت معالج الاستثناءات غير المُلتقطة في onCreate لكلا العمليتين.
     */
    fun installUncaughtExceptionHandler(context: Context) {
        if (handlerInstalled) return
        handlerInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrash(context, System.currentTimeMillis())
            } catch (t: Throwable) {
                Log.e(TAG, "فشل تسجيل وقت الانهيار", t)
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * تسجيل وقت الانهيار في قائمة آخر 5 انهيارات بـ commit() متزامن.
     */
    fun recordCrash(
        context: Context,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val prefs = getStorageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CRASH_TIMESTAMPS, null)
        val list = raw?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toMutableList() ?: mutableListOf()
        list.add(timestamp)
        val trimmed = if (list.size > MAX_CRASH_RECORDS) {
            list.takeLast(MAX_CRASH_RECORDS)
        } else {
            list
        }
        prefs.edit()
            .putString(KEY_CRASH_TIMESTAMPS, trimmed.joinToString(","))
            .commit()
    }

    /**
     * قراءة أوقات الانهيارات المسجلة.
     */
    fun getCrashTimestamps(context: Context): List<Long> {
        val prefs = getStorageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CRASH_TIMESTAMPS, null)
            ?: return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    /**
     * فحص الانهيارات عند بدء NateqTtsService و AnnouncementSpeaker:
     * إن وُجد انهيار خلال آخر 120 ثانية ⇐ وضع الأمان 10 دقائق.
     * إن حدثت 3 انهيارات خلال 10 دقائق ⇐ وضع الأمان دائم حتى الزر.
     * يُرجع true إذا كان يجب نطق «وضع الطوارئ» لمرة واحدة.
     */
    fun checkOnStartup(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = getStorageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamps = getCrashTimestamps(context)

        val tripleCount = timestamps.count {
            (now - it) in 0..CRASH_WINDOW_TRIPLE_MS
        }
        val isPermanent = tripleCount >= 3
        val hasRecentCrash = timestamps.any {
            (now - it) in 0..CRASH_WINDOW_RECENT_MS
        }

        val editor = prefs.edit()
        var shouldAnnounce = false

        if (isPermanent) {
            editor.putBoolean(KEY_PERMANENT_EMERGENCY, true)
            if (!prefs.getBoolean(KEY_ANNOUNCED_EMERGENCY, false)) {
                shouldAnnounce = true
                editor.putBoolean(KEY_ANNOUNCED_EMERGENCY, true)
            }
        } else if (hasRecentCrash) {
            val safeUntil = now + SAFE_MODE_DURATION_MS
            val currentUntil = prefs.getLong(KEY_SAFE_MODE_UNTIL, 0L)
            if (safeUntil > currentUntil) {
                editor.putLong(KEY_SAFE_MODE_UNTIL, safeUntil)
            }
            if (!prefs.getBoolean(KEY_ANNOUNCED_EMERGENCY, false)) {
                shouldAnnounce = true
                editor.putBoolean(KEY_ANNOUNCED_EMERGENCY, true)
            }
        }
        editor.commit()
        return shouldAnnounce
    }

    /**
     * هل وضع الأمان نشط حالياً؟
     */
    fun isSafeModeActive(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = getStorageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERMANENT_EMERGENCY, false)) return true
        val safeUntil = prefs.getLong(KEY_SAFE_MODE_UNTIL, 0L)
        return now < safeUntil
    }

    /**
     * هل وضع الطوارئ دائم (بسبب 3 انهيارات في 10 دقائق)؟
     */
    fun isPermanentEmergency(context: Context): Boolean {
        val prefs = getStorageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PERMANENT_EMERGENCY, false)
    }

    /**
     * الخروج من وضع الطوارئ (عند ضغط زر الخروج في لوحة الطوارئ).
     */
    fun exitEmergencyMode(context: Context) {
        val prefs = getStorageContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_PERMANENT_EMERGENCY, false)
            .putLong(KEY_SAFE_MODE_UNTIL, 0L)
            .putBoolean(KEY_ANNOUNCED_EMERGENCY, false)
            .remove(KEY_CRASH_TIMESTAMPS)
            .commit()
    }

    /**
     * إعادة التعيين للاختبارات.
     */
    fun resetForTest(context: Context) {
        exitEmergencyMode(context)
    }
}
