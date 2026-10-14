package com.aymankhattab.nateq.core.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.aymankhattab.nateq.engine.ConvertPreferencesCodec
import java.io.File

/**
 * هجرة بيانات المستخدم بعد حذف محرك Sherpa-ONNX / Piper (ع٤٠-7).
 *
 * عند أول تشغيل بعد الحذف:
 * 1. حذف مجلدات Piper / SherpaTTS / espeak-ng-data من filesDir.
 * 2. حذف ملفات engine_status_sherpa*.json و hostlog_sherpa* من filesDir.
 * 3. حذف ملفات Piper من مجلد التنزيلات downloads.
 * 4. إعادة أي إعداد محفوظ يشير إلى المحرك sherpa_onnx_embedded (أو piper)
 *    إلى «افتراضي» (null) في فئات الصوت ولغات التحويل.
 * 5. حذف مفاتيح التفضيلات الميتة الخاصة بالمحرك المحذوف.
 * 6. إشعار واحد (Toast) للمستخدم إن تم تصحيح أي إعداد صراحةً.
 */
class LegacySherpaMigrator(
    private val context: Context,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "SherpaMigrator"
        const val KEY_MIGRATED = "_migrated_sherpa_removal"
        private const val PREFS_NAME = "nateq_settings"

        private val SHERPA_IDENTIFIERS = setOf(
            "sherpa_onnx_embedded",
            "sherpa_onnx_piper",
            "sherpa_piper",
            "sherpa",
            "piper"
        )

        private val DEAD_PREF_KEYS = listOf(
            "embedded_storage_migrated_SHERPA",
            "embedded_storage_migrated_PIPER",
            "embedded_storage_migrated_sherpa",
            "embedded_storage_migrated_piper"
        )
    }

    /**
     * ينفّذ الهجرة إن لم تكن قد نُفذت مسبقاً.
     * يعيد true إذا جرى تنفيذ الهجرة، وfalse إن كانت قد تمت سابقاً.
     */
    fun migrateIfNeeded(showToast: Boolean = true): Boolean {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (sp.getBoolean(KEY_MIGRATED, false)) {
            return false
        }

        var resetAnySetting = false

        // 1. حذف المجلدات والملفات الميتة
        cleanupLegacyFiles()

        // 2. فحص وتصفير فئات الصوت
        for (cat in SettingsRepository.ALL_VOICE_CATEGORIES) {
            val engine = settings.getEngineForCategory(cat)
            if (isSherpaOrPiper(engine)) {
                settings.setEngineForCategory(cat, null)
                resetAnySetting = true
                Log.i(
                    TAG,
                    "Reset category '$cat' engine from '$engine' to null"
                )
            }
        }

        // فئة الإيموجي إن وُجدت
        val emojiEngine = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_EMOJI
        )
        if (isSherpaOrPiper(emojiEngine)) {
            settings.setEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_EMOJI,
                null
            )
            resetAnySetting = true
        }

        // فحص محرك الإعلانات الاحتياطي
        val fallbackEng = settings.getAnnouncementFallbackEngine()
        if (isSherpaOrPiper(fallbackEng)) {
            settings.setAnnouncementFallbackEngine(null)
            resetAnySetting = true
        }

        // 3. فحص لغات التحويل التلقائي
        val allConvertPrefs = settings.allConvertLanguagePreferences()
        if (allConvertPrefs.isNotEmpty()) {
            val mutableMap = allConvertPrefs.toMutableMap()
            var convertMapChanged = false
            for ((langTag, pref) in allConvertPrefs) {
                if (isSherpaOrPiper(pref.engine)) {
                    mutableMap[langTag] = pref.copy(engine = null)
                    convertMapChanged = true
                    resetAnySetting = true
                    Log.i(
                        TAG,
                        "Reset convert pref engine for lang '$langTag' to null"
                    )
                }
            }
            if (convertMapChanged) {
                val json = ConvertPreferencesCodec.toJson(mutableMap)
                sp.edit().putString("convert_language_prefs", json).apply()
            }
        }

        // السلوتات القديمة 1 و 2 للتحويل
        if (isSherpaOrPiper(settings.getConvertEngine1())) {
            settings.setConvertEngine1(null)
            resetAnySetting = true
        }
        if (isSherpaOrPiper(settings.getConvertEngine2())) {
            settings.setConvertEngine2(null)
            resetAnySetting = true
        }

        // 4. حذف مفاتيح التفضيلات الميتة
        val editor = sp.edit()
        for (deadKey in DEAD_PREF_KEYS) {
            if (sp.contains(deadKey)) {
                editor.remove(deadKey)
            }
        }

        // وضع علامة اكتمال الهجرة
        editor.putBoolean(KEY_MIGRATED, true).apply()

        // 5. إشعار المستخدم إن أُعيدت إعدادات إلى الافتراضي
        if (resetAnySetting && showToast) {
            notifyUserOnMainThread()
        }

        Log.i(
            TAG,
            "Legacy Sherpa/Piper migration finished (reset=$resetAnySetting)"
        )
        return true
    }

    private fun isSherpaOrPiper(engineId: String?): Boolean {
        if (engineId.isNullOrBlank()) return false
        val lower = engineId.trim().lowercase()
        return SHERPA_IDENTIFIERS.any { id ->
            lower == id || lower.contains("sherpa") || lower.contains("piper")
        }
    }

    private fun cleanupLegacyFiles() {
        val filesDir = context.filesDir

        // المجلدات القديمة
        val dirSherpa = File(filesDir, "SherpaTTS")
        if (dirSherpa.exists()) dirSherpa.deleteRecursively()

        val dirPiper = File(filesDir, "Piper")
        if (dirPiper.exists()) dirPiper.deleteRecursively()

        val dirEspeak = File(filesDir, "espeak-ng-data")
        if (dirEspeak.exists()) dirEspeak.deleteRecursively()

        // ملفات الحالة والسجلات
        filesDir.listFiles()?.forEach { file ->
            val name = file.name
            if (name.startsWith("engine_status_sherpa") &&
                name.endsWith(".json")
            ) {
                file.delete()
            } else if (name.startsWith("hostlog_sherpa")) {
                file.delete()
            }
        }

        // ملفات التنزيل الجزئية أو المحملة لـ Piper
        val downloadsDir = File(filesDir, "downloads")
        if (downloadsDir.exists()) {
            downloadsDir.listFiles()?.forEach { file ->
                if (file.name.lowercase().contains("piper")) {
                    file.delete()
                }
            }
        }
    }

    private fun notifyUserOnMainThread() {
        Handler(Looper.getMainLooper()).post {
            try {
                val resId = context.resources.getIdentifier(
                    "piper_engine_removed",
                    "string",
                    context.packageName
                )
                val msg = if (resId != 0) {
                    context.getString(resId)
                } else {
                    "حُذف محرك Piper؛ أُعيدت الأصوات إلى الافتراضي"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to display migration Toast", t)
            }
        }
    }
}
