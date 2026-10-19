package com.aymankhattab.nateq.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import com.aymankhattab.nateq.core.audio.engine.SegmentWithOffset
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.NateqJson
import com.aymankhattab.nateq.util.optObject
import java.lang.reflect.Type

/**
 * مخرج مطابقة واحد في آلة Aho-Corasick الموحدة للقاموس (الأوامر 1 و2 و3).
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
 * يدعم النطاق العام والطبقات اللغوية حسب منطقة الكلمة (الأمر 1)،
 * وتطبيق الحرف المنفرد فقط (الأمر 2)، وقفل لغة النطق (الأمر 3).
 */
class PronunciationDictionary(
    private val context: Context,
    private val diskCheckThrottleNanos: Long = DISK_CHECK_THROTTLE_NANOS
) {

    companion object {
        const val ACTION_DICTIONARY_CHANGED =
            "com.aymankhattab.nateq.action.DICTIONARY_CHANGED"

        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val MAX_IMPORT_ENTRIES = 200_000
        const val MAX_KEY_LENGTH = 8_000
        const val MAX_VALUE_LENGTH = 8_000

        private const val DISK_CHECK_THROTTLE_NANOS = 1_500_000_000L
        private const val KEY_DEFAULTS_MIGRATED = "defaults_migrated_to_empty"
        private const val KEY_SCOPES = "dictionary_scopes"
        private const val KEY_MATCH_MODES = "dictionary_match_modes"
        private const val KEY_VALUE_LANGS = "dictionary_value_lang"

        const val PREFS_NAME = "nateq_pronunciation_dict"
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

        fun normalizeKey(rawKey: String): String {
            val sb = java.lang.StringBuilder(rawKey.length)
            var i = 0
            while (i < rawKey.length) {
                val cp = rawKey.codePointAt(i)
                val w = Character.charCount(cp)
                i += w
                if (isTashkeelOrTatweel(cp)) continue
                val normCp = normalizeIndicDigit(cp)
                sb.appendCodePoint(normCp)
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
         * إزالة علامات قفل اللغة لأي نص لا يمر بالمقسم (الأمر 3).
         */
        fun stripLanguageLocks(text: String): String =
            LanguageSegmenter.stripLockMarkers(text)
    }

    enum class StorageMode { ENCRYPTED, PLAIN, MEMORY_ONLY }

    private val storageRef: StorageMode
    private val prefs: android.content.SharedPreferences?

    init {
        val encrypted = runCatching { openEncryptedPrefs() }.getOrNull()
        val plain = if (encrypted == null) {
            runCatching {
                context.getSharedPreferences(
                    PREFS_NAME, android.content.Context.MODE_PRIVATE
                )
            }.getOrNull()
        } else null

        when {
            encrypted != null -> {
                storageRef = StorageMode.ENCRYPTED
                prefs = encrypted
            }
            plain != null -> {
                storageRef = StorageMode.PLAIN
                prefs = plain
                android.util.Log.w(
                    LOG_TAG,
                    "تعذّر فتح التخزين المشفّر لقاموس النطق؛ التحويل إلى" +
                        " تخزين عادي"
                )
            }
            else -> {
                storageRef = StorageMode.MEMORY_ONLY
                prefs = null
                android.util.Log.w(
                    LOG_TAG,
                    "تعذّر فتح أيّ تخزين لقاموس النطق؛ سيعمل بالذاكرة"
                )
            }
        }
    }

    val storageMode: StorageMode get() = storageRef

    private fun openEncryptedPrefs(): android.content.SharedPreferences? {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun openFreshPrefs(): android.content.SharedPreferences? =
        when (storageRef) {
            StorageMode.ENCRYPTED -> runCatching { openEncryptedPrefs() }
                .getOrNull()
            StorageMode.PLAIN -> runCatching {
                context.getSharedPreferences(
                    PREFS_NAME, android.content.Context.MODE_PRIVATE
                )
            }.getOrNull()
            StorageMode.MEMORY_ONLY -> null
        }

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

    /**
     * حقل لاختبار حد الحرف المنفرد برمجياً في اختبارات الوحدة.
     */
    var testStandaloneMaxLength: Int? = null

    fun setStandaloneMaxLength(len: Int) {
        testStandaloneMaxLength = len
    }

    private data class MachineCache(
        val version: Long,
        val machine: UnifiedAhoCorasick
    )

    @Volatile
    private var cache: MachineCache? = null

    private val typeToken: Type = NateqJson.mapStringOf(String::class.java)

    private val prefsFile: java.io.File? =
        if (prefs != null) context.filesDir?.parentFile
            ?.let {
                java.io.File(it, "shared_prefs/nateq_pronunciation_dict.xml")
            }
        else null

    @Volatile
    private var lastStamp: Long? = null

    @Volatile
    private var lastDiskCheckNanos = 0L

    init {
        val loaded = loadFromPrefs(prefs)
        entries = loaded.global
        langEntries = loaded.scopes
        matchModes = loaded.modes
        valueLangs = loaded.vLangs
        removeLegacyDefaultsOnce()
        if (prefs != null) lastStamp = currentStamp()
    }

    private fun normalizeLangTag(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    private fun removeLegacyDefaultsOnce() {
        val sp = prefs ?: return
        if (sp.getBoolean(KEY_DEFAULTS_MIGRATED, false)) return
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
            swapEntries(updated)
            save()
        }
        sp.edit().putBoolean(KEY_DEFAULTS_MIGRATED, true).apply()
    }

    fun isPersistent(): Boolean = prefs != null

    fun reload() {
        if (prefs == null) return
        val loaded = loadFromPrefs()
        synchronized(this) {
            entries = loaded.global
            langEntries = loaded.scopes
            matchModes = loaded.modes
            valueLangs = loaded.vLangs
            cache = null
            version++
        }
        lastStamp = currentStamp()
    }

    fun clearAll(): Boolean {
        if (prefs == null) return false
        synchronized(this) {
            entries = emptyMap()
            langEntries = emptyMap()
            matchModes = emptyMap()
            valueLangs = emptyMap()
            cache = null
            version++
        }
        val ok = try {
            prefs.edit().clear().commit()
        } catch (e: Exception) {
            false
        }
        lastDiskCheckNanos = 0L
        return ok
    }

    fun invalidate() {
        lastDiskCheckNanos = 0L
        lastStamp = null
    }

    fun reloadIfChanged(): Boolean {
        if (prefs == null) return false
        val now = System.nanoTime()
        if (lastDiskCheckNanos != 0L &&
            now - lastDiskCheckNanos < diskCheckThrottleNanos
        ) return false
        lastDiskCheckNanos = now
        val stamp = currentStamp()
        if (lastStamp == stamp) return false
        val loaded = loadFromPrefs()
        synchronized(this) {
            entries = loaded.global
            langEntries = loaded.scopes
            matchModes = loaded.modes
            valueLangs = loaded.vLangs
            cache = null
            version++
        }
        lastStamp = stamp
        return true
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

    private fun currentStamp(): Long =
        prefsFile?.let { if (it.exists()) it.lastModified() else 0L } ?: 0L

    private fun getStandaloneMaxLength(): Int {
        testStandaloneMaxLength?.let { return it }
        return runCatching {
            context.getSharedPreferences(
                "nateq_settings", Context.MODE_PRIVATE
            ).getInt("dictionary_standalone_max_length", 1)
        }.getOrDefault(1)
    }

    /**
     * تطبيق القاموس على النص الخام باستخدام آلة موحدة من إدخالات العام
     * وكل الطبقات، مع تحديد لغة كل منطقة عبر LanguageSegmenter (الأمر 1).
     */
    fun apply(text: String, languageTag: String? = null): String {
        if (text.isEmpty()) return text
        reloadIfChanged()

        var held = cache
        if (held == null || held.version != version) {
            held = synchronized(this) {
                val currentVersion = version
                val cached = cache
                if (cached != null && cached.version == currentVersion) {
                    cached
                } else {
                    val outputs = buildAllOutputs()
                    MachineCache(
                        currentVersion,
                        UnifiedAhoCorasick(outputs)
                    ).also { cache = it }
                }
            }
        }

        val segmenter = LanguageSegmenter()
        val segments = segmenter.segmentWithOffsets(
            text,
            fallbackLanguage = languageTag ?: LanguageCode.AR.tag
        )

        return held.machine.apply(text, segments, getStandaloneMaxLength())
    }

    private fun buildAllOutputs(): List<DictOutput> {
        val list = ArrayList<DictOutput>()
        // 1. الإدخالات العامة
        for ((key, value) in entries) {
            val normKey = normalizeKey(key)
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
        // 2. إدخالات كل الطبقات اللغوية
        for ((scopeTag, scopeMap) in langEntries) {
            for ((key, value) in scopeMap) {
                val normKey = normalizeKey(key)
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
        val strippedKey = normalizeKey(key)
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
        lastDiskCheckNanos = 0L
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
        lastDiskCheckNanos = 0L
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
        lastDiskCheckNanos = 0L
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

        val sp = prefs ?: return true
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

    private fun loadFromPrefs(
        sourceSp: android.content.SharedPreferences? = null
    ): LoadedData {
        val sp = sourceSp ?: openFreshPrefs() ?: return LoadedData(
            entries, langEntries, matchModes, valueLangs
        )
        val globalJson = try {
            sp.getString("dictionary", "{}")
        } catch (t: Throwable) {
            return LoadedData(entries, langEntries, matchModes, valueLangs)
        }
        val scopesJson = try {
            sp.getString(KEY_SCOPES, "{}")
        } catch (t: Throwable) {
            return LoadedData(
                parseEntries(globalJson), langEntries, matchModes, valueLangs
            )
        }
        val modesJson = try {
            sp.getString(KEY_MATCH_MODES, "{}")
        } catch (t: Throwable) {
            "{}"
        }
        val vLangsJson = try {
            sp.getString(KEY_VALUE_LANGS, "{}")
        } catch (t: Throwable) {
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

    private fun save(): Boolean {
        val sp = prefs ?: return false
        return try {
            val ok = sp.edit()
                .putString("dictionary", NateqJson.toJson(entries))
                .putString(KEY_SCOPES, NateqJson.toJson(langEntries))
                .putString(KEY_MATCH_MODES, NateqJson.toJson(matchModes))
                .putString(KEY_VALUE_LANGS, NateqJson.toJson(valueLangs))
                .commit()
            lastStamp = currentStamp()
            lastDiskCheckNanos = 0L
            if (ok) notifyChanged()
            ok
        } catch (e: Exception) {
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
 * آلة Aho-Corasick موحدة تجمع إدخالات العام وكل الطبقات اللغوية،
 * وتطبق قواعد قبول الحرف المنفرد (الأمر 2) والمطابقة بلا فقدان مواضع (الأمر 1).
 */
private class UnifiedAhoCorasick(entries: List<DictOutput>) {

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

        // بناء النسخة المطبعة وجدول إرجاع المواضع (الأمر 1)
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
            val startLen = normSb.length
            normSb.appendCodePoint(normCp)
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

                // 1. فحص لغة المنطقة (الأمر 1)
                val regionLang = findRegionLanguage(segments, origStart)
                if (!isScopeMatching(out.scope, regionLang)) {
                    continue
                }

                // 2. فحص قبول المطابقة والحرف المنفرد (الأمر 2)
                if (!isMatchAccepted(
                        out, text, origStart, origEnd, standaloneMaxLength
                    )
                ) {
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

        // الأولوية: البداية أولاً، ثم الطبقة الخاصة تعلو العام، ثم الأطول
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

    private fun isMatchAccepted(
        out: DictOutput,
        text: String,
        origStart: Int,
        origEnd: Int,
        standaloneMaxLength: Int
    ): Boolean {
        val mode = out.matchMode.uppercase()
        if (mode == "ANYWHERE") return true

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

        if (!requireStandalone) return true

        if (hasLetterNeighborBefore(text, origStart)) return false
        if (hasLetterNeighborAfter(text, origEnd)) return false

        return true
    }

    private fun hasLetterNeighborBefore(text: String, origStart: Int): Boolean {
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
        if (cp in 0x064B..0x065F || cp == 0x0670) return true
        if (cp == 0x0640) return true
        if (cp == 0x200E || cp == 0x200F || cp == 0x061C ||
            cp in 0x202A..0x202E || cp in 0x2066..0x2069
        ) return true
        if (cp == 0x200C || cp == 0x200D) return true
        return false
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
