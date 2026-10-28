package com.aymankhattab.nateq.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import com.aymankhattab.nateq.core.audio.engine.SegmentWithOffset
import com.aymankhattab.nateq.core.audio.engine.SpeechRole
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.NateqJson
import com.aymankhattab.nateq.util.optObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Type

/**
 * مخرج مطابقة واحد في آلة Aho-Corasick الموحدة للقاموس
 * (الأوامر 1 و2 و3).
 */
data class DictOutput(
    val normalizedKey: String,
    val originalKey: String,
    val value: String,
    val scope: String?,
    val matchMode: String = "AUTO",
    val valueLang: String? = null
)

/**
 * قاموس النطق الشخصي الموحد — يطبق مرة واحدة على النص الخام قبل أي معالجة.
 * يدعم النطاق العام والطبقات اللغوية، والمطابقة غير الحساسة للحالة
 * اللاتينية، والتخزين بملف JSON موحد بين العمليتين بلا تشفير ولا انقسام.
 */
open class PronunciationDictionary(
    private val context: Context
) {

    constructor(context: Context, throttleMs: Long) : this(context)

    val storageMode: String = "JSON_V2"

    companion object {
        const val ACTION_DICTIONARY_CHANGED =
            "com.aymankhattab.nateq.action.DICTIONARY_CHANGED"

        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val MAX_IMPORT_ENTRIES = 200_000
        const val MAX_KEY_LENGTH = 8_000
        const val MAX_VALUE_LENGTH = 8_000

        private const val FILE_NAME_V2 = "dictionary_v2.json"
        private const val FILE_NAME_TMP = "dictionary_v2.json.tmp"
        private const val FILE_NAME_BACKUP = "dictionary_legacy_backup.json"
        private const val PREFS_NAME = "nateq_pronunciation_dict"
        private const val KEY_DEFAULTS_MIGRATED = "defaults_migrated_to_empty"
        private const val KEY_SCOPES = "dictionary_scopes"
        private const val KEY_MATCH_MODES = "dictionary_match_modes"
        private const val KEY_VALUE_LANGS = "dictionary_value_lang"
        private const val LOG_TAG = "NateqDict"

        @Volatile
        private var sharedInstance: PronunciationDictionary? = null

        fun shared(context: Context): PronunciationDictionary =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: PronunciationDictionary(
                    context.applicationContext
                ).also { sharedInstance = it }
            }

        fun resetSharedForTests() {
            synchronized(this) { sharedInstance = null }
        }

        fun normalizeKey(
            rawKey: String,
            caseInsensitive: Boolean = false
        ): String {
            val sb = java.lang.StringBuilder(rawKey.length)
            var i = 0
            while (i < rawKey.length) {
                val cp = rawKey.codePointAt(i)
                val w = Character.charCount(cp)
                i += w
                if (isTashkeelOrTatweel(cp)) continue
                val normCp = normalizeIndicDigit(cp)
                val finalCp = if (caseInsensitive &&
                    Character.isUpperCase(normCp)
                ) {
                    Character.toLowerCase(normCp)
                } else normCp
                sb.appendCodePoint(finalCp)
            }
            return sb.toString().trim()
        }

        private fun isTashkeelOrTatweel(cp: Int): Boolean =
            (cp in 0x064B..0x065F) || cp == 0x0670 || cp == 0x0640

        private fun normalizeIndicDigit(cp: Int): Int = when (cp) {
            in 0x0660..0x0669 -> '0'.code + (cp - 0x0660)
            in 0x06F0..0x06F9 -> '0'.code + (cp - 0x06F0)
            else -> cp
        }

        /**
         * إزالة علامات قفل اللغة لأي نص لا يمر بالمقسم.
         */
        fun stripLanguageLocks(text: String): String =
            LanguageSegmenter.stripLockMarkers(text)
    }

    private val segmenter = LanguageSegmenter()
    private val dictFile: File = File(context.filesDir, FILE_NAME_V2)
    private val tmpFile: File = File(context.filesDir, FILE_NAME_TMP)
    private val backupFile: File = File(context.filesDir, FILE_NAME_BACKUP)

    @Volatile
    private var entries: Map<String, String> = emptyMap()

    @Volatile
    private var langEntries: Map<String, Map<String, String>> = emptyMap()

    @Volatile
    private var matchModes: Map<String, String> = emptyMap()

    @Volatile
    private var valueLangs: Map<String, String> = emptyMap()

    @Volatile
    private var version = 0L

    @Volatile
    private var lastFileModified = -1L

    @Volatile
    private var lastFileLength = -1L

    /** حقل لاختبار حد الحرف المنفرد برمجياً في اختبارات الوحدة. */
    var testStandaloneMaxLength: Int? = null

    /** حقل لاختبار حساسية الحالة برمجياً في اختبارات الوحدة. */
    var testCaseInsensitive: Boolean? = null

    fun setStandaloneMaxLength(len: Int) {
        testStandaloneMaxLength = len
    }

    private data class MachineCache(
        val version: Long,
        val caseInsensitive: Boolean,
        val machine: UnifiedAhoCorasick
    )

    @Volatile
    private var cache: MachineCache? = null

    private val typeToken: Type = NateqJson.mapStringOf(String::class.java)

    init {
        loadInitialData()
    }

    private fun isCaseInsensitive(): Boolean {
        testCaseInsensitive?.let { return it }
        return runCatching {
            val cls = Class.forName(
                "com.aymankhattab.nateq.core.data.SettingsRepository"
            )
            val createM = cls.getMethod("create", Context::class.java)
            val repo = createM.invoke(null, context)
            val getM = cls.getMethod("isDictionaryCaseInsensitive")
            getM.invoke(repo) as Boolean
        }.getOrElse {
            runCatching {
                context.getSharedPreferences(
                    "nateq_settings", Context.MODE_PRIVATE
                ).getBoolean("dictionary_case_insensitive", true)
            }.getOrDefault(true)
        }
    }

    fun getStandaloneMaxLength(): Int {
        testStandaloneMaxLength?.let { return it }
        return runCatching {
            val cls = Class.forName(
                "com.aymankhattab.nateq.core.data.SettingsRepository"
            )
            val createM = cls.getMethod("create", Context::class.java)
            val repo = createM.invoke(null, context)
            val getM = cls.getMethod("getDictionaryStandaloneMaxLength")
            getM.invoke(repo) as Int
        }.getOrElse {
            runCatching {
                context.getSharedPreferences(
                    "nateq_settings", Context.MODE_PRIVATE
                ).getInt("dictionary_standalone_max_length", 1)
            }.getOrDefault(1)
        }
    }

    private fun loadInitialData() {
        if (dictFile.exists()) {
            loadFromFile()
            removeLegacyDefaultsOnce()
        } else {
            migrateFromLegacyStorage()
            removeLegacyDefaultsOnce()
        }
    }

    private fun migrateFromLegacyStorage() {
        val encData = runCatching {
            openEncryptedPrefs()?.let { loadFromPrefs(it) }
        }.getOrNull()
        val plainData = runCatching {
            val plain = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
            loadFromPrefs(plain)
        }.getOrNull()

        val encCount = (encData?.global?.size ?: 0) +
            (encData?.scopes?.values?.sumOf { it.size } ?: 0)
        val plainCount = (plainData?.global?.size ?: 0) +
            (plainData?.scopes?.values?.sumOf { it.size } ?: 0)

        val best = if (encCount >= plainCount && encData != null) {
            encData
        } else if (plainData != null) {
            plainData
        } else {
            LoadedData(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }

        synchronized(this) {
            entries = best.global
            langEntries = best.scopes
            matchModes = best.modes
            valueLangs = best.vLangs
            version = 1L
            cache = null
        }

        if (best.global.isNotEmpty() || best.scopes.isNotEmpty()) {
            runCatching {
                val json = serializeToJson(version, entries, langEntries,
                    matchModes, valueLangs)
                backupFile.writeText(json, Charsets.UTF_8)
            }
        }
        save()
    }

    private fun openEncryptedPrefs(): android.content.SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    private fun normalizeLangTag(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    private fun removeLegacyDefaultsOnce() {
        var changed = false
        val updated = LinkedHashMap(entries)
        for ((key, badValue) in mapOf(
            "ريال" to "ريال سعودي", "جم" to "الجمعة"
        )) {
            if (updated[key] == badValue) {
                updated.remove(key)
                changed = true
            }
        }
        if (changed) {
            synchronized(this) {
                entries = updated
                cache = null
                version++
            }
            save()
        }
    }

    open fun isPersistent(): Boolean = true

    fun reload() {
        synchronized(this) {
            loadFromFile()
            cache = null
            version++
        }
    }

    fun clearAll(): Boolean {
        synchronized(this) {
            entries = emptyMap()
            langEntries = emptyMap()
            matchModes = emptyMap()
            valueLangs = emptyMap()
            cache = null
            version++
        }
        return save()
    }

    open fun invalidate() {
        lastFileModified = -1L
        lastFileLength = -1L
    }

    open fun reloadIfChanged(): Boolean {
        if (!dictFile.exists()) return false
        val mod = dictFile.lastModified()
        val len = dictFile.length()
        if (mod == lastFileModified && len == lastFileLength) {
            return false
        }
        synchronized(this) {
            if (mod == lastFileModified && len == lastFileLength) {
                return false
            }
            val loaded = loadFromFile()
            lastFileModified = mod
            lastFileLength = len
            cache = null
            return loaded
        }
    }

    private fun swapEntries(updated: Map<String, String>) {
        entries = updated
        cache = null
        version++
    }

    private fun swapLangEntries(lang: String, updated: Map<String, String>) {
        val copy = LinkedHashMap(langEntries)
        if (updated.isEmpty()) copy.remove(lang) else copy[lang] = updated
        langEntries = copy
        cache = null
        version++
    }

    /**
     * تطبيق القاموس على النص الخام باستخدام آلة موحدة من إدخالات العام
     * وكل الطبقات، مع تحديد لغة كل منطقة عبر [segmenter] المشترك.
     */
    open fun apply(text: String, languageTag: String? = null): String {
        return applyInternal(text, languageTag, null)
    }

    open fun apply(
        text: String,
        languageTag: String?,
        precomputedSegments: List<SegmentWithOffset>?
    ): String {
        if (precomputedSegments == null) {
            return apply(text, languageTag)
        }
        return applyInternal(text, languageTag, precomputedSegments)
    }

    private fun applyInternal(
        text: String,
        languageTag: String?,
        precomputedSegments: List<SegmentWithOffset>?
    ): String {
        if (text.isEmpty()) return text
        reloadIfChanged()

        val caseInsensitive = isCaseInsensitive()
        var held = cache
        if (held == null || held.version != version ||
            held.caseInsensitive != caseInsensitive
        ) {
            held = synchronized(this) {
                val currentVersion = version
                val cached = cache
                if (cached != null && cached.version == currentVersion &&
                    cached.caseInsensitive == caseInsensitive
                ) {
                    cached
                } else {
                    val outputs = buildAllOutputs(caseInsensitive)
                    MachineCache(
                        currentVersion,
                        caseInsensitive,
                        UnifiedAhoCorasick(outputs, caseInsensitive)
                    ).also { cache = it }
                }
            }
        }

        val hasLangEntries = synchronized(this) { langEntries.isNotEmpty() }
        val segments = if (precomputedSegments != null) {
            precomputedSegments
        } else if (hasLangEntries) {
            segmenter.segmentWithOffsets(
                text,
                fallbackLanguage = languageTag ?: LanguageCode.AR.tag
            )
        } else {
            listOf(
                SegmentWithOffset(
                    text = text,
                    languageTag = languageTag ?: LanguageCode.AR.tag,
                    role = SpeechRole.TEXT,
                    start = 0,
                    end = text.length
                )
            )
        }

        return held.machine.apply(text, segments, getStandaloneMaxLength())
    }

    /**
     * تتبع مطابقات القاموس على النص وتفصيل أسباب القبول والرفض
     * (الجولة 50 - الأمر 4).
     */
    fun traceMatches(
        text: String,
        languageTag: String? = null
    ): List<DictTraceEntry> {
        if (text.isEmpty()) return emptyList()
        reloadIfChanged()
        val caseInsensitive = isCaseInsensitive()
        var held = cache
        if (held == null || held.version != version ||
            held.caseInsensitive != caseInsensitive
        ) {
            held = synchronized(this) {
                val currentVersion = version
                val cached = cache
                if (cached != null && cached.version == currentVersion &&
                    cached.caseInsensitive == caseInsensitive
                ) {
                    cached
                } else {
                    val outputs = buildAllOutputs(caseInsensitive)
                    MachineCache(
                        currentVersion,
                        caseInsensitive,
                        UnifiedAhoCorasick(outputs, caseInsensitive)
                    ).also { cache = it }
                }
            }
        }
        val hasLangEntries = synchronized(this) { langEntries.isNotEmpty() }
        val segments = if (hasLangEntries) {
            segmenter.segmentWithOffsets(
                text,
                fallbackLanguage = languageTag ?: LanguageCode.AR.tag
            )
        } else {
            listOf(
                SegmentWithOffset(
                    text = text,
                    languageTag = languageTag ?: LanguageCode.AR.tag,
                    role = SpeechRole.TEXT,
                    start = 0,
                    end = text.length
                )
            )
        }
        return held.machine.trace(text, segments, getStandaloneMaxLength())
    }

    private fun buildAllOutputs(caseInsensitive: Boolean): List<DictOutput> {
        val list = ArrayList<DictOutput>()
        for ((key, value) in entries) {
            val normKey = normalizeKey(key, caseInsensitive)
            if (normKey.isEmpty()) continue
            val mode = matchModes["|$key"] ?: "AUTO"
            val vLang = valueLangs["|$key"]
            list.add(
                DictOutput(
                    normalizedKey = normKey,
                    originalKey = key,
                    value = value,
                    scope = null,
                    matchMode = mode,
                    valueLang = vLang
                )
            )
        }
        for ((scopeTag, scopeMap) in langEntries) {
            for ((key, value) in scopeMap) {
                val normKey = normalizeKey(key, caseInsensitive)
                if (normKey.isEmpty()) continue
                val mode = matchModes["$scopeTag|$key"] ?: "AUTO"
                val vLang = valueLangs["$scopeTag|$key"]
                list.add(
                    DictOutput(
                        normalizedKey = normKey,
                        originalKey = key,
                        value = value,
                        scope = scopeTag,
                        matchMode = mode,
                        valueLang = vLang
                    )
                )
            }
        }
        return list
    }

    fun addEntry(
        abbreviation: String,
        pronunciation: String,
        languageTag: String? = null
    ): Boolean = addEntry(
        abbreviation, pronunciation, languageTag, null, null
    )

    fun addEntry(
        abbreviation: String,
        pronunciation: String,
        languageTag: String? = null,
        matchMode: String? = null,
        valueLang: String? = null
    ): Boolean {
        if (abbreviation.isBlank() || pronunciation.isBlank()) return false
        val key = abbreviation.trim()
        val strippedKey = normalizeKey(key, false)
        if (strippedKey.isEmpty()) return false
        val value = pronunciation.trim()
        if (key.length > MAX_KEY_LENGTH || value.length > MAX_VALUE_LENGTH) {
            return false
        }
        val lang = normalizeLangTag(languageTag)
        val modeKey = "${lang.orEmpty()}|$key"

        synchronized(this) {
            if (lang == null) {
                val updated = LinkedHashMap(entries)
                updated[key] = value
                swapEntries(updated)
            } else {
                val overlay = LinkedHashMap(langEntries[lang] ?: emptyMap())
                overlay[key] = value
                swapLangEntries(lang, overlay)
            }

            val modes = LinkedHashMap(matchModes)
            if (!matchMode.isNullOrBlank() && matchMode != "AUTO") {
                modes[modeKey] = matchMode.uppercase()
            } else {
                modes.remove(modeKey)
            }
            matchModes = modes

            val vLangs = LinkedHashMap(valueLangs)
            if (!valueLang.isNullOrBlank()) {
                vLangs[modeKey] = valueLang.trim()
            } else {
                vLangs.remove(modeKey)
            }
            valueLangs = vLangs

            cache = null
            version++
        }
        return save()
    }

    fun removeEntry(
        abbreviation: String,
        languageTag: String? = null
    ): Boolean {
        val lang = normalizeLangTag(languageTag)
        val key = abbreviation.trim()
        val modeKey = "${lang.orEmpty()}|$key"
        var changed = false
        synchronized(this) {
            if (lang == null) {
                val updated = LinkedHashMap(entries)
                changed = updated.remove(key) != null
                if (changed) swapEntries(updated)
            } else {
                val overlay = LinkedHashMap(langEntries[lang] ?: emptyMap())
                changed = overlay.remove(key) != null
                if (changed) swapLangEntries(lang, overlay)
            }
            if (changed) {
                val modes = LinkedHashMap(matchModes)
                modes.remove(modeKey)
                matchModes = modes

                val vLangs = LinkedHashMap(valueLangs)
                vLangs.remove(modeKey)
                valueLangs = vLangs

                cache = null
                version++
            }
        }
        return if (changed) save() else false
    }

    fun getMatchMode(
        abbreviation: String,
        languageTag: String? = null
    ): String {
        val lang = normalizeLangTag(languageTag)
        val modeKey = "${lang.orEmpty()}|${abbreviation.trim()}"
        return matchModes[modeKey] ?: "AUTO"
    }

    fun getValueLang(
        abbreviation: String,
        languageTag: String? = null
    ): String? {
        val lang = normalizeLangTag(languageTag)
        val modeKey = "${lang.orEmpty()}|${abbreviation.trim()}"
        return valueLangs[modeKey]
    }

    fun clear() {
        synchronized(this) {
            swapEntries(emptyMap())
            langEntries = emptyMap()
            matchModes = emptyMap()
            valueLangs = emptyMap()
            cache = null
            version++
        }
        save()
    }

    fun getAllEntries(languageTag: String? = null): Map<String, String> {
        val lang = normalizeLangTag(languageTag)
        return if (lang == null) entries.toMap()
        else (langEntries[lang] ?: emptyMap()).toMap()
    }

    fun findEntryScope(key: String): String? {
        val trimmed = key.trim()
        if (entries.containsKey(trimmed)) return null
        for ((tag, map) in langEntries) {
            if (map.containsKey(trimmed)) return tag
        }
        return null
    }

    fun importFromJson(
        json: String,
        merge: Boolean = false,
        languageTag: String? = null
    ): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        val obj = NateqJson.parseObject(json) ?: return false
        if (obj.size() == 0) return false

        val valid = LinkedHashMap<String, String>()
        for ((rawKey, element) in obj.entrySet()) {
            if (!element.isJsonPrimitive ||
                !element.asJsonPrimitive.isString
            ) {
                return false
            }
            val key = rawKey.trim()
            val value = element.asString.trim()
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return false
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) {
                return false
            }
            valid[key] = value
        }
        if (valid.isEmpty()) return false

        val lang = normalizeLangTag(languageTag)
        val imported = synchronized(this) {
            val base = if (lang == null) entries
            else (langEntries[lang] ?: emptyMap())
            val updated = if (merge) LinkedHashMap(base)
            else LinkedHashMap<String, String>()
            if (updated.size + valid.size > MAX_IMPORT_ENTRIES) {
                return@synchronized -1
            }
            var count = 0
            for ((key, value) in valid) {
                updated[key] = value
                count++
            }
            if (count > 0) {
                if (lang == null) swapEntries(updated)
                else swapLangEntries(lang, updated)
            }
            count
        }
        if (imported <= 0) return false
        return save()
    }

    fun exportToJson(languageTag: String? = null): String {
        val lang = normalizeLangTag(languageTag)
        val source = if (lang == null) entries
        else (langEntries[lang] ?: emptyMap())
        return NateqJson.toJson(source)
    }

    fun exportModesToJson(): String = NateqJson.toJson(matchModes)

    fun importModesFromJson(json: String): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        val parsed = parseEntries(json)
        synchronized(this) {
            val updated = LinkedHashMap(matchModes)
            updated.putAll(parsed)
            matchModes = updated
            cache = null
            version++
        }
        return save()
    }

    private data class LoadedData(
        val global: Map<String, String>,
        val scopes: Map<String, Map<String, String>>,
        val modes: Map<String, String>,
        val vLangs: Map<String, String>
    )

    private fun loadFromFile(): Boolean {
        if (!dictFile.exists()) return false
        return try {
            val text = dictFile.readText(Charsets.UTF_8)
            val root = NateqJson.parseObject(text) ?: return false
            val fileVer = root.get("version")?.asLong ?: (version + 1)
            val globalObj = root.get("entries")?.optObject()
            val scopesObj = root.get("scopes")?.optObject()
            val modesObj = root.get("modes")?.optObject()
            val vLangsObj = root.get("value_langs")?.optObject()

            val gMap = parseObjectToMap(globalObj)
            val sMap = LinkedHashMap<String, Map<String, String>>()
            if (scopesObj != null) {
                for ((tag, element) in scopesObj.entrySet()) {
                    val sub = parseObjectToMap(element.optObject())
                    if (sub.isNotEmpty()) sMap[tag.trim()] = sub
                }
            }
            val mMap = parseObjectToMap(modesObj)
            val vMap = parseObjectToMap(vLangsObj)

            synchronized(this) {
                version = fileVer
                entries = gMap
                langEntries = sMap
                matchModes = mMap
                valueLangs = vMap
                cache = null
            }
            lastFileModified = dictFile.lastModified()
            lastFileLength = dictFile.length()
            true
        } catch (t: Throwable) {
            android.util.Log.e(LOG_TAG, "فشل قراءة قاموس JSON", t)
            false
        }
    }

    private fun parseObjectToMap(
        obj: com.google.gson.JsonObject?
    ): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in obj.entrySet()) {
            if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                out[k.trim()] = v.asString.trim()
            }
        }
        return out
    }

    private fun loadFromPrefs(
        sp: android.content.SharedPreferences
    ): LoadedData {
        val globalJson = try {
            sp.getString("dictionary", "{}")
        } catch (_: Throwable) {
            "{}"
        }
        val scopesJson = try {
            sp.getString(KEY_SCOPES, "{}")
        } catch (_: Throwable) {
            "{}"
        }
        val modesJson = try {
            sp.getString(KEY_MATCH_MODES, "{}")
        } catch (_: Throwable) {
            "{}"
        }
        val vLangsJson = try {
            sp.getString(KEY_VALUE_LANGS, "{}")
        } catch (_: Throwable) {
            "{}"
        }

        return LoadedData(
            global = parseEntries(globalJson),
            scopes = parseScopes(scopesJson),
            modes = parseEntries(modesJson),
            vLangs = parseEntries(vLangsJson)
        )
    }

    private fun parseEntries(json: String?): Map<String, String> {
        val raw = if (json == null) emptyMap<Any, Any>()
        else (NateqJson.fromJson<Map<*, *>>(json, typeToken)
            as? Map<*, *> ?: emptyMap<Any, Any>())
        val fresh = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            if (k !is String || v !is String) continue
            val key = k.trim()
            val value = v.trim()
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) continue
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) continue
            fresh[key] = value
        }
        return fresh
    }

    private fun parseScopes(
        json: String?
    ): Map<String, Map<String, String>> {
        val root = NateqJson.parseObject(json) ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, String>>()
        for ((rawTag, element) in root.entrySet()) {
            val tag = rawTag.trim()
            if (tag.isEmpty()) continue
            val layer = element.optObject() ?: continue
            val parsed = LinkedHashMap<String, String>()
            for ((rawKey, rawValue) in layer.entrySet()) {
                if (!rawValue.isJsonPrimitive) continue
                val key = rawKey.trim()
                val value = rawValue.asString.trim()
                if (key.isEmpty() || key.length > MAX_KEY_LENGTH) continue
                if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) {
                    continue
                }
                parsed[key] = value
            }
            if (parsed.isNotEmpty()) out[tag] = parsed
        }
        return out
    }

    private fun serializeToJson(
        ver: Long,
        g: Map<String, String>,
        s: Map<String, Map<String, String>>,
        m: Map<String, String>,
        v: Map<String, String>
    ): String {
        val root = com.google.gson.JsonObject()
        root.addProperty("version", ver)
        val gObj = com.google.gson.JsonObject()
        for ((k, value) in g) gObj.addProperty(k, value)
        root.add("entries", gObj)

        val sObj = com.google.gson.JsonObject()
        for ((tag, map) in s) {
            val subObj = com.google.gson.JsonObject()
            for ((k, value) in map) subObj.addProperty(k, value)
            sObj.add(tag, subObj)
        }
        root.add("scopes", sObj)

        val mObj = com.google.gson.JsonObject()
        for ((k, value) in m) mObj.addProperty(k, value)
        root.add("modes", mObj)

        val vObj = com.google.gson.JsonObject()
        for ((k, value) in v) vObj.addProperty(k, value)
        root.add("value_langs", vObj)

        return root.toString()
    }

    private fun save(): Boolean {
        return try {
            val jsonStr = serializeToJson(
                version, entries, langEntries, matchModes, valueLangs
            )
            val parent = tmpFile.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(tmpFile).use { fos ->
                fos.write(jsonStr.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (dictFile.exists()) {
                dictFile.delete()
            }
            val ok = tmpFile.renameTo(dictFile)
            if (ok) {
                lastFileModified = dictFile.lastModified()
                lastFileLength = dictFile.length()
                notifyChanged()
            }
            ok
        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "فشل حفظ القاموس الذري", e)
            false
        }
    }

    private fun notifyChanged() {
        runCatching {
            context.sendBroadcast(
                android.content.Intent(ACTION_DICTIONARY_CHANGED)
                    .setPackage(context.packageName)
            )
        }
    }
}

