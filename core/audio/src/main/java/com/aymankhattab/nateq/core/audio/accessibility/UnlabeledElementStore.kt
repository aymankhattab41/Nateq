package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import com.aymankhattab.nateq.core.data.SettingsChangeProvider
import com.aymankhattab.nateq.util.NateqJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * مخزن تسميات العناصر غير المسماة متزامن عبر العمليات (ع٢٧-29، البند 21).
 * يربط بين عملية الواجهة الرئيسية وعملية :tts لخدمة الإتاحة عبر
 * ContentObserver وإشعارات فورية دون polling ودون MODE_MULTI_PROCESS.
 */
class UnlabeledElementStore private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val customLabels = ConcurrentHashMap<String, String>()
    private val lastFocused =
        AtomicReference<Pair<String, String>?>(null)
    private val lastFocusedTime = AtomicLong(0L)

    private val observer = object : ContentObserver(
        Handler(Looper.getMainLooper())
    ) {
        override fun onChange(selfChange: Boolean) {
            reloadFromPrefs()
        }
    }

    init {
        reloadFromPrefs()
        runCatching {
            context.contentResolver.registerContentObserver(
                SettingsChangeProvider.unlabeledUri(),
                true,
                observer
            )
        }
    }

    private fun makeKey(
        packageName: String,
        viewIdResourceName: String
    ): String = "$packageName:$viewIdResourceName"

    fun getCustomLabel(
        packageName: String?,
        viewIdResourceName: String?
    ): String? {
        if (packageName.isNullOrBlank() || viewIdResourceName.isNullOrBlank()) {
            return null
        }
        return customLabels[makeKey(packageName, viewIdResourceName)]
    }

    fun setCustomLabel(
        packageName: String,
        viewIdResourceName: String,
        label: String?
    ) {
        val key = makeKey(packageName, viewIdResourceName)
        if (label.isNullOrBlank()) {
            customLabels.remove(key)
        } else {
            customLabels[key] = label.trim()
        }
        saveLabelsToPrefs()
    }

    fun getAllCustomLabels(): Map<String, String> =
        customLabels.toMap()

    /**
     * استبدال كافة التسميات دفعة واحدة (يُستخدم في الاستعادة الكاملة).
     */
    fun replaceAllCustomLabels(newLabels: Map<String, String>) {
        customLabels.clear()
        customLabels.putAll(newLabels)
        saveLabelsToPrefs()
    }

    /**
     * حفظ آخر عنصر مركّز عليه لقارئ الشاشة (خارج تطبيق ناطق)
     * مع طابع زمني للتحقق من الصلاحية (البند 21).
     */
    fun setLastFocusedElement(
        packageName: String,
        viewIdResourceName: String
    ) {
        if (packageName.isBlank() || viewIdResourceName.isBlank()) return
        if (packageName == context.packageName) return

        val now = System.currentTimeMillis()
        lastFocused.set(packageName to viewIdResourceName)
        lastFocusedTime.set(now)

        prefs.edit()
            .putString(KEY_LAST_FOCUSED_PKG, packageName)
            .putString(KEY_LAST_FOCUSED_VIEW_ID, viewIdResourceName)
            .putLong(KEY_LAST_FOCUSED_TIME, now)
            .commit()

        notifyChange()
    }

    /**
     * آخر عنصر مركّز عليه مُلتقط مع التحقق من الصلاحية (أقل من دقيقة افتراضياً)
     * واستبعاد تطبيق ناطق نفسه.
     */
    fun getLastFocusedElement(
        maxAgeMs: Long = DEFAULT_SNAPSHOT_MAX_AGE_MS
    ): Pair<String, String>? {
        val cached = lastFocused.get()
        val cachedTime = lastFocusedTime.get()
        val now = System.currentTimeMillis()

        if (cached != null && (now - cachedTime) <= maxAgeMs) {
            if (cached.first != context.packageName) {
                return cached
            }
        }

        val pkg = prefs.getString(KEY_LAST_FOCUSED_PKG, null)
            ?.takeIf { it.isNotBlank() && it != context.packageName }
            ?: return null
        val viewId = prefs.getString(KEY_LAST_FOCUSED_VIEW_ID, null)
            ?.takeIf { it.isNotBlank() } ?: return null
        val time = prefs.getLong(KEY_LAST_FOCUSED_TIME, 0L)

        if ((now - time) > maxAgeMs) return null

        val pair = pkg to viewId
        lastFocused.set(pair)
        lastFocusedTime.set(time)
        return pair
    }

    @Synchronized
    fun reloadFromPrefs() {
        val json = prefs.getString(KEY_CUSTOM_LABELS, null)
        if (!json.isNullOrBlank()) {
            runCatching {
                val map = NateqJson.parseStringMap(json)
                if (map != null) {
                    customLabels.clear()
                    customLabels.putAll(map)
                }
            }
        } else {
            customLabels.clear()
        }

        val pkg = prefs.getString(KEY_LAST_FOCUSED_PKG, null)
        val viewId = prefs.getString(KEY_LAST_FOCUSED_VIEW_ID, null)
        val time = prefs.getLong(KEY_LAST_FOCUSED_TIME, 0L)
        if (!pkg.isNullOrBlank() && !viewId.isNullOrBlank()) {
            lastFocused.set(pkg to viewId)
            lastFocusedTime.set(time)
        }
    }

    private fun saveLabelsToPrefs() {
        val json = NateqJson.toJson(customLabels)
        prefs.edit().putString(KEY_CUSTOM_LABELS, json).commit()
        notifyChange()
    }

    private fun notifyChange() {
        runCatching {
            context.contentResolver.notifyChange(
                SettingsChangeProvider.unlabeledUri(),
                null
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "nateq_unlabeled_elements"
        private const val KEY_CUSTOM_LABELS = "custom_labels_map"
        private const val KEY_LAST_FOCUSED_PKG = "last_focused_pkg"
        private const val KEY_LAST_FOCUSED_VIEW_ID = "last_focused_view_id"
        private const val KEY_LAST_FOCUSED_TIME = "last_focused_time"
        private const val DEFAULT_SNAPSHOT_MAX_AGE_MS = 60_000L

        @Volatile
        private var instance: UnlabeledElementStore? = null

        fun getInstance(context: Context): UnlabeledElementStore {
            return instance ?: synchronized(this) {
                instance ?: UnlabeledElementStore(
                    context.applicationContext
                ).also { instance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTesting() {
            instance = null
        }
    }
}
