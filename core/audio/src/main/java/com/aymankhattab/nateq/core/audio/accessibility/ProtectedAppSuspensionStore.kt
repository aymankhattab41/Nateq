package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.aymankhattab.nateq.core.data.ProtectedApp

/**
 * تخزين حالة إيقاف التطبيق المحمي في سياق تخزين الجهاز المحمي
 * [Context.createDeviceProtectedStorageContext] لتبقى صامدة حتى قبل فك قفل
 * الجهاز عند الإقلاع (الجولة 51 - الأمر 1).
 */
object ProtectedAppSuspensionStore {

    private const val PREFS_NAME = "protected_app_device_protected"
    private const val KEY_PACKAGE = "suspended_pkg"
    private const val KEY_TIMESTAMP = "suspended_timestamp"
    private const val KEY_MODE = "suspended_mode"
    private const val KEY_FAILURE_ANNOUNCED = "failure_announced"

    data class SuspensionState(
        val packageName: String,
        val timestamp: Long,
        val mode: String,
        val failureAnnounced: Boolean = false
    )

    private fun getPrefs(context: Context): SharedPreferences {
        val storageContext = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            runCatching {
                context.createDeviceProtectedStorageContext()
            }.getOrDefault(context)
        } else {
            context
        }
        return storageContext.getSharedPreferences(
            PREFS_NAME, Context.MODE_PRIVATE
        )
    }

    fun save(
        context: Context,
        packageName: String,
        timestamp: Long = System.currentTimeMillis(),
        mode: String = ProtectedApp.MODE_FULL_DISABLE
    ) {
        getPrefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putLong(KEY_TIMESTAMP, timestamp)
            .putString(KEY_MODE, mode)
            .putBoolean(KEY_FAILURE_ANNOUNCED, false)
            .apply()
    }

    fun isFailureAnnounced(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_FAILURE_ANNOUNCED, false)

    fun setFailureAnnounced(context: Context, announced: Boolean = true) {
        getPrefs(context).edit()
            .putBoolean(KEY_FAILURE_ANNOUNCED, announced)
            .apply()
    }

    fun load(context: Context): SuspensionState? {
        val prefs = getPrefs(context)
        val pkg = prefs.getString(KEY_PACKAGE, null) ?: return null
        val time = prefs.getLong(KEY_TIMESTAMP, 0L)
        val mode = prefs.getString(KEY_MODE, ProtectedApp.MODE_FULL_DISABLE)
            ?: ProtectedApp.MODE_FULL_DISABLE
        val announced = prefs.getBoolean(KEY_FAILURE_ANNOUNCED, false)
        return SuspensionState(pkg, time, mode, announced)
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