/**
 * نتيجة فحص مطابقة واحدة في أداة تتبع القاموس (الجولة 50 - الأمر 4).
 */
data class DictTraceEntry(
    val key: String,
    val value: String,
    val matchMode: String,
    val accepted: Boolean,
    val reason: String,
    val origStart: Int,
    val origEnd: Int
)

/**
 * آلة Aho-Corasick موحدة تجمع إدخالات العام وكل الطبقات اللغوية،
 * وتطبق قواعد قبول الحرف المنفرد والمطابقة الحساسة/غير الحساسة لحالة الأحرف.
 */
private class UnifiedAhoCorasick(
    entries: List<DictOutput>,
    private val caseInsensitive: Boolean
) {

    private class Node {
        val children = HashMap<Char, Node>()
        val outputs = ArrayList<DictOutput>()
        var fail: Node? = null
    }

    private data class MatchCandidate(
        val origStart: Int,
        val origEnd: Int,
        val output: DictOutput,
        val replacement: String
    )

    private val root = Node()

    init {
        for (entry in entries) {
            val key = entry.normalizedKey
            if (key.isEmpty()) continue
            var node = root
            for (ch in key) {
                node = node.children.getOrPut(ch) { Node() }
            }
            node.outputs.add(entry)
        }

        val queue = ArrayDeque<Node>()
        for (child in root.children.values) {
            child.fail = root
            queue.add(child)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for ((ch, child) in current.children) {
                var fail = current.fail
                while (fail != null && !fail.children.containsKey(ch)) {
                    fail = fail.fail
                }
                val targetFail = if (fail != null) fail.children[ch] else root
                child.fail = targetFail
                if (targetFail != null && targetFail.outputs.isNotEmpty()) {
                    child.outputs.addAll(targetFail.outputs)
                }
                queue.add(child)
            }
        }
    }

    fun apply(
        text: String,
        segments: List<SegmentWithOffset>,
        standaloneMaxLength: Int
    ): String {
        if (text.isEmpty()) return text

        val normSb = StringBuilder(text.length)
        val normToOrig = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val origIdx = i
            val cp = text.codePointAt(i)
            val w = Character.charCount(cp)
            i += w
            if (isTashkeelOrTatweel(cp)) continue
            val normCp = normalizeIndicDigit(cp)
            val matchCp = if (caseInsensitive &&
                Character.isUpperCase(normCp)
            ) {
                Character.toLowerCase(normCp)
            } else normCp

            val startLen = normSb.length
            normSb.appendCodePoint(matchCp)
            val addedLen = normSb.length - startLen
            for (k in 0 until addedLen) {
                normToOrig.add(origIdx)
            }
        }
        val normText = normSb.toString()
        if (normText.isEmpty()) return text

        fun origEndForNorm(normEnd: Int): Int =
            if (normEnd < normToOrig.size) normToOrig[normEnd] else text.length

        val candidates = ArrayList<MatchCandidate>()
        var node = root
        for (idx in 0 until normText.length) {
            val ch = normText[idx]
            while (node !== root && !node.children.containsKey(ch)) {
                node = node.fail ?: root
            }
            node = node.children[ch] ?: root
            if (node.outputs.isEmpty()) continue

            val normEnd = idx + 1
            for (out in node.outputs) {
                val normStart = normEnd - out.normalizedKey.length
                if (normStart < 0) continue
                val origStart = normToOrig[normStart]
                val origEnd = origEndForNorm(normEnd)

                val regionLang = findRegionLanguage(segments, origStart)
                if (!isScopeMatching(out.scope, regionLang)) {
                    continue
                }

                val (accepted, _) = evaluateMatch(
                    out, text, origStart, origEnd,
                    normText, normStart, normEnd,
                    standaloneMaxLength
                )
                if (!accepted) {
                    continue
                }

                val replacement = if (!out.valueLang.isNullOrBlank()) {
                    "${LanguageSegmenter.LOCK_START}${out.valueLang}" +
                        "${LanguageSegmenter.LOCK_TAG_END}${out.value}" +
                        "${LanguageSegmenter.LOCK_END}"
                } else {
                    out.value
                }

                candidates.add(
                    MatchCandidate(origStart, origEnd, out, replacement)
                )
            }
        }

        if (candidates.isEmpty()) return text

        candidates.sortWith { a, b ->
            if (a.origStart != b.origStart) {
                a.origStart.compareTo(b.origStart)
            } else {
                val aScoped = if (a.output.scope != null) 1 else 0
                val bScoped = if (b.output.scope != null) 1 else 0
                if (aScoped != bScoped) {
                    bScoped.compareTo(aScoped)
                } else {
                    b.origEnd.compareTo(a.origEnd)
                }
            }
        }

        val result = StringBuilder(text.length)
        var cursor = 0
        for (cand in candidates) {
            if (cand.origStart < cursor) continue
            result.append(text, cursor, cand.origStart)
            result.append(cand.replacement)
            cursor = cand.origEnd
        }
        if (cursor < text.length) {
            result.append(text, cursor, text.length)
        }
        return result.toString()
    }

    fun trace(
        text: String,
        segments: List<SegmentWithOffset>,
        standaloneMaxLength: Int
    ): List<DictTraceEntry> {
        if (text.isEmpty()) return emptyList()

        val normSb = StringBuilder(text.length)
        val normToOrig = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val origIdx = i
            val cp = text.codePointAt(i)
            val w = Character.charCount(cp)
            i += w
            if (isTashkeelOrTatweel(cp)) continue
            val normCp = normalizeIndicDigit(cp)
            val matchCp = if (caseInsensitive &&
                Character.isUpperCase(normCp)
            ) {
                Character.toLowerCase(normCp)
            } else normCp

            val startLen = normSb.length
            normSb.appendCodePoint(matchCp)
            val addedLen = normSb.length - startLen
            for (k in 0 until addedLen) {
                normToOrig.add(origIdx)
            }
        }
        val normText = normSb.toString()
        if (normText.isEmpty()) return emptyList()

        fun origEndForNorm(normEnd: Int): Int =
            if (normEnd < normToOrig.size) normToOrig[normEnd] else text.length

        val traceResults = ArrayList<DictTraceEntry>()
        var node = root
        for (idx in 0 until normText.length) {
            val ch = normText[idx]
            while (node !== root && !node.children.containsKey(ch)) {
                node = node.fail ?: root
            }
            node = node.children[ch] ?: root
            if (node.outputs.isEmpty()) continue

            val normEnd = idx + 1
            for (out in node.outputs) {
                val normStart = normEnd - out.normalizedKey.length
                if (normStart < 0) continue
                val origStart = normToOrig[normStart]
                val origEnd = origEndForNorm(normEnd)

                val regionLang = findRegionLanguage(segments, origStart)
                if (!isScopeMatching(out.scope, regionLang)) {
                    traceResults.add(
                        DictTraceEntry(
                            key = out.originalKey,
                            value = out.value,
                            matchMode = out.matchMode,
                            accepted = false,
                            reason =
                                "مرفوض: نطاق اللغة لا يطابق (${out.scope})",
                            origStart = origStart,
                            origEnd = origEnd
                        )
                    )
                    continue
                }

                val (accepted, reason) = evaluateMatch(
                    out, text, origStart, origEnd,
                    normText, normStart, normEnd,
                    standaloneMaxLength
                )
                traceResults.add(
                    DictTraceEntry(
                        key = out.originalKey,
                        value = out.value,
                        matchMode = out.matchMode,
                        accepted = accepted,
                        reason = reason,
                        origStart = origStart,
                        origEnd = origEnd
                    )
                )
            }
        }
        return traceResults
    }

    private fun findRegionLanguage(
        segments: List<SegmentWithOffset>,
        origStart: Int
    ): String {
        for (seg in segments) {
            if (origStart >= seg.start && origStart < seg.end) {
                return seg.languageTag
            }
        }
        return segments.lastOrNull()?.languageTag ?: LanguageCode.AR.tag
    }

    private fun isScopeMatching(
        scope: String?,
        regionLang: String
    ): Boolean {
        if (scope == null) return true
        if (scope.equals(regionLang, ignoreCase = true)) return true
        if (!scope.contains('-') && !scope.contains('_')) {
            return LanguageCode.isSameFamily(scope, regionLang)
        }
        return false
    }

    private val PROTECTED_HIJRI_WORDS = listOf(
        "محرم", "صفر", "ربيع الاول", "ربيع الاخر", "ربيع الأول",
        "ربيع الآخر", "جمادى الاولى", "جمادى الاخرة", "جمادى الأولى",
        "جمادى الآخرة", "جمادى الثانية", "جمادى", "جمادي",
        "الجمادى الاولى", "الجمادى الاخرة", "الجمادى الأولى",
        "الجمادى الآخرة", "الجمادى الثانية", "الجمادى", "الجمادي",
        "جمادي الاولى", "جمادي الاخرة", "جمادي الأولى",
        "جمادي الآخرة", "جمادي الثانية",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
    )

    private fun isInsideProtectedWord(
        normText: String,
        normStart: Int,
        normEnd: Int
    ): Boolean {
        for (word in PROTECTED_HIJRI_WORDS) {
            var idx = 0
            while (true) {
                val pos = normText.indexOf(word, idx)
                if (pos == -1) break
                val wordEnd = pos + word.length
                if (normStart >= pos && normEnd <= wordEnd) {
                    return true
                }
                idx = pos + 1
            }
        }
        return false
    }

    private fun evaluateMatch(
        out: DictOutput,
        text: String,
        origStart: Int,
        origEnd: Int,
        normText: String,
        normStart: Int,
        normEnd: Int,
        standaloneMaxLength: Int
    ): Pair<Boolean, String> {
        val mode = out.matchMode.uppercase()

        // الكلمات الهجرية المحمية محمية دائماً حتى في وضع ANYWHERE
        if (out.normalizedKey.length <= 2 &&
            isInsideProtectedWord(normText, normStart, normEnd)
        ) {
            return false to "مرفوض: يقع داخل كلمة هجرية محمية"
        }

        if (mode == "ANYWHERE") {
            return true to "مقبول: وضع أي موضع (ANYWHERE)"
        }

        val requireStandalone = if (mode == "WHOLE") {
            true
        } else {
            if (standaloneMaxLength <= 0) {
                false
            } else {
                val stripped = out.normalizedKey
                stripped.length <= standaloneMaxLength &&
                    stripped.all { Character.isLetter(it) }
            }
        }

        if (!requireStandalone) {
            return true to "مقبول: لا يتطلب كلمة مستقلة"
        }

        if (hasLetterNeighborBefore(text, origStart)) {
            return false to "مرفوض: حرف جار قبله"
        }
        if (hasLetterNeighborAfter(text, origEnd)) {
            return false to "مرفوض: حرف جار بعده"
        }

        if (normStart > 0 && Character.isLetter(normText[normStart - 1])) {
            return false to "مرفوض: حرف جار قبله في النص المطبّع"
        }
        if (normEnd < normText.length &&
            Character.isLetter(normText[normEnd])
        ) {
            return false to "مرفوض: حرف جار بعده في النص المطبّع"
        }

        return true to "مقبول: كلمة مستقلة بلا أحرف جارة"
    }

    private fun hasLetterNeighborBefore(
        text: String,
        origStart: Int
    ): Boolean {
        var i = origStart - 1
        while (i >= 0) {
            val cp = text.codePointBefore(i + 1)
            val w = Character.charCount(cp)
            if (isIgnoredNeighborMark(cp)) {
                i -= w
                continue
            }
            if (Character.isLetter(cp)) return true
            if (isConnectorChar(cp)) {
                var k = i - w
                while (k >= 0) {
                    val prevCp = text.codePointBefore(k + 1)
                    val pw = Character.charCount(prevCp)
                    if (isIgnoredNeighborMark(prevCp)) {
                        k -= pw
                        continue
                    }
                    return Character.isLetter(prevCp)
                }
                return false
            }
            return false
        }
        return false
    }

    private fun hasLetterNeighborAfter(text: String, origEnd: Int): Boolean {
        var i = origEnd
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = Character.charCount(cp)
            if (isIgnoredNeighborMark(cp)) {
                i += w
                continue
            }
            if (Character.isLetter(cp)) return true
            if (isConnectorChar(cp)) {
                var k = i + w
                while (k < text.length) {
                    val nextCp = text.codePointAt(k)
                    val nw = Character.charCount(nextCp)
                    if (isIgnoredNeighborMark(nextCp)) {
                        k += nw
                        continue
                    }
                    return Character.isLetter(nextCp)
                }
                return false
            }
            return false
        }
        return false
    }

    private fun isIgnoredNeighborMark(cp: Int): Boolean {
        if (isConnectorChar(cp)) return false
        if (cp == 0x00A0 || cp == 0x202F || cp == 0x200B ||
            cp == 0x2060 || cp == 0x200E || cp == 0x200F ||
            cp == 0x061C || cp == 0xFEFF || cp == 0x00AD
        ) return true
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.CONTROL.toInt() ||
            cp == 0x0640
    }

    private fun isConnectorChar(cp: Int): Boolean =
        cp == '\''.code || cp == '’'.code || cp == '-'.code || cp == '_'.code

    private fun isTashkeelOrTatweel(cp: Int): Boolean =
        (cp in 0x064B..0x065F) || cp == 0x0670 || cp == 0x0640

    private fun normalizeIndicDigit(cp: Int): Int = when (cp) {
        in 0x0660..0x0669 -> '0'.code + (cp - 0x0660)
        in 0x06F0..0x06F9 -> '0'.code + (cp - 0x06F0)
        else -> cp
    }
}
