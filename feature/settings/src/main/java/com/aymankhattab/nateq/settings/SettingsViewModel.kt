package com.aymankhattab.nateq.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.util.NateqJson
import com.aymankhattab.nateq.util.optArray
import com.aymankhattab.nateq.util.optBoolean
import com.aymankhattab.nateq.util.optDouble
import com.aymankhattab.nateq.util.optInt
import com.aymankhattab.nateq.util.optLong
import com.aymankhattab.nateq.util.optObject
import com.aymankhattab.nateq.util.optString
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * طبقة الحالة الرئيسية لمشهد الإعدادات.
 *
 * يَحوي المصدرين الثقيلين اللذين كان الفصيل ينشئهما بنفسه عند كل عرض:
 *  - [SettingsRepository]: كل مفاتيح الإعدادات
 *    (SharedPreferences عبر الحُقنة).
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

    /** مُنشئ اختبار فقط: يبدّل مشغّل العمليات غير المتزامنة بمشغّل محدد
     *  (StandardTestDispatcher) حتى تجري الاختبارات على خيطٍ واحد حتمي
     *  بلا تنسيقِ خيوطَ حقيقية متغيّر. */
    constructor(
        settings: SettingsRepository,
        pronunciationDict: PronunciationDictionary,
        operationsDispatcher: CoroutineDispatcher
    ) : this(settings, pronunciationDict) {
        ioDispatcher = operationsDispatcher
    }

    private var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    companion object {
        /** حدود دفاعية ضد ملفات النسخ الاحتياطي الخبيثة/الضخمة
         *  (SAF أو مصادر أخرى). */
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

    /** يُعلن أن الإعدادات تغيّرت تغييراً جماعياً يستلزم إعادة عرض
         *  الواجهة كلها. */
    fun notifySettingsChanged() {
        _settingsRevision.update { it + 1 }
    }

    // ===== العمليات غير المتزامنة (التصدير/الاستيراد/النسخ الاحتياطي) =====
    // كل القراءة/الكتابة والتشفير وفك التشفير تجري على خيط IO في نطاق
    // viewModelScope: لا توجد أي معالجة ثقيلة على Main أثناء القواميس
    // الكبيرة، والعمليات تنجو من تدوير الشاشة لأنها مرتبطة بحياة الفي إم.
    sealed interface SettingsOperation {
        data class DictImported(val ok: Boolean) : SettingsOperation
        data class DictExported(val ok: Boolean) : SettingsOperation
        data class BackedUp(val ok: Boolean) : SettingsOperation
        data class Restored(val ok: Boolean) : SettingsOperation
    }

    private val _operationEvents =
        MutableSharedFlow<SettingsOperation>(extraBufferCapacity = 1)
    val operationEvents: SharedFlow<SettingsOperation> =
        _operationEvents.asSharedFlow()

    /** تصدير النسخة الاحتياطية (إعدادات + قاموس + أسماء متصلين) إلى uri. */
    fun exportBackup(uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch(ioDispatcher) {
            val ok = runCatching {
                val json = buildBackupJson()
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            _operationEvents.emit(SettingsOperation.BackedUp(ok))
        }
    }

    /** استعادة نسخة احتياطية من uri (قراءة + تفكيك + تطبيق) على IO. */
    fun restoreBackup(uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch(ioDispatcher) {
            val ok = runCatching {
                val text = resolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                text != null && applyBackupJson(text)
            }.getOrDefault(false)
            _operationEvents.emit(SettingsOperation.Restored(ok))
        }
    }

    /** تصدير القاموس وحده إلى uri. */
    fun exportDict(uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch(ioDispatcher) {
            val ok = runCatching {
                val json = pronunciationDict.exportToJson()
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            _operationEvents.emit(SettingsOperation.DictExported(ok))
        }
    }

    /** تطبيق نص قاموس مُقرأ سابقاً (مسار حوار دمج/استبدال) على IO. */
    fun importDict(json: String, merge: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val ok = runCatching {
                pronunciationDict.importFromJson(json, merge)
            }.getOrDefault(false)
            _operationEvents.emit(SettingsOperation.DictImported(ok))
        }
    }

    /** بناء ملف JSON كامل: إعدادات مصنفة الأنواع + القاموس
 *  + أسماء المتصلين. */
    fun buildBackupJson(): String {
        return try {
            val root = JsonObject()
            root.addProperty("version", 1)
            root.addProperty("exportedAt", System.currentTimeMillis())

            val settingsObj = JsonObject()
            settings.exportSettings().forEach { (key, value) ->
                val entry = JsonObject()
                when (value) {
                    is Float -> {
                        entry.addProperty("type", "float")
                        entry.addProperty("value", value.toDouble())
                    }
                    is Int -> {
                        entry.addProperty("type", "int")
                        entry.addProperty("value", value)
                    }
                    is Long -> {
                        entry.addProperty("type", "int")
                        entry.addProperty("value", value)
                    }
                    is Boolean -> {
                        entry.addProperty("type", "bool")
                        entry.addProperty("value", value)
                    }
                    is String -> {
                        entry.addProperty("type", "string")
                        entry.addProperty("value", value)
                    }
                    is Set<*> -> {
                        val arr = JsonArray()
                        for (item in value) arr.add(item.toString())
                        entry.addProperty("type", "stringset")
                            entry.add("value", arr)
                    }
                    else -> return@forEach
                }
                settingsObj.add(key, entry)
            }
            root.add("settings", settingsObj)

            val dictArr = JsonArray()
            pronunciationDict.getAllEntries().forEach { (word, phon) ->
                val pair = JsonArray()
                pair.add(word)
                pair.add(phon)
                dictArr.add(pair)
            }
            root.add("dictionary", dictArr)

            val callers = JsonObject()
            runCatching { settings.getCustomCallerNames() }
                .getOrDefault(emptyMap())
                .forEach { (num, name) -> callers.addProperty(num, name) }
            root.add("callerNames", callers)

            NateqJson.toJson(root)
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
            val root = NateqJson.parseObject(text) ?: return false
            if (root.optInt("version") != 1) return false

            // حدود دفاعية ضد ملفات النسخ الاحتياطي الخبيثة/الضخمة
            // الواردة من SAF
            if (text.length > MAX_BACKUP_BYTES) return false
            val callers = root.optObject("callerNames")
            val settingsObj = root.optObject("settings")
            val callerCount = callers?.size() ?: 0
            val settingsCount = settingsObj?.size() ?: 0
            if (callerCount + settingsCount > MAX_BACKUP_ENTRIES) return false

            var applied = false

            val dictArr = root.optArray("dictionary")
            if (dictArr != null) {
                val map = JsonObject()
                for (i in 0 until dictArr.size()) {
                    // المزاوجات تُخزَّن مصفوفتين [كلمة، نطق] — نُجرّدها بأمان
                    // والقيود/التنظيف واردة في
                    // PronunciationDictionary.importFromJson.
                    val pairArr = dictArr.get(i).optArray() ?: continue
                    if (pairArr.size() < 2) continue
                    val first = pairArr.get(0)
                    val second = pairArr.get(1)
                    if (!first.isJsonPrimitive ||
                        !second.isJsonPrimitive
                    ) continue
                    map.addProperty(first.asString, second.asString)
                }
                applied = pronunciationDict.importFromJson(
                    NateqJson.toJson(map)
                ) || applied
            }

            if (callers != null && callers.size() > 0) {
                val map = HashMap<String, String>()
                for ((key, v) in callers.entrySet()) {
                    if (v.isJsonPrimitive) map[key] = v.asString
                }
                settings.setCustomCallerNames(map)
                applied = true
            }

            if (settingsObj != null && settingsObj.size() > 0) {
                val restored = HashMap<String, Any>()
                for ((key, entryEl) in settingsObj.entrySet()) {
                    val entry = entryEl.optObject() ?: continue
                    when (entry.optString("type")) {
                        // "int": نتحقق أن القيمة الطويلة ضمن حدود Int الصحيحة
                        // قبل التحويل حتى لا يُقلب Long خارج المدى إشارته
                        // (بند 17)
                        // ويصبح إعداداً معطوباً بلا إنذار بدل رفضه.
                        "int" -> {
                            val longValue = entry.optLong(
                                "value",
                                Long.MIN_VALUE
                            )
                            if (longValue < Int.MIN_VALUE ||
                                longValue > Int.MAX_VALUE
                            ) continue
                            restored[key] = longValue.toInt()
                        }
                        "float" -> restored[key] =
                            entry.optDouble("value", 0.0).toFloat()
                        "bool" -> restored[key] = entry.optBoolean("value")
                        "string" -> restored[key] = entry.optString("value")
                        "stringset" -> {
                            val arr = entry.optArray("value") ?: continue
                            val set = HashSet<String>()
                            for (j in 0 until arr.size()) {
                                val v = arr.get(j)
                                if (v.isJsonPrimitive) set.add(v.asString)
                            }
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
