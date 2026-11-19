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
import java.text.BreakIterator
import java.text.Normalizer

/**
 * مخرج مطابقة واحد في آلة Aho-Corasick الموحدة للقاموس.
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
 * المطابقة حساسة للتشكيل افتراضياً (مع تكافؤ Unicode القانوني وتجاهل
 * التطويل التجميلي فقط)، وتُطبَّق في جميع النطاقات وأوضاع الحدود.
 */
open class PronunciationDictionary(private val context: Context) {

    constructor(
        context: Context,
        @Suppress("UNUSED_PARAMETER") throttleMs: Long
    ) : this(context)

    companion object {
        private const val PREFS_NAME = "nateq_pronunciation_dict"
        private const val KEY_DICT = "dictionary"
        private const val KEY_MODIFIED = "modified_at"
        private const val KEY_SCOPES = "dictionary_scopes"
        private const val KEY_MATCH_MODES = "dictionary_match_modes"
        private const val KEY_VALUE_LANGS = "dictionary_value_langs"

        private const val LOG_TAG = "PronunciationDict"
        const val ACTION_DICTIONARY_CHANGED =
            "com.aymankhattab.nateq.ACTION_DICTIONARY_CHANGED"
        private const val DICT_FILE_NAME = "dictionary_v2.json"
        private const val DICT_TMP_FILE_NAME = "dictionary_v2.json.tmp"
        private const val LEGACY_BACKUP_FILE_NAME =
            "dictionary_legacy_backup.json"

        const val MAX_KEY_LENGTH = 100
        const val MAX_VALUE_LENGTH = 500
        const val MAX_IMPORT_BYTES = 512 * 1024
        const val MAX_IMPORT_ENTRIES = 10000
        const val MIN_STANDALONE_LENGTH = 0
        const val MAX_STANDALONE_LENGTH = 15
        const val DEFAULT_STANDALONE_MAX_LENGTH = 1

        @Volatile
        private var instance: PronunciationDictionary? = null

        fun shared(context: Context): PronunciationDictionary =
            instance ?: synchronized(this) {
                instance ?: PronunciationDictionary(context.applicationContext)
                    .also { instance = it }
            }

        fun resetSharedForTests() {
            synchronized(this) { instance = null }
        }

        /** تطويل تجميلي فقط — يُتجاهل في المطابقة ولا يُعدّ تشكيلًا. */
        internal fun isTatweel(cp: Int): Boolean = cp == 0x0640

        /** هل المقطع تحويليّ/تركيبي (لا يُعدّ محتوىً أساسياً للمفتاح)؟ */
        internal fun isCombiningMark(cp: Int): Boolean {
            val type = Character.getType(cp)
            return type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
        }

        /**
         * تطبيع موحّد للمطابقة: يزيل التطويل، يطبّق تكافؤ Unicode القانوني
         * (NFC) وترتيب العلامات المكافئ، ويحفظ التشكيل والعلامات ذات المعنى،
         * مع توحيد الأرقام الهندية/العربية وحالة الأحرف اللاتينية كما هو.
         */
        internal fun normalizeForMatch(
            text: String,
            caseInsensitive: Boolean = true
        ): NormalizedText {
            if (text.isEmpty()) {
                return NormalizedText(
                    "", IntArray(0), BooleanArray(1) { true }
                )
            }
            val bi = BreakIterator.getCharacterInstance()
            bi.setText(text)
            val normSb = StringBuilder(text.length)
            val normToOrig = ArrayList<Int>(text.length)
            val boundaryList = ArrayList<Boolean>(text.length + 1)

            var start = bi.first()
            var end = bi.next()
            while (end != BreakIterator.DONE) {
                val cluster = text.substring(start, end)
                // إزالة التطويل من داخل العنقود
                val noTatweel = if (cluster.indexOf('\u0640') >= 0) {
                    buildString(cluster.length) {
                        var i = 0
                        while (i < cluster.length) {
                            val cp = cluster.codePointAt(i)
                            val w = Character.charCount(cp)
                            if (!isTatweel(cp)) appendCodePoint(cp)
                            i += w
                        }
                    }
                } else cluster

                if (noTatweel.isNotEmpty()) {
                    val nfc = Normalizer.normalize(
                        noTatweel, Normalizer.Form.NFC
                    )
                    val folded = foldCaseAndDigits(nfc, caseInsensitive)
                    if (folded.isNotEmpty()) {
                        while (boundaryList.size < normSb.length) {
                            boundaryList.add(false)
                        }
                        boundaryList.add(true) // بداية هذا العنقود
                        val baseNormIdx = normSb.length
                        normSb.append(folded)
                        val added = normSb.length - baseNormIdx
                        while (boundaryList.size < normSb.length) {
                            boundaryList.add(false)
                        }
                        repeat(added) { normToOrig.add(start) }
                    }
                }
                start = end
                end = bi.next()
            }
            while (boundaryList.size <= normSb.length) {
                boundaryList.add(boundaryList.size == normSb.length)
            }
            if (boundaryList.isNotEmpty()) {
                boundaryList[boundaryList.size - 1] = true
            }
            val boundaries = BooleanArray(boundaryList.size)
            for (i in boundaryList.indices) boundaries[i] = boundaryList[i]
            return NormalizedText(
                normSb.toString(), normToOrig.toIntArray(), boundaries
            )
        }

        private fun foldCaseAndDigits(
            text: String,
            caseInsensitive: Boolean
        ): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                val digit = normalizeIndicDigit(cp)
                val out = if (caseInsensitive) {
                    Character.toLowerCase(digit)
                } else digit
                sb.appendCodePoint(out)
            }
            return sb.toString()
        }

        /**
         * تطبيع مفتاح للمطابقة/الهوية: مسار موحّد (حساس للتشكيل، NFC، بلا تطويل).
         */
        fun normalizeKey(
            key: String,
            caseInsensitive: Boolean = true
        ): String = normalizeForMatch(key, caseInsensitive).text

        /**
         * هل للمفتاح (بعد إزالة التطويل فقط) محتوى أساسي غير حركات/تركيب؟
         * يُستخدم لرفض المفاتيح المكوّنة من حركات/تطويل فقط.
         */
        internal fun hasBaseContent(key: String): Boolean {
            var i = 0
            while (i < key.length) {
                val cp = key.codePointAt(i)
                i += Character.charCount(cp)
                if (isTatweel(cp)) continue
                if (isCombiningMark(cp)) continue
                return true
            }
            return false
        }

        private fun normalizeIndicDigit(cp: Int): Int = when (cp) {
            in 0x0660..0x0669 -> '0'.code + (cp - 0x0660)
            in 0x06F0..0x06F9 -> '0'.code + (cp - 0x06F0)
            else -> cp
        }
    }

    @Volatile
    private var matchModes: Map<String, String> = emptyMap()

    @Volatile
    private var valueLangs: Map<String, String> = emptyMap()

    open fun isPersistent(): Boolean = true

    val storageMode: String = "JSON_V2"

    private val dictFile = File(context.filesDir, DICT_FILE_NAME)
    private val tmpFile = File(context.filesDir, DICT_TMP_FILE_NAME)
    private val legacyBackupFile =
        File(context.filesDir, LEGACY_BACKUP_FILE_NAME)

    @Volatile
    private var lastFileModified = 0L

    @Volatile
    private var lastFileLength = 0L

    private var version = 0L

    private data class MachineCache(
        val version: Long,
        val standaloneMaxLength: Int,
        val caseInsensitive: Boolean,
        val machine: UnifiedAhoCorasick
    )

    @Volatile
    private var cache: MachineCache? = null

    @Volatile
    var standaloneMaxLength: Int = DEFAULT_STANDALONE_MAX_LENGTH
        set(value) {
            field = value.coerceIn(
                MIN_STANDALONE_LENGTH, MAX_STANDALONE_LENGTH
            )
        }

    @Volatile
    var testCaseInsensitive: Boolean = true

    private var _entries: Map<String, String> = emptyMap()
    val entries: Map<String, String>
        get() = _entries

    @Volatile
    var langEntries: Map<String, Map<String, String>> = emptyMap()
        private set

    private fun swapEntries(next: Map<String, String>) { _entries = next }

    private fun swapLangEntries(
        lang: String,
        next: Map<String, String>
    ) {
        val base = LinkedHashMap(langEntries)
        if (next.isEmpty()) base.remove(lang) else base[lang] = next
        langEntries = base
    }

    private val typeToken: Type = object :
        com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type

    init {
        if (!loadFromFile()) {
            val prefs = openPrefs()
            migrateFromPrefs(prefs)
        }
    }

    open fun reloadIfChanged(): Boolean {
        if (!dictFile.exists()) return false
        val lm = dictFile.lastModified()
        val len = dictFile.length()
        if (lm == lastFileModified && len == lastFileLength) return false
        return loadFromFile()
    }

    fun reload() {
        synchronized(this) {
            if (!loadFromFile()) {
                lastFileModified = -1L
                lastFileLength = -1L
                cache = null
                version++
            }
        }
    }

    open fun invalidate() {
        synchronized(this) {
            lastFileModified = -1L
            lastFileLength = -1L
            cache = null
        }
    }

    private fun openPrefs(): android.content.SharedPreferences {
        return try {
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
        } catch (e: Exception) {
            android.util.Log.w(
                LOG_TAG, "فشل التخزين المشفّر، استخدام العادي", e
            )
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    private fun migrateFromPrefs(
        prefs: android.content.SharedPreferences
    ) {
        if (dictFile.exists()) return
        val json = prefs.getString(KEY_DICT, null)
        if (json.isNullOrBlank() || json == "{}") return

        try {
            if (!legacyBackupFile.exists()) {
                legacyBackupFile.writeText(json, Charsets.UTF_8)
            }

            val loaded = loadFromPrefs(prefs)
            val ver = prefs.getLong(KEY_MODIFIED, 0L).let {
                if (it == 0L) 1L else it
            }
            version = ver
            swapEntries(loaded.global)
            langEntries = loaded.scopes
            matchModes = loaded.modes
            valueLangs = loaded.vLangs

            val jsonStr = serializeToJson(
                ver, loaded.global, loaded.scopes,
                loaded.modes, loaded.vLangs
            )
            FileOutputStream(tmpFile).use { fos ->
                fos.write(jsonStr.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (tmpFile.renameTo(dictFile)) {
                lastFileModified = dictFile.lastModified()
                lastFileLength = dictFile.length()
            }
        } catch (e: Exception) {
            android.util.Log.e(LOG_TAG, "فشل ترحيل قاموس النطق", e)
        }
    }

    private val caseInsensitive: Boolean
        get() = testCaseInsensitive

    open fun apply(text: String): String = apply(text, null, null)

    open fun apply(text: String, languageTag: String? = null): String =
        apply(text, languageTag, null)

    open fun apply(
        text: String,
        languageTag: String?,
        precomputedSegments: List<SegmentWithOffset>?
    ): String {
        if (text.isEmpty()) return text
        synchronized(this) { reloadIfChanged() }

        val held = synchronized(this) {
            val cached = cache
            if (cached != null && cached.version == version &&
                cached.standaloneMaxLength == standaloneMaxLength &&
                cached.caseInsensitive == caseInsensitive
            ) {
                cached
            } else {
                val outputs = buildAllOutputs(caseInsensitive)
                val machine = UnifiedAhoCorasick(outputs, caseInsensitive)
                MachineCache(
                    version, standaloneMaxLength, caseInsensitive, machine
                ).also { cache = it }
            }
        }

        val segments = when {
            precomputedSegments != null -> precomputedSegments
            !languageTag.isNullOrBlank() -> listOf(
                SegmentWithOffset(
                    text = text,
                    languageTag = languageTag.trim(),
                    role = SpeechRole.TEXT,
                    start = 0,
                    end = text.length
                )
            )
            else -> LanguageSegmenter().segmentWithOffsets(text)
        }
        return held.machine.apply(text, segments, standaloneMaxLength)
    }

    fun traceMatches(
        text: String,
        languageTag: String? = null
    ): List<DictTraceEntry> {
        if (text.isEmpty()) return emptyList()
        synchronized(this) { reloadIfChanged() }

        val held = synchronized(this) {
            val cached = cache
            if (cached != null && cached.version == version &&
                cached.standaloneMaxLength == standaloneMaxLength &&
                cached.caseInsensitive == caseInsensitive
            ) {
                cached
            } else {
                val outputs = buildAllOutputs(caseInsensitive)
                val machine = UnifiedAhoCorasick(outputs, caseInsensitive)
                MachineCache(
                    version, standaloneMaxLength, caseInsensitive, machine
                ).also { cache = it }
            }
        }

        val segments = if (!languageTag.isNullOrBlank()) {
            listOf(
                SegmentWithOffset(
                    text = text,
                    languageTag = languageTag.trim(),
                    role = SpeechRole.TEXT,
                    start = 0,
                    end = text.length
                )
            )
        } else {
            LanguageSegmenter().segmentWithOffsets(text)
        }
        return held.machine.trace(text, segments, standaloneMaxLength)
    }

    private fun buildAllOutputs(caseInsensitive: Boolean): List<DictOutput> {
        val list = ArrayList<DictOutput>()
        for ((key, value) in entries) {
            val normKey = normalizeKey(key, caseInsensitive)
            if (normKey.isEmpty()) continue
            val mode = matchModes["|$key"] ?: "AUTO"
            val vLang = valueLangs["|$key"]
            list.add(DictOutput(normKey, key, value, null, mode, vLang))
        }
        for ((scopeTag, map) in langEntries) {
            for ((key, value) in map) {
                val normKey = normalizeKey(key, caseInsensitive)
                if (normKey.isEmpty()) continue
                val mode = matchModes["$scopeTag|$key"] ?: "AUTO"
                val vLang = valueLangs["$scopeTag|$key"]
                list.add(
                    DictOutput(normKey, key, value, scopeTag, mode, vLang)
                )
            }
        }
        return list
    }



    private fun normalizeLangTag(languageTag: String?): String? =
        languageTag?.trim()?.takeIf { it.isNotEmpty() }

    private fun modeKeyOf(languageTag: String?, key: String): String =
        "${normalizeLangTag(languageTag).orEmpty()}|${key.trim()}"

    /** إيجاد مفتاح مخزّن مكافئ قانونياً (هوية مطابقة موحّدة) في نفس النطاق. */
    private fun findCanonicallyEquivalentStoredKey(
        scopeTag: String?,
        normalized: String
    ): String? {
        val map = if (scopeTag == null) entries
        else (langEntries[scopeTag] ?: emptyMap())
        for (existing in map.keys) {
            if (normalizeKey(existing, true) == normalized) return existing
        }
        return null
    }

    fun addEntry(abbreviation: String, pronunciation: String): Boolean =
        addEntry(abbreviation, pronunciation, null, null, null)

    fun addEntry(
        abbreviation: String,
        pronunciation: String,
        languageTag: String?
    ): Boolean = addEntry(abbreviation, pronunciation, languageTag, null, null)

    fun addEntry(
        abbreviation: String,
        pronunciation: String,
        languageTag: String?,
        matchMode: String? = null,
        valueLang: String? = null
    ): Boolean {
        if (abbreviation.isBlank() || pronunciation.isBlank()) return false
        val key = abbreviation.trim()
        if (!hasBaseContent(key)) return false
        val normKey = normalizeKey(key, true)
        if (normKey.isEmpty()) return false
        val value = pronunciation.trim()
        if (key.length > MAX_KEY_LENGTH || value.length > MAX_VALUE_LENGTH) {
            return false
        }
        val lang = normalizeLangTag(languageTag)

        synchronized(this) {
            // منع الالتباس: مفتاح مكافئ قانونياً في نفس النطاق = نفس الهوية
            val equivalent = findCanonicallyEquivalentStoredKey(lang, normKey)

            if (lang == null) {
                val updated = LinkedHashMap(entries)
                if (equivalent != null && equivalent != key) {
                    updated.remove(equivalent)
                    migrateMetaKey(
                        modeKeyOf(null, equivalent), modeKeyOf(null, key)
                    )
                }
                updated[key] = value
                swapEntries(updated)
            } else {
                val overlay = LinkedHashMap(langEntries[lang] ?: emptyMap())
                if (equivalent != null && equivalent != key) {
                    overlay.remove(equivalent)
                    migrateMetaKey(
                        modeKeyOf(lang, equivalent), modeKeyOf(lang, key)
                    )
                }
                overlay[key] = value
                swapLangEntries(lang, overlay)
            }

            val finalModeKey = modeKeyOf(lang, key)
            val modes = LinkedHashMap(matchModes)
            if (!matchMode.isNullOrBlank() && matchMode != "AUTO") {
                modes[finalModeKey] = matchMode.uppercase()
            } else {
                modes.remove(finalModeKey)
            }
            matchModes = modes

            val vLangs = LinkedHashMap(valueLangs)
            if (!valueLang.isNullOrBlank()) {
                vLangs[finalModeKey] = valueLang.trim()
            } else {
                vLangs.remove(finalModeKey)
            }
            valueLangs = vLangs

            cache = null
            version++
        }
        return save()
    }

    private fun migrateMetaKey(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val modes = LinkedHashMap(matchModes)
        val m = modes.remove(oldKey)
        if (m != null) modes[newKey] = m
        matchModes = modes
        val vLangs = LinkedHashMap(valueLangs)
        val v = vLangs.remove(oldKey)
        if (v != null) vLangs[newKey] = v
        valueLangs = vLangs
    }

    fun removeEntry(
        abbreviation: String,
        languageTag: String? = null
    ): Boolean {
        val lang = normalizeLangTag(languageTag)
        val trimmed = abbreviation.trim()
        var changed = false
        synchronized(this) {
            if (lang == null) {
                val updated = LinkedHashMap(entries)
                changed = removeByIdentity(updated, trimmed)
                if (changed) swapEntries(updated)
            } else {
                val overlay = LinkedHashMap(langEntries[lang] ?: emptyMap())
                changed = removeByIdentity(overlay, trimmed)
                if (changed) swapLangEntries(lang, overlay)
            }
            if (changed) {
                cleanupMetaForRemoved(lang, trimmed)
                cache = null
                version++
            }
        }
        return if (changed) save() else false
    }

    private fun removeByIdentity(
        map: MutableMap<String, String>,
        key: String
    ): Boolean {
        if (map.remove(key) != null) return true
        val norm = normalizeKey(key, true)
        val it = map.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (normalizeKey(k, true) == norm) {
                it.remove()
                return true
            }
        }
        return false
    }

    private fun cleanupMetaForRemoved(lang: String?, removedKey: String) {
        val norm = runCatching { normalizeKey(removedKey, true) }.getOrNull()
        val modes = LinkedHashMap(matchModes)
        val vLangs = LinkedHashMap(valueLangs)
        val prefix = "${lang.orEmpty()}|"
        fun shouldDrop(metaKey: String): Boolean {
            if (!metaKey.startsWith(prefix)) return false
            val k = metaKey.removePrefix(prefix)
            if (k == removedKey) return true
            return norm != null && normalizeKey(k, true) == norm
        }
        modes.keys.toList().forEach { if (shouldDrop(it)) modes.remove(it) }
        vLangs.keys.toList().forEach { if (shouldDrop(it)) vLangs.remove(it) }
        matchModes = modes
        valueLangs = vLangs
    }

    fun getMatchMode(
        abbreviation: String,
        languageTag: String? = null
    ): String {
        val lang = normalizeLangTag(languageTag)
        val modeKey = modeKeyOf(lang, abbreviation)
        matchModes[modeKey]?.let { return it }
        val norm = normalizeKey(abbreviation.trim(), true)
        val eq = findCanonicallyEquivalentStoredKey(lang, norm) ?: return "AUTO"
        return matchModes[modeKeyOf(lang, eq)] ?: "AUTO"
    }

    fun getValueLang(
        abbreviation: String,
        languageTag: String? = null
    ): String? {
        val lang = normalizeLangTag(languageTag)
        val modeKey = modeKeyOf(lang, abbreviation)
        valueLangs[modeKey]?.let { return it }
        val norm = normalizeKey(abbreviation.trim(), true)
        val eq = findCanonicallyEquivalentStoredKey(lang, norm) ?: return null
        return valueLangs[modeKeyOf(lang, eq)]
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

    fun clearAll() = clear()

    fun getAllEntries(languageTag: String? = null): Map<String, String> {
        val lang = normalizeLangTag(languageTag)
        return if (lang == null) entries.toMap()
        else (langEntries[lang] ?: emptyMap()).toMap()
    }

    fun findEntryScope(key: String): String? {
        val trimmed = key.trim()
        val norm = normalizeKey(trimmed, true)
        if (entries.keys.any {
                it == trimmed || normalizeKey(it, true) == norm
            }) {
            return null
        }
        for ((tag, map) in langEntries) {
            if (map.keys.any {
                    it == trimmed || normalizeKey(it, true) == norm
                }) {
                return tag
            }
        }
        return null
    }

    fun hasEntry(abbreviation: String, languageTag: String? = null): Boolean {
        val lang = normalizeLangTag(languageTag)
        val norm = normalizeKey(abbreviation.trim(), true)
        val map = if (lang == null) entries
        else (langEntries[lang] ?: emptyMap())
        return map.keys.any { normalizeKey(it, true) == norm }
    }

    fun importFromJson(
        json: String,
        merge: Boolean = false,
        languageTag: String? = null
    ): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        val obj = NateqJson.parseObject(json) ?: return false
        if (obj.size() == 0) return false

        val lang = normalizeLangTag(languageTag)
        val valid = LinkedHashMap<String, String>()
        val seenNorms = HashSet<String>()
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
            // رفض المفتاح المكوّن من حركات/تطويل فقط (سياسة واضحة)
            if (!hasBaseContent(key)) return false
            val norm = normalizeKey(key, true)
            if (norm.isEmpty()) return false
            // منع الالتباس: تكرار مكافئ قانونياً داخل الدفعة نفسها
            if (!seenNorms.add(norm)) return false
            valid[key] = value
        }
        if (valid.isEmpty()) return false

        val imported = synchronized(this) {
            val base = if (lang == null) entries
            else (langEntries[lang] ?: emptyMap())
            val updated = if (merge) LinkedHashMap(base)
            else LinkedHashMap<String, String>()

            // عند الدمج: إزالة أي مفاتيح قائمة مكافئة قانونياً لما سيُستورد
            // (تحديث هوية مكافئة، بلا حذف بيانات قديمة أخرى بصمت)
            if (merge) {
                val toRemove = ArrayList<String>()
                for (existing in updated.keys) {
                    val eNorm = normalizeKey(existing, true)
                    if (seenNorms.contains(eNorm)) toRemove.add(existing)
                }
                for (rk in toRemove) {
                    val oldModeKey = modeKeyOf(lang, rk)
                    // أزل المفتاح القديم؛ ستُضاف الإعدادات مع المفتاح الجديد أدناه
                    updated.remove(rk)
                    // إزالة وصف قديم إذا تغيّر المفتاح النصي
                    val modes = LinkedHashMap(matchModes)
                    val vLangs = LinkedHashMap(valueLangs)
                    modes.remove(oldModeKey)
                    vLangs.remove(oldModeKey)
                    matchModes = modes
                    valueLangs = vLangs
                }
            }

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
                cache = null
                version++
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
                swapEntries(gMap)
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
        val globalJson = try { sp.getString("dictionary", "{}") }
        catch (_: Throwable) { "{}" }
        val scopesJson = try { sp.getString(KEY_SCOPES, "{}") }
        catch (_: Throwable) { "{}" }
        val modesJson = try { sp.getString(KEY_MATCH_MODES, "{}") }
        catch (_: Throwable) { "{}" }
        val vLangsJson = try { sp.getString(KEY_VALUE_LANGS, "{}") }
        catch (_: Throwable) { "{}" }

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
            android.util.Log.e(LOG_TAG, "فشل حفظ قاموس النطق", e)
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

/** نص مُطَبَّع للمطابقة مع خرائط المواضع وحدود العناقيد الحرفية. */
internal data class NormalizedText(
    val text: String,
    val normToOrig: IntArray,
    /** حدود العناقيد: boundaries[i]==true تعني أن i بداية عنقود أو نهاية النص. */
    val boundaries: BooleanArray
) {
    fun isBoundary(index: Int): Boolean =
        index >= 0 && index < boundaries.size && boundaries[index]

    fun origEndForNorm(normEnd: Int, originalLength: Int): Int =
        if (normEnd < normToOrig.size) normToOrig[normEnd] else originalLength
}

/** نتيجة فحص مطابقة واحدة في تتبع القاموس. */
data class DictTraceEntry(
    val key: String,
    val value: String,
    val matchMode: String,
    val accepted: Boolean,
    val reason: String,
    val origStart: Int,
    val origEnd: Int
)

/** آلة Aho-Corasick موحدة (حساسة للتشكيل مع تكافؤ قانوني وتجاهل التطويل). */
private class UnifiedAhoCorasick(
    private val allEntries: List<DictOutput>,
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
        for (entry in allEntries) {
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
        val norm = PronunciationDictionary.normalizeForMatch(
            text, caseInsensitive
        )
        if (norm.text.isEmpty()) return text

        val candidates = collectCandidates(
            text, norm, segments, standaloneMaxLength
        )
        val selected = resolveOverlaps(candidates)
        if (selected.isEmpty()) return text

        val result = StringBuilder(text.length)
        var cursor = 0
        for (cand in selected) {
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
        val norm = PronunciationDictionary.normalizeForMatch(
            text, caseInsensitive
        )
        if (norm.text.isEmpty()) return emptyList()

        val prelim = ArrayList<Pair<DictTraceEntry, MatchCandidate?>>()
        collectCandidatesWithTrace(
            text, norm, segments, standaloneMaxLength, prelim
        )
        val allAccepted = ArrayList<MatchCandidate>()
        for ((_, cand) in prelim) if (cand != null) allAccepted.add(cand)
        val finalSelected = resolveOverlaps(allAccepted)
        val finalIds = IdentityHashSet<MatchCandidate>()
        for (c in finalSelected) finalIds.add(c)

        val finalTrace = ArrayList<DictTraceEntry>(prelim.size)
        val acceptedKeys = HashSet<String>()
        for ((entry, cand) in prelim) {
            if (cand == null) {
                finalTrace.add(entry)
            } else if (finalIds.contains(cand)) {
                acceptedKeys.add(entry.key + "\u0000" + entry.origStart)
                finalTrace.add(entry)
            } else {
                finalTrace.add(
                    entry.copy(
                        accepted = false,
                        reason = "مرفوض: أزاحه تطابقٌ آخر (أطول/أسبق) حُسم نهائياً"
                    )
                )
            }
        }
        appendDiacriticNearMisses(text, norm, segments, finalTrace)
        return finalTrace
    }

    private fun appendDiacriticNearMisses(
        text: String,
        norm: NormalizedText,
        segments: List<SegmentWithOffset>,
        trace: ArrayList<DictTraceEntry>
    ) {
        if (allEntries.isEmpty()) return
        val baseText = stripMarks(norm.text)
        if (baseText.isEmpty()) return
        val reported = HashSet<String>()
        // لا نكرر لمواضع/مفاتيح مذكورة بالفعل
        for (e in trace) {
            reported.add(e.key + "\u0000" + e.origStart + "\u0000" + e.origEnd)
        }
        for (entry in allEntries) {
            val regionOk = true // النطاق يُقيّم لاحقاً لكل موضع
            val baseKey = stripMarks(entry.normalizedKey)
            if (baseKey.isEmpty()) continue
            var searchFrom = 0
            while (searchFrom <= baseText.length - baseKey.length) {
                val pos = baseText.indexOf(baseKey, searchFrom)
                if (pos < 0) break
                val end = pos + baseKey.length
                // مواضع base تقابل مواضع norm 1:1 لأننا أزلنا العلامات من نفس السلسلة
                // نحتاج مواضع norm الحقيقية: ابحث عن نافذة norm تُعطي نفس base
                val normRange = findNormRangeForBase(norm, baseKey, pos)
                searchFrom = pos + 1
                if (normRange == null) continue
                val (ns, ne) = normRange
                if (!norm.isBoundary(ns) || !norm.isBoundary(ne)) continue
                if (ns < 0 || ne > norm.normToOrig.size && ne != norm.text.length) {
                    continue
                }
                val origStart = if (ns < norm.normToOrig.size)
                    norm.normToOrig[ns] else continue
                val origEnd = norm.origEndForNorm(ne, text.length)
                val markId = entry.originalKey + "\u0000" +
                    origStart + "\u0000" + origEnd
                if (reported.contains(markId)) continue
                val regionLang = findRegionLanguage(segments, origStart)
                if (!isScopeMatching(entry.scope, regionLang)) continue
                // تطابق تامّ حسّاس للتشكيل موجود؟ لا نُبلغ
                val exact = norm.text.substring(ns, ne)
                if (exact == entry.normalizedKey) continue
                // تطابق الحروف الأساس مع اختلاف التشكيل
                reported.add(markId)
                val matchedText = text.substring(origStart, origEnd)
                trace.add(
                    DictTraceEntry(
                        key = entry.originalKey,
                        value = entry.value,
                        matchMode = entry.matchMode,
                        accepted = false,
                        reason = "مرفوض: الحروف الأساس متطابقة («$matchedText») " +
                            "لكن التشكيل مختلف عن مفتاح القاموس — " +
                            "المطابقة حساسة للتشكيل",
                        origStart = origStart,
                        origEnd = origEnd
                    )
                )
            }
        }
    }

    private fun stripMarks(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (PronunciationDictionary.isCombiningMark(cp)) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    private fun findNormRangeForBase(
        norm: NormalizedText,
        baseKey: String,
        basePos: Int
    ): Pair<Int, Int>? {
        val normText = norm.text
        // حوّل موضع basePos في النص المجرد من العلامات إلى نطاق في normText
        var baseIdx = 0
        var normStart = -1
        var i = 0
        while (i < normText.length) {
            if (baseIdx == basePos) {
                normStart = i
                break
            }
            val cp = normText.codePointAt(i)
            val w = Character.charCount(cp)
            if (!PronunciationDictionary.isCombiningMark(cp)) baseIdx++
            i += w
        }
        if (baseIdx == basePos && normStart == -1) {
            normStart = normText.length
        }
        if (normStart < 0) return null
        var j = normStart
        var matched = 0
        while (j < normText.length && matched < baseKey.length) {
            val cp = normText.codePointAt(j)
            val w = Character.charCount(cp)
            if (!PronunciationDictionary.isCombiningMark(cp)) matched++
            j += w
        }
        if (matched != baseKey.length) return null
        // امتد لالتقاط العلامات التركيبية التابعة لآخر محرف أساس داخل
        // العنقود نفسه (مثل التنوين/الشدة)، حتى حد العنقود التالي.
        var end = j
        while (end < normText.length && !norm.isBoundary(end)) {
            val cp = normText.codePointAt(end)
            val w = Character.charCount(cp)
            if (!PronunciationDictionary.isCombiningMark(cp)) break
            end += w
        }
        return normStart to end
    }

    private class IdentityHashSet<T> {
        private val map = java.util.IdentityHashMap<T, Boolean>()
        fun add(v: T) { map[v] = true }
        fun contains(v: T): Boolean = map.containsKey(v)
    }

    private fun collectCandidates(
        text: String,
        norm: NormalizedText,
        segments: List<SegmentWithOffset>,
        standaloneMaxLength: Int
    ): ArrayList<MatchCandidate> {
        val out = ArrayList<MatchCandidate>()
        collectInternal(text, norm, segments, standaloneMaxLength) { _, cand ->
            if (cand != null) out.add(cand)
        }
        return out
    }

    private fun collectCandidatesWithTrace(
        text: String,
        norm: NormalizedText,
        segments: List<SegmentWithOffset>,
        standaloneMaxLength: Int,
        sink: ArrayList<Pair<DictTraceEntry, MatchCandidate?>>
    ) {
        collectInternal(text, norm, segments, standaloneMaxLength) { entry, cand ->
            sink.add(entry to cand)
        }
    }

    private fun collectInternal(
        text: String,
        norm: NormalizedText,
        segments: List<SegmentWithOffset>,
        standaloneMaxLength: Int,
        consumer: (DictTraceEntry, MatchCandidate?) -> Unit
    ) {
        val normText = norm.text
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

                // حدود العناقيد الحرفية إلزامية (بداية/نهاية عنقود):
                // يمنع البدء داخل عنقود مركب أو الانتهاء قبل علامة تابعة.
                if (!norm.isBoundary(normStart) || !norm.isBoundary(normEnd)) {
                    val os = if (normStart < norm.normToOrig.size)
                        norm.normToOrig[normStart] else text.length
                    val oe = norm.origEndForNorm(normEnd, text.length)
                    consumer(
                        DictTraceEntry(
                            out.originalKey, out.value, out.matchMode, false,
                            "مرفوض: التطابق لا يلتزم بحدود العناقيد الحرفية" +
                                " (تشكيل/علامة تابعة خارج المطابقة)",
                            os, oe
                        ), null
                    )
                    continue
                }

                val origStart = norm.normToOrig[normStart]
                val origEnd = norm.origEndForNorm(normEnd, text.length)

                val regionLang = findRegionLanguage(segments, origStart)
                if (!isScopeMatching(out.scope, regionLang)) {
                    consumer(
                        DictTraceEntry(
                            out.originalKey, out.value, out.matchMode, false,
                            "مرفوض: نطاق اللغة لا يطابق (${out.scope})",
                            origStart, origEnd
                        ), null
                    )
                    continue
                }

                val (accepted, reason) = evaluateMatch(
                    out, text, origStart, origEnd,
                    normText, normStart, normEnd, standaloneMaxLength
                )
                if (!accepted) {
                    consumer(
                        DictTraceEntry(
                            out.originalKey, out.value, out.matchMode,
                            false, reason, origStart, origEnd
                        ), null
                    )
                    continue
                }

                val replacement = if (!out.valueLang.isNullOrBlank()) {
                    "${LanguageSegmenter.LOCK_START}${out.valueLang}" +
                        "${LanguageSegmenter.LOCK_TAG_END}${out.value}" +
                        LanguageSegmenter.LOCK_END
                } else out.value

                val cand = MatchCandidate(
                    origStart, origEnd, out, replacement
                )
                consumer(
                    DictTraceEntry(
                        out.originalKey, out.value, out.matchMode,
                        true, reason, origStart, origEnd
                    ), cand
                )
            }
        }
    }

    private fun resolveOverlaps(
        candidates: ArrayList<MatchCandidate>
    ): List<MatchCandidate> {
        if (candidates.isEmpty()) return emptyList()
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
        val selected = ArrayList<MatchCandidate>(candidates.size)
        var cursor = 0
        for (cand in candidates) {
            if (cand.origStart < cursor) continue
            selected.add(cand)
            cursor = cand.origEnd
        }
        return selected
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

    private fun isScopeMatching(scope: String?, regionLang: String): Boolean {
        if (scope == null) return true
        if (scope.equals(regionLang, ignoreCase = true)) return true
        if (!scope.contains('-') && !scope.contains('_')) {
            return LanguageCode.isSameFamily(scope, regionLang)
        }
        return false
    }

    private val PROTECTED_HIJRI_WORDS = listOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "ربيع الاول",
        "ربيع الاخر", "جمادى الأولى", "جمادى الآخرة", "جمادى الاولى",
        "جمادى الاخرة", "جمادى", "رجب", "شعبان", "رمضان", "شوال",
        "ذو القعدة", "ذو الحجة"
    )

    private fun isInsideProtectedWord(
        normText: String, normStart: Int, normEnd: Int
    ): Boolean {
        for (word in PROTECTED_HIJRI_WORDS) {
            var idx = 0
            while (true) {
                val pos = normText.indexOf(word, idx)
                if (pos == -1) break
                val wordEnd = pos + word.length
                if (normStart >= pos && normEnd <= wordEnd) return true
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
        if (mode == "ANYWHERE") {
            return true to "مقبول: وضع أي موضع (ANYWHERE) — المطابقة حساسة للتشكيل"
        }

        if (out.normalizedKey.length <= 2 &&
            isInsideProtectedWord(normText, normStart, normEnd)
        ) {
            return false to "مرفوض: يقع داخل كلمة شهر هجري محمية"
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
            return true to "مقبول: لا تتطلب كلمة مستقلة (حساسة للتشكيل)"
        }

        if (hasLetterNeighborBefore(text, origStart)) {
            return false to
                "مرفوض: حرف جارٍ قبله (حدود كلمة) — المطابقة حساسة للتشكيل"
        }
        if (hasLetterNeighborAfter(text, origEnd)) {
            return false to
                "مرفوض: حرف جارٍ بعده (حدود كلمة) — المطابقة حساسة للتشكيل"
        }

        return true to "مقبول: كلمة مستقلة بلا حروف جارة (حساسة للتشكيل)"
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
        cp == '\''.code || cp == '\u2019'.code || cp == '-'.code || cp == '_'.code
}
