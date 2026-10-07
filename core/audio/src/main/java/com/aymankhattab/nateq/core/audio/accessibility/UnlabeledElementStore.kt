package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.SharedPreferences
import com.aymankhattab.nateq.util.NateqJson
import java.util.concurrent.ConcurrentHashMap

/**
 * مخزن تسميات العناصر غير المسماة (ع٢٧-29، ع٢٧-30).
 * يتيح تخصيص أسماء نطق للأزرار التي تفتقر إلى contentDescription.
 */
class UnlabeledElementStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val customLabels = ConcurrentHashMap<String, String>()

    init {
        loadFromPrefs()
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
        saveToPrefs()
    }

    fun getAllCustomLabels(): Map<String, String> =
        customLabels.toMap()

    private fun loadFromPrefs() {
        val json = prefs.getString(KEY_CUSTOM_LABELS, null) ?: return
        runCatching {
            val map = NateqJson.parseStringMap(json) ?: return
            customLabels.clear()
            customLabels.putAll(map)
        }
    }

    private fun saveToPrefs() {
        val json = NateqJson.toJson(customLabels)
        prefs.edit().putString(KEY_CUSTOM_LABELS, json).apply()
    }

    companion object {
        private const val PREFS_NAME = "nateq_unlabeled_elements"
        private const val KEY_CUSTOM_LABELS = "custom_labels_map"

        @Volatile
        private var instance: UnlabeledElementStore? = null

        fun getInstance(context: Context): UnlabeledElementStore {
            return instance ?: synchronized(this) {
                instance ?: UnlabeledElementStore(
                    context.applicationContext
                ).also { instance = it }
            }
        }
    }
}
