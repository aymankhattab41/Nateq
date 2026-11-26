package com.aymankhattab.nateq.engine

import android.content.Context
import android.os.Build
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
import java.io.RandomAccessFile
import java.lang.reflect.Type
import java.nio.channels.FileLock
import java.text.BreakIterator
import java.text.Normalizer
import java.util.UUID

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
         * تطبيع مفتاح للمطابقة/الهوية: مسار موحّد
         * (حساس للتشكيل، NFC، بلا تطويل).
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
    private val backupFile = File(context.filesDir, "dictionary_v2.json.bak")
    private val lockFile = File(context.filesDir, "dictionary.lock")
    private val storageLock = Any()
    private val legacyBackupFile =
        File(context.filesDir, LEGACY_BACKUP_FILE_NAME)

    private data class CandidateState(
        val entries: Map<String, String>,
        val scopes: Map<String, Map<String, String>>,
        val modes: Map<String, String>,
        val vLangs: Map<String, String>
    )

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

    /**
     * يبني مصفوفة مطابقة القاموس (Aho-Corasick) مسبقاً في خيط خلفي حتى لا
     * تدفعها أول جملة بعد الإقلاع أو بعد تعديل القاموس. يعيد الاستخدام إن
     * كان الكاش صالحاً؛ ويُعاد البناء فقط عند تغيّر الإصدار/الحالة.
     */
    open fun prebuildMatcher() {
        synchronized(this) {
            reloadIfChanged()
            val cached = cache
            if (cached != null && cached.version == version &&
                cached.standaloneMaxLength == standaloneMaxLength &&
                cached.caseInsensitive == caseInsensitive
            ) {
                return
            }
            val outputs = buildAllOutputs(caseInsensitive)
            val machine = UnifiedAhoCorasick(outputs, caseInsensitive)
            cache = MachineCache(
                version, standaloneMaxLength, caseInsensitive, machine
            )
        }
    }

    @androidx.annotation.VisibleForTesting
    fun isMatcherBuiltForTesting(): Boolean = synchronized(this) {
        val cached = cache
        cached != null && cached.version == version &&
            cached.standaloneMaxLength == standaloneMaxLength &&
            cached.caseInsensitive == caseInsensitive
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
            commitTransaction(
                loaded.global,
                loaded.scopes,
                loaded.modes,
                loaded.vLangs
            )
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

        val candidate = synchronized(this) {
            val nextEntries = LinkedHashMap(entries)
            val nextScopes = LinkedHashMap(langEntries)
            val nextModes = LinkedHashMap(matchModes)
            val nextVLangs = LinkedHashMap(valueLangs)

            val equivalent = findCanonicallyEquivalentStoredKey(lang, normKey)

            if (lang == null) {
                if (equivalent != null && equivalent != key) {
                    nextEntries.remove(equivalent)
                    val oldM = nextModes.remove(modeKeyOf(null, equivalent))
                    if (oldM != null) {
                        nextModes[modeKeyOf(null, key)] = oldM
                    }
                    val oldV = nextVLangs.remove(modeKeyOf(null, equivalent))
                    if (oldV != null) {
                        nextVLangs[modeKeyOf(null, key)] = oldV
                    }
                }
                nextEntries[key] = value
            } else {
                val overlay = LinkedHashMap(nextScopes[lang] ?: emptyMap())
                if (equivalent != null && equivalent != key) {
                    overlay.remove(equivalent)
                    val oldM = nextModes.remove(modeKeyOf(lang, equivalent))
                    if (oldM != null) {
                        nextModes[modeKeyOf(lang, key)] = oldM
                    }
                    val oldV = nextVLangs.remove(modeKeyOf(lang, equivalent))
                    if (oldV != null) {
                        nextVLangs[modeKeyOf(lang, key)] = oldV
                    }
                }
                overlay[key] = value
                nextScopes[lang] = overlay
            }

            val finalModeKey = modeKeyOf(lang, key)
            if (!matchMode.isNullOrBlank() && matchMode != "AUTO") {
                nextModes[finalModeKey] = matchMode.uppercase()
            } else {
                nextModes.remove(finalModeKey)
            }

            if (!valueLang.isNullOrBlank()) {
                nextVLangs[finalModeKey] = valueLang.trim()
            } else {
                nextVLangs.remove(finalModeKey)
            }

            CandidateState(nextEntries, nextScopes, nextModes, nextVLangs)
        }
        return commitTransaction(
            candidate.entries,
            candidate.scopes,
            candidate.modes,
            candidate.vLangs
        )
    }

    fun removeEntry(
        abbreviation: String,
        languageTag: String? = null
    ): Boolean {
        val lang = normalizeLangTag(languageTag)
        val trimmed = abbreviation.trim()
        val candidate = synchronized(this) {
            val nextEntries = LinkedHashMap(entries)
            val nextScopes = LinkedHashMap(langEntries)
            val nextModes = LinkedHashMap(matchModes)
            val nextVLangs = LinkedHashMap(valueLangs)
            val changed: Boolean

            if (lang == null) {
                changed = removeByIdentity(nextEntries, trimmed)
            } else {
                val overlay = LinkedHashMap(nextScopes[lang] ?: emptyMap())
                changed = removeByIdentity(overlay, trimmed)
                if (changed) {
                    if (overlay.isEmpty()) nextScopes.remove(lang)
                    else nextScopes[lang] = overlay
                }
            }
            if (!changed) return false

            cleanupMetaInMaps(nextModes, nextVLangs, lang, trimmed)
            CandidateState(nextEntries, nextScopes, nextModes, nextVLangs)
        }
        return commitTransaction(
            candidate.entries,
            candidate.scopes,
            candidate.modes,
            candidate.vLangs
        )
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

    private fun cleanupMetaInMaps(
        modes: MutableMap<String, String>,
        vLangs: MutableMap<String, String>,
        lang: String?,
        removedKey: String
    ) {
        val norm = runCatching { normalizeKey(removedKey, true) }.getOrNull()
        val prefix = "${lang.orEmpty()}|"
        fun shouldDrop(metaKey: String): Boolean {
            if (!metaKey.startsWith(prefix)) return false
            val k = metaKey.removePrefix(prefix)
            if (k == removedKey) return true
            return norm != null && normalizeKey(k, true) == norm
        }
        modes.keys.toList().forEach { if (shouldDrop(it)) modes.remove(it) }
        vLangs.keys.toList().forEach { if (shouldDrop(it)) vLangs.remove(it) }
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
        commitTransaction(emptyMap(), emptyMap(), emptyMap(), emptyMap())
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
        val root = NateqJson.parseObject(json) ?: return false
        if (root.size() == 0) return false

        val format = root.get("format")?.asString
        val isScope = format == "nateq_dictionary_scope" ||
            (format == null && root.has("scope") && root.has("entries"))

        val isFull = format == "nateq_dictionary" ||
            (format == null && !isScope && root.has("entries") &&
                (root.has("scopes") || root.has("modes") ||
                    root.has("value_langs")))

        val candidate = synchronized(this) {
            val baseEntries = if (merge) LinkedHashMap(entries)
                else LinkedHashMap<String, String>()
            val baseScopes = if (merge) LinkedHashMap(langEntries)
                else LinkedHashMap<String, Map<String, String>>()
            val baseModes = if (merge) LinkedHashMap(matchModes)
                else LinkedHashMap<String, String>()
            val baseVLangs = if (merge) LinkedHashMap(valueLangs)
                else LinkedHashMap<String, String>()

            if (isFull) {
                val gObj = root.optObject("entries") ?: return@synchronized null
                val parsedGlobal = parseAndValidateEntries(gObj)
                    ?: return@synchronized null
                val parsedScopes = LinkedHashMap<String, Map<String, String>>()
                val sObj = root.optObject("scopes")
                if (sObj != null) {
                    for ((rawTag, el) in sObj.entrySet()) {
                        val tag = normalizeLangTag(rawTag) ?: continue
                        val subObj = el.optObject() ?: continue
                        val subParsed = parseAndValidateEntries(subObj)
                            ?: return@synchronized null
                        if (subParsed.isNotEmpty()) {
                            parsedScopes[tag] = subParsed
                        }
                    }
                }
                val parsedModes = parseAndValidateModes(root.optObject("modes"))
                    ?: return@synchronized null
                val parsedVLangs = parseAndValidateVLangs(
                    root.optObject("value_langs")
                ) ?: return@synchronized null

                val totalCount = parsedGlobal.size +
                    parsedScopes.values.sumOf { it.size }
                val currentTotal = if (merge) {
                    baseEntries.size + baseScopes.values.sumOf { it.size }
                } else 0
                if (currentTotal + totalCount > MAX_IMPORT_ENTRIES) {
                    return@synchronized null
                }

                if (merge) {
                    mergeEntriesInto(
                        baseEntries, parsedGlobal, null, baseModes, baseVLangs
                    )
                    for ((tag, sMap) in parsedScopes) {
                        val target = LinkedHashMap(
                            baseScopes[tag] ?: emptyMap()
                        )
                        mergeEntriesInto(
                            target, sMap, tag, baseModes, baseVLangs
                        )
                        baseScopes[tag] = target
                    }
                    baseModes.putAll(parsedModes)
                    baseVLangs.putAll(parsedVLangs)
                } else {
                    baseEntries.putAll(parsedGlobal)
                    baseScopes.putAll(parsedScopes)
                    baseModes.putAll(parsedModes)
                    baseVLangs.putAll(parsedVLangs)
                }
                CandidateState(baseEntries, baseScopes, baseModes, baseVLangs)
            } else if (isScope) {
                val targetLang = root.get("scope")?.asString?.let {
                    normalizeLangTag(it)
                } ?: normalizeLangTag(languageTag) ?: return@synchronized null
                val eObj = root.optObject("entries") ?: return@synchronized null
                val parsed = parseAndValidateEntries(eObj)
                    ?: return@synchronized null
                val currentScopeTotal = if (merge) {
                    baseEntries.size + baseScopes.values.sumOf { it.size }
                } else 0
                if (currentScopeTotal + parsed.size > MAX_IMPORT_ENTRIES) {
                    return@synchronized null
                }

                val scopeModes = parseAndValidateModes(root.optObject("modes"))
                    ?: return@synchronized null
                val scopeVLangs = parseAndValidateVLangs(
                    root.optObject("value_langs")
                ) ?: return@synchronized null

                val targetScope = if (merge) {
                    LinkedHashMap(langEntries[targetLang] ?: emptyMap())
                } else {
                    LinkedHashMap<String, String>()
                }
                val prefix = "$targetLang|"
                if (!merge) {
                    baseEntries.putAll(entries)
                    baseScopes.putAll(langEntries)
                    baseModes.putAll(matchModes)
                    baseVLangs.putAll(valueLangs)
                    baseModes.keys.filter { it.startsWith(prefix) }
                        .forEach { baseModes.remove(it) }
                    baseVLangs.keys.filter { it.startsWith(prefix) }
                        .forEach { baseVLangs.remove(it) }
                }

                mergeEntriesInto(
                    targetScope,
                    parsed,
                    targetLang,
                    baseModes,
                    baseVLangs
                )
                baseScopes[targetLang] = targetScope
                for ((k, v) in scopeModes) {
                    val finalK = if (k.startsWith(prefix)) k else "$prefix$k"
                    baseModes[finalK] = v
                }
                for ((k, v) in scopeVLangs) {
                    val finalK = if (k.startsWith(prefix)) k else "$prefix$k"
                    baseVLangs[finalK] = v
                }
                CandidateState(baseEntries, baseScopes, baseModes, baseVLangs)
            } else {
                val parsed = parseAndValidateEntries(root)
                    ?: return@synchronized null
                val currentScopeTotal = if (merge) {
                    baseEntries.size + baseScopes.values.sumOf { it.size }
                } else 0
                if (currentScopeTotal + parsed.size > MAX_IMPORT_ENTRIES) {
                    return@synchronized null
                }
                val lang = normalizeLangTag(languageTag)

                if (!merge) {
                    baseEntries.putAll(entries)
                    baseScopes.putAll(langEntries)
                    baseModes.putAll(matchModes)
                    baseVLangs.putAll(valueLangs)
                    val prefix = "${lang.orEmpty()}|"
                    baseModes.keys.filter { it.startsWith(prefix) }
                        .forEach { baseModes.remove(it) }
                    baseVLangs.keys.filter { it.startsWith(prefix) }
                        .forEach { baseVLangs.remove(it) }
                }

                if (lang == null) {
                    val target = if (merge) baseEntries
                        else LinkedHashMap<String, String>()
                    mergeEntriesInto(
                        target, parsed, null, baseModes, baseVLangs
                    )
                    if (!merge) {
                        baseEntries.clear()
                        baseEntries.putAll(target)
                    }
                } else {
                    val target = if (merge) {
                        LinkedHashMap(baseScopes[lang] ?: emptyMap())
                    } else {
                        LinkedHashMap<String, String>()
                    }
                    mergeEntriesInto(
                        target, parsed, lang, baseModes, baseVLangs
                    )
                    baseScopes[lang] = target
                }
                CandidateState(baseEntries, baseScopes, baseModes, baseVLangs)
            }
        } ?: return false

        return commitTransaction(
            candidate.entries,
            candidate.scopes,
            candidate.modes,
            candidate.vLangs
        )
    }

    private fun mergeEntriesInto(
        target: MutableMap<String, String>,
        incoming: Map<String, String>,
        lang: String?,
        modes: MutableMap<String, String>,
        vLangs: MutableMap<String, String>
    ) {
        val incomingNorms = incoming.keys.map {
            normalizeKey(it, true)
        }.toSet()
        val toRemove = target.keys.filter {
            incomingNorms.contains(normalizeKey(it, true))
        }
        for (rk in toRemove) {
            target.remove(rk)
            val oldModeKey = modeKeyOf(lang, rk)
            modes.remove(oldModeKey)
            vLangs.remove(oldModeKey)
        }
        target.putAll(incoming)
    }

    private fun parseAndValidateEntries(
        obj: com.google.gson.JsonObject
    ): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        val seenNorms = HashSet<String>()
        for ((rawKey, el) in obj.entrySet()) {
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) return null
            val key = rawKey.trim()
            val value = el.asString.trim()
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return null
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) return null
            if (!hasBaseContent(key)) return null
            val norm = normalizeKey(key, true)
            if (norm.isEmpty() || !seenNorms.add(norm)) return null
            out[key] = value
        }
        return out
    }

    private fun parseAndValidateModes(
        obj: com.google.gson.JsonObject?
    ): Map<String, String>? {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, el) in obj.entrySet()) {
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) return null
            val mode = el.asString.trim().uppercase()
            if (mode == "WHOLE" || mode == "ANYWHERE" || mode == "AUTO") {
                out[k.trim()] = mode
            }
        }
        return out
    }

    private fun parseAndValidateVLangs(
        obj: com.google.gson.JsonObject?
    ): Map<String, String>? {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, el) in obj.entrySet()) {
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) return null
            val vLang = el.asString.trim()
            if (vLang.length <= 20) {
                out[k.trim()] = vLang
            }
        }
        return out
    }

    fun exportFullToJson(): String {
        return synchronized(this) {
            val root = com.google.gson.JsonObject()
            root.addProperty("format", "nateq_dictionary")
            root.addProperty("version", 2)

            val gObj = com.google.gson.JsonObject()
            for ((k, v) in entries) gObj.addProperty(k, v)
            root.add("entries", gObj)

            val sObj = com.google.gson.JsonObject()
            for ((tag, map) in langEntries) {
                val sub = com.google.gson.JsonObject()
                for ((k, v) in map) sub.addProperty(k, v)
                sObj.add(tag, sub)
            }
            root.add("scopes", sObj)

            val mObj = com.google.gson.JsonObject()
            for ((k, v) in matchModes) mObj.addProperty(k, v)
            root.add("modes", mObj)

            val vObj = com.google.gson.JsonObject()
            for ((k, v) in valueLangs) vObj.addProperty(k, v)
            root.add("value_langs", vObj)

            root.toString()
        }
    }

    fun exportScopeToJson(languageTag: String): String {
        val lang = normalizeLangTag(languageTag) ?: return "{}"
        return synchronized(this) {
            val root = com.google.gson.JsonObject()
            root.addProperty("format", "nateq_dictionary_scope")
            root.addProperty("version", 2)
            root.addProperty("scope", lang)

            val scopeEntries = langEntries[lang] ?: emptyMap()
            val eObj = com.google.gson.JsonObject()
            for ((k, v) in scopeEntries) eObj.addProperty(k, v)
            root.add("entries", eObj)

            val prefix = "$lang|"
            val mObj = com.google.gson.JsonObject()
            for ((k, v) in matchModes) {
                if (k.startsWith(prefix)) {
                    mObj.addProperty(k.removePrefix(prefix), v)
                }
            }
            root.add("modes", mObj)

            val vObj = com.google.gson.JsonObject()
            for ((k, v) in valueLangs) {
                if (k.startsWith(prefix)) {
                    vObj.addProperty(k.removePrefix(prefix), v)
                }
            }
            root.add("value_langs", vObj)

            root.toString()
        }
    }

    fun exportToJson(languageTag: String? = null): String {
        val lang = normalizeLangTag(languageTag)
        return if (lang == null) {
            exportFullToJson()
        } else {
            exportScopeToJson(lang)
        }
    }

    fun exportModesToJson(): String = NateqJson.toJson(matchModes)

    fun importModesFromJson(json: String): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        val parsed = parseEntries(json)
        val candidate = synchronized(this) {
            val nextModes = LinkedHashMap(matchModes)
            nextModes.putAll(parsed)
            CandidateState(entries, langEntries, nextModes, valueLangs)
        }
        return commitTransaction(
            candidate.entries,
            candidate.scopes,
            candidate.modes,
            candidate.vLangs
        )
    }

    private data class LoadedData(
        val global: Map<String, String>,
        val scopes: Map<String, Map<String, String>>,
        val modes: Map<String, String>,
        val vLangs: Map<String, String>
    )

    private fun loadFromFile(): Boolean {
        val target = when {
            dictFile.exists() && dictFile.length() > 0 -> dictFile
            backupFile.exists() && backupFile.length() > 0 -> backupFile
            else -> return false
        }
        return try {
            val text = target.readText(Charsets.UTF_8)
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
            lastFileModified = target.lastModified()
            lastFileLength = target.length()
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

    fun save(): Boolean {
        return commitTransaction(entries, langEntries, matchModes, valueLangs)
    }

    private fun commitTransaction(
        nextEntries: Map<String, String>,
        nextScopes: Map<String, Map<String, String>>,
        nextModes: Map<String, String>,
        nextVLangs: Map<String, String>
    ): Boolean {
        synchronized(storageLock) {
            val parent = context.filesDir
            if (!parent.exists()) parent.mkdirs()

            var fileLock: FileLock? = null
            var raf: RandomAccessFile? = null
            try {
                raf = RandomAccessFile(lockFile, "rw")
                fileLock = raf.channel.lock()

                val nextVer = synchronized(this) { version + 1 }
                val jsonStr = serializeToJson(
                    nextVer, nextEntries, nextScopes, nextModes, nextVLangs
                )
                val uniqueTmp = File(
                    parent,
                    "dict_${System.currentTimeMillis()}_" +
                        "${UUID.randomUUID().toString().take(8)}.tmp"
                )

                FileOutputStream(uniqueTmp).use { fos ->
                    fos.write(jsonStr.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }

                if (!uniqueTmp.exists() || uniqueTmp.length() == 0L) {
                    uniqueTmp.delete()
                    return false
                }

                if (dictFile.exists() && dictFile.length() > 0) {
                    runCatching {
                        dictFile.copyTo(backupFile, overwrite = true)
                    }
                }

                val moved = atomicMoveOrReplace(uniqueTmp, dictFile)
                if (!moved) {
                    uniqueTmp.delete()
                    return false
                }

                if (!dictFile.exists() || dictFile.length() == 0L) {
                    if (backupFile.exists() && backupFile.length() > 0) {
                        backupFile.copyTo(dictFile, overwrite = true)
                    }
                    return false
                }

                synchronized(this) {
                    swapEntries(nextEntries)
                    langEntries = nextScopes
                    matchModes = nextModes
                    valueLangs = nextVLangs
                    version = nextVer
                    lastFileModified = dictFile.lastModified()
                    lastFileLength = dictFile.length()
                    cache = null
                }
                notifyChanged()
                return true
            } catch (e: Exception) {
                android.util.Log.e(LOG_TAG, "فشل حفظ قاموس النطق ذرياً", e)
                return false
            } finally {
                runCatching { fileLock?.release() }
                runCatching { raf?.close() }
            }
        }
    }

    private fun atomicMoveOrReplace(source: File, target: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                java.nio.file.Files.move(
                    source.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                return true
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                try {
                    java.nio.file.Files.move(
                        source.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    return true
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
        }
        if (target.exists()) {
            val bak = File(target.parentFile, "${target.name}.swap.bak")
            if (target.renameTo(bak)) {
                if (source.renameTo(target)) {
                    bak.delete()
                    return true
                } else {
                    bak.renameTo(target)
                    return false
                }
            }
        }
        return source.renameTo(target)
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
    /**
     * حدود العناقيد: boundaries[i]==true تعني أن i بداية عنقود أو نهاية النص.
     */
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
                        reason = "مرفوض: أزاحه تطابقٌ آخر " +
                            "(أطول/أسبق) حُسم نهائياً"
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
                // مواضع base تقابل مواضع norm 1:1 لأننا أزلنا العلامات
                // من نفس السلسلة
                // نحتاج مواضع norm الحقيقية: ابحث عن نافذة norm تُعطي نفس base
                val normRange = findNormRangeForBase(norm, baseKey, pos)
                searchFrom = pos + 1
                if (normRange == null) continue
                val (ns, ne) = normRange
                if (!norm.isBoundary(ns) || !norm.isBoundary(ne)) continue
                if (ns < 0 ||
                    (ne > norm.normToOrig.size && ne != norm.text.length)
                ) {
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
                        reason = "مرفوض: الحروف الأساس متطابقة " +
                            "(«$matchedText») لكن التشكيل مختلف عن " +
                            "مفتاح القاموس — المطابقة حساسة للتشكيل",
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
        collectInternal(
            text, norm, segments, standaloneMaxLength
        ) { entry, cand ->
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
            return true to "مقبول: وضع أي موضع (ANYWHERE) — " +
                "المطابقة حساسة للتشكيل"
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
        cp == '\''.code || cp == '\u2019'.code ||
            cp == '-'.code || cp == '_'.code
}
