package com.aymankhattab.nateq.settings

import androidx.lifecycle.ViewModel
import com.aymankhattab.nateq.engine.PronunciationDictionary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * طبقة الحالة الرئيسية لمشهد الإعدادات.
 *
 * يَحوي المصدرين الثقيلين اللذين كان الفصيل ينشئهما بنفسه عند كل عرض:
 *  - [SettingsRepository]: كل مفاتيح الإعدادات (SharedPreferences عبر الحُقنة).
 *  - [PronunciationDictionary]: قاموس النطق الشخصي (Gson عبر الحُقنة).
 *
 * حقنهما من [com.aymankhattab.nateq.di.AppModule] يمنحهما حياةً واحدة داخل
 * تطبيق Hilt بدل إنشائهما من جديد في كل مرة يُعاد فيها إنشاء Fragment
 * (دوران جهاز، عودة من القفل…)، ويبقى الفصيل مسؤولاً عن العرض والتفاعل فقط.
 *
 * كذلك يحمل [buildBackupJson]/[applyBackupJson]: منطق النسخ الاحتياطي
 * الكامل (إعدادات + قاموس + أسماء متصلين) — منقول هنا من الفصيل ليكون
 * قابلاً للاختبار/لإعادة الاستخدام دون Context، ويقتصر عرض تكوين JSON
 * لدى Fragment (موضع SAF) ولا يخالطه التحويل.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: SettingsRepository,
    val pronunciationDict: PronunciationDictionary
) : ViewModel() {

    companion object {
        /** حدود دفاعية ضد ملفات النسخ الاحتياطي الخبيثة/الضخمة (SAF أو مصادر أخرى). */
        const val MAX_BACKUP_BYTES = 2 * 1024 * 1024   // 2 MB

        /** أقصى عدد يُقبل من عناصر النسخة (أسماء متصلين + إعدادات). */
        const val MAX_BACKUP_ENTRIES = 5000
    }

    // ===== الحالة التفاعلية (البند 8): مراجعة إعدادات تصاعدية =====
    // مصدر حقيقة التحديث الكلي للواجهة: كل تغيير جماعي (استعادة نسخة، إعادة
    // ضبط) يرفع المراجعة، ويجمعها الفصيل عبر StateFlow فيُعيد بناء أقسامه
    // تلقائياً بدل استدعاء refreshAllSettingsUi() يدوياً من كل موضع.
    private val _settingsRevision = MutableStateFlow(0)
    val settingsRevision: StateFlow<Int> = _settingsRevision.asStateFlow()

    /** يُعلن أن الإعدادات تغيّرت تغييراً جماعياً يستلزم إعادة عرض الواجهة كلها. */
    fun notifySettingsChanged() {
        _settingsRevision.update { it + 1 }
    }

    /** بناء ملف JSON كامل: إعدادات مصنفة الأنواع + القاموس + أسماء المتصلين. */
    fun buildBackupJson(): String {
        return try {
            val root = org.json.JSONObject()
            root.put("version", 1)
            root.put("exportedAt", System.currentTimeMillis())

            val settingsObj = org.json.JSONObject()
            settings.exportSettings().forEach { (key, value) ->
                val entry = org.json.JSONObject()
                when (value) {
                    is Float -> { entry.put("type", "float"); entry.put("value", value.toDouble()) }
                    is Int -> { entry.put("type", "int"); entry.put("value", value) }
                    is Long -> { entry.put("type", "int"); entry.put("value", value) }
                    is Boolean -> { entry.put("type", "bool"); entry.put("value", value) }
                    is String -> { entry.put("type", "string"); entry.put("value", value) }
                    is Set<*> -> {
                        val arr = org.json.JSONArray()
                        for (item in value) arr.put(item.toString())
                        entry.put("type", "stringset"); entry.put("value", arr)
                    }
                    else -> return@forEach
                }
                settingsObj.put(key, entry)
            }
            root.put("settings", settingsObj)

            val dictArr = org.json.JSONArray()
            pronunciationDict.getAllEntries().forEach { (word, phon) ->
                dictArr.put(org.json.JSONArray().put(word).put(phon))
            }
            root.put("dictionary", dictArr)

            val callers = org.json.JSONObject()
            runCatching { settings.getCustomCallerNames() }.getOrDefault(emptyMap())
                .forEach { (num, name) -> callers.put(num, name) }
            root.put("callerNames", callers)

            root.toString()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * تطبيق نسخة احتياطية: يتحقق من البنية ثم يستعيد القاموس والأسماء ثم
     * الإعدادات (آخرها لأن استعادتها تمسح القرص) — وتُعقَّل قيم النطاقات
     * داخل SettingsRepository.importSettings.
     */
    fun applyBackupJson(text: String): Boolean {
        return try {
            val root = org.json.JSONObject(text)
            if (root.optInt("version", 0) != 1) return false

            // حدود دفاعية ضد ملفات النسخ الاحتياطي الخبيثة/الضخمة الواردة من SAF
            if (text.length > MAX_BACKUP_BYTES) return false
            val callerNames = root.optJSONObject("callerNames")
            val callerCount = callerNames?.length() ?: 0
            val settingsCount = root.optJSONObject("settings")?.length() ?: 0
            if (callerCount + settingsCount > MAX_BACKUP_ENTRIES) return false

            var applied = false

            val dictArr = root.optJSONArray("dictionary")
            if (dictArr != null) {
                val map = org.json.JSONObject()
                for (i in 0 until dictArr.length()) {
                    val pair = dictArr.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue
                    map.put(pair.getString(0), pair.getString(1))
                }
                applied = pronunciationDict.importFromJson(map.toString()) || applied
            }

            val callers = root.optJSONObject("callerNames")
            if (callers != null && callers.length() > 0) {
                val map = HashMap<String, String>()
                val names = callers.names() ?: org.json.JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    map[key] = callers.getString(key)
                }
                settings.setCustomCallerNames(map)
                applied = true
            }

            val settingsObj = root.optJSONObject("settings")
            if (settingsObj != null && settingsObj.length() > 0) {
                val restored = HashMap<String, Any>()
                val names = settingsObj.names() ?: org.json.JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.getString(i)
                    val entry = settingsObj.optJSONObject(key) ?: continue
                    when (entry.optString("type")) {
                        // "int": نتحقق أن القيمة الطويلة ضمن حدود Int الصحيحة
                        // قبل التحويل حتى لا يُقلب Long خارج المدى إشارته (بند 17)
                        // ويصبح إعداداً معطوباً بلا إنذار بدل رفضه.
                        "int" -> {
                            val longValue = entry.optLong("value", Long.MIN_VALUE)
                            if (longValue < Int.MIN_VALUE || longValue > Int.MAX_VALUE) continue
                            restored[key] = longValue.toInt()
                        }
                        "float" -> restored[key] = entry.optDouble("value", 0.0).toFloat()
                        "bool" -> restored[key] = entry.optBoolean("value")
                        "string" -> restored[key] = entry.optString("value")
                        "stringset" -> {
                            val arr = entry.optJSONArray("value") ?: continue
                            val set = HashSet<String>()
                            for (j in 0 until arr.length()) set.add(arr.getString(j))
                            restored[key] = set
                        }
                    }
                }
                if (restored.isNotEmpty()) {
                    applied = settings.importSettings(restored) || applied
                }
            }

            applied
        } catch (t: Throwable) {
            false
        }.also { applied -> if (applied) notifySettingsChanged() }
    }
}