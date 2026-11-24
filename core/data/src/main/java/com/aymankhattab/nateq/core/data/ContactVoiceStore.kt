package com.aymankhattab.nateq.core.data

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * إعدادات صوتٍ جُزئية مربوطة بمفتاح (جهة اتصال/تطبيق) — ع٤١-ب10، ب11، ع٤٥.
 * الحقول الفارغة (null) تعني «لا تُغيِّر القيمة الأثقل منها».
 */
data class VoiceProfile(
    val engine: String? = null,
    val voiceId: String? = null,
    val speechRate: Float? = null,
    val pitch: Float? = null,
    val volume: Float? = null,
    val displayName: String? = null,
    val voiceLanguage: String? = null
)

/** معاملات النطق الفعلية بعد الحسم (كلها قابلة للإلغاء: null = ورث). */
data class EffectiveVoice(
    val engine: String? = null,
    val voiceId: String? = null,
    val speechRate: Float? = null,
    val pitch: Float? = null,
    val volume: Float? = null,
    val displayName: String? = null,
    val voiceLanguage: String? = null
)

/**
 * حاسم أولوية الأصوات (ع٤١-ب10.3، ب11.2، ع٤٢-11، ع٤٥):
 * الافتراضي ← الفئة ← ملف التطبيق ← جهة الاتصال.
 */
object VoiceProfileResolver {

    fun effective(
        base: EffectiveVoice = EffectiveVoice(),
        category: VoiceProfile? = null,
        appProfile: VoiceProfile? = null,
        contact: VoiceProfile? = null
    ): EffectiveVoice {
        var out = base
        for (layer in listOf(category, appProfile, contact)) {
            if (layer == null) continue
            val newEngine = layer.engine ?: out.engine
            val newVoiceId = if (layer.engine != null &&
                layer.engine != out.engine
            ) {
                layer.voiceId
            } else {
                layer.voiceId ?: out.voiceId
            }
            val newRate = when {
                out.speechRate != null && layer.speechRate != null ->
                    (out.speechRate * layer.speechRate).coerceIn(0.1f, 6.0f)
                layer.speechRate != null -> layer.speechRate
                else -> out.speechRate
            }
            val newPitch = when {
                out.pitch != null && layer.pitch != null ->
                    (out.pitch * layer.pitch).coerceIn(0.2f, 2.0f)
                layer.pitch != null -> layer.pitch
                else -> out.pitch
            }
            val newVolume = layer.volume ?: out.volume
            val newDisplayName = layer.displayName ?: out.displayName
            val newLang = layer.voiceLanguage ?: out.voiceLanguage
            out = EffectiveVoice(
                engine = newEngine,
                voiceId = newVoiceId,
                speechRate = newRate,
                pitch = newPitch,
                volume = newVolume,
                displayName = newDisplayName,
                voiceLanguage = newLang
            )
        }
        return out
    }
}

/**
 * ترميز/فكّ ترميز خريطة أصواتٍ مربوطة بمفاتيح — نقيّ وقابل للاختبار.
 */
object VoiceProfileCodec {

    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'

    fun encode(map: Map<String, VoiceProfile>): String =
        map.entries.joinToString(RECORD.toString()) { (key, v) ->
            listOf(
                key,
                v.engine.orEmpty(),
                v.voiceId.orEmpty(),
                v.speechRate?.toString().orEmpty(),
                v.pitch?.toString().orEmpty(),
                v.volume?.toString().orEmpty(),
                v.displayName.orEmpty(),
                v.voiceLanguage.orEmpty()
            ).joinToString(FIELD.toString())
        }

    fun decode(raw: String?): Map<String, VoiceProfile> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, VoiceProfile>()
        for (record in raw.split(RECORD)) {
            if (record.isEmpty()) continue
            val parts = record.split(FIELD)
            if (parts.size < 6) continue
            val key = parts[0]
            if (key.isBlank()) continue
            val disp = if (parts.size >= 7) parts[6].ifBlank { null } else null
            val lang = if (parts.size >= 8) parts[7].ifBlank { null } else null
            result[key] = VoiceProfile(
                engine = parts[1].ifBlank { null },
                voiceId = parts[2].ifBlank { null },
                speechRate = parts[3].toFloatOrNull(),
                pitch = parts[4].toFloatOrNull(),
                volume = parts[5].toFloatOrNull(),
                displayName = disp,
                voiceLanguage = lang
            )
        }
        return result
    }
}

/**
 * سجل صوت جهة اتصال بهوية أساسية وفهارس مشتقة مملوكة له (البنود 18 و19 و20).
 */
data class ContactVoiceRecord(
    val primaryKey: String,
    val profile: VoiceProfile
)

/**
 * مخزن أصوات جهات الاتصال — مشترك بين العمليات عبر SettingsRepository.
 * يدير الهويات الأساسية والفهارس المشتقة دون ترك فهارس يتيمة أو خلط هويات.
 */
class ContactVoiceStore private constructor(
    private val context: Context,
    private val settings: SettingsRepository
) {

    internal fun loadRecords(): List<ContactVoiceRecord> {
        val raw = settings.getContactVoicesRaw()
        val rawMap = VoiceProfileCodec.decode(raw)
        return migrateLegacyEntries(rawMap)
    }

    private fun saveRecords(records: List<ContactVoiceRecord>) {
        val map = LinkedHashMap<String, VoiceProfile>()
        for (rec in records) {
            map[rec.primaryKey] = rec.profile
        }
        settings.setContactVoicesRaw(VoiceProfileCodec.encode(map))
    }

    val all: Map<String, VoiceProfile>
        get() = loadRecords().associate { it.primaryKey to it.profile }

    /** يبحث بأيٍّ من صِيغ الرقم حتى يجد أول تطابق غير ملتبس. */
    fun find(keys: List<String>): VoiceProfile? {
        val records = loadRecords()
        for (key in keys) {
            val normalized = key.trim()
            if (normalized.isEmpty()) continue

            // 1. المطابقة المباشرة للمفتاح الأساسي
            records.firstOrNull { it.primaryKey == normalized }?.let {
                return it.profile
            }

            // 2. مطابقة صيغ الهاتف
            if (isPhoneCandidate(normalized)) {
                records.firstOrNull { rec ->
                    isPhoneCandidate(rec.primaryKey) &&
                        areSamePhone(normalized, rec.primaryKey)
                }?.let { return it.profile }
            }

            // 3. مطابقة الاسم المطبّع (إن كان غير ملتبس)
            val normName = ContactLookupNormalizer.normalizeName(normalized)
            if (normName.isNotEmpty()) {
                val compact = normName.replace(" ", "")
                val matches = records.filter { rec ->
                    val recNorm =
                        ContactLookupNormalizer.normalizeName(rec.primaryKey)
                    val dispNorm = rec.profile.displayName?.let {
                        ContactLookupNormalizer.normalizeName(it)
                    }.orEmpty()
                    recNorm == normName ||
                        recNorm.replace(" ", "") == compact ||
                        dispNorm == normName ||
                        dispNorm.replace(" ", "") == compact
                }
                val distinctProfiles = matches.map { it.profile }.distinct()
                if (distinctProfiles.size == 1) {
                    return distinctProfiles.first()
                }
            }
        }
        return null
    }

    fun get(key: String): VoiceProfile? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        val records = loadRecords()

        // 1. مطابقة مباشرة بالمفتاح
        var result = records.firstOrNull { it.primaryKey == trimmed }?.profile

        // 2. مطابقة الهاتف إن كان رقماً
        if (result == null && isPhoneCandidate(trimmed)) {
            result = records.firstOrNull { rec ->
                isPhoneCandidate(rec.primaryKey) &&
                    areSamePhone(trimmed, rec.primaryKey)
            }?.profile
        }

        // 3. مطابقة الاسم إن كان غير ملتبس
        if (result == null) {
            val normName = ContactLookupNormalizer.normalizeName(trimmed)
            if (normName.isNotEmpty()) {
                val compact = normName.replace(" ", "")
                val matches = records.filter { rec ->
                    val recNorm =
                        ContactLookupNormalizer.normalizeName(rec.primaryKey)
                    val dispNorm = rec.profile.displayName?.let {
                        ContactLookupNormalizer.normalizeName(it)
                    }.orEmpty()
                    recNorm == normName ||
                        recNorm.replace(" ", "") == compact ||
                        dispNorm == normName ||
                        dispNorm.replace(" ", "") == compact
                }
                val distinctProfiles = matches.map { it.profile }.distinct()
                if (distinctProfiles.size == 1) {
                    result = distinctProfiles.first()
                }
            }
        }

        val proc = detectProcessName(context)
        Log.d(
            TAG,
            "ContactVoice.get: proc=$proc, found=${result != null}, " +
                "count=${records.size}"
        )
        return result
    }

    /**
     * حفظ صوت جهة اتصال بتحديث الهوية الأساسية وفهارسها المشتقة (البند 18).
     */
    @Synchronized
    fun put(key: String, profile: VoiceProfile) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        val records = loadRecords().toMutableList()

        val disp = profile.displayName?.ifBlank { null } ?: trimmed
        val enriched = profile.copy(displayName = disp)

        val idx = records.indexOfFirst { rec ->
            rec.primaryKey == trimmed || (
                isPhoneCandidate(trimmed) &&
                    isPhoneCandidate(rec.primaryKey) &&
                    areSamePhone(trimmed, rec.primaryKey)
            )
        }

        if (idx >= 0) {
            val existing = records[idx]
            val keepKey = if (trimmed.startsWith("+")) {
                trimmed
            } else {
                existing.primaryKey
            }
            records[idx] = existing.copy(
                primaryKey = keepKey,
                profile = enriched
            )
        } else {
            records.add(ContactVoiceRecord(trimmed, enriched))
        }

        saveRecords(records)
    }

    /**
     * حذف السجل الأساسي وفهارسه المشتقة دون المساس بالآخرين (البند 18).
     */
    @Synchronized
    fun remove(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        val records = loadRecords().toMutableList()

        // 1. المطابقة المباشرة للمفتاح الأساسي
        var targetIdx = records.indexOfFirst { it.primaryKey == trimmed }

        // 2. مطابقة صيغ الهاتف إن كان رقماً
        if (targetIdx < 0 && isPhoneCandidate(trimmed)) {
            targetIdx = records.indexOfFirst { rec ->
                isPhoneCandidate(rec.primaryKey) &&
                    areSamePhone(trimmed, rec.primaryKey)
            }
        }

        // 3. مطابقة الاسم إن كان وحيداً
        if (targetIdx < 0) {
            val norm = ContactLookupNormalizer.normalizeName(trimmed)
            val compact = norm.replace(" ", "")
            val matches = records.indices.filter { i ->
                val rec = records[i]
                val recNorm =
                    ContactLookupNormalizer.normalizeName(rec.primaryKey)
                val dispNorm = rec.profile.displayName?.let {
                    ContactLookupNormalizer.normalizeName(it)
                }.orEmpty()
                recNorm == norm || recNorm.replace(" ", "") == compact ||
                    dispNorm == norm || dispNorm.replace(" ", "") == compact
            }
            if (matches.size == 1) {
                targetIdx = matches.first()
            } else if (matches.size > 1) {
                Log.w(TAG, "Cannot remove: ambiguous contact name")
                return false
            }
        }

        if (targetIdx >= 0) {
            records.removeAt(targetIdx)
            saveRecords(records)
            return true
        }
        return false
    }

    fun keys(): Set<String> = all.keys

    /**
     * قائمة السجلات الأساسية للعرض في الواجهة دون تكرار مفهرس ودون إخفاء
     * أرقام مستقلة تشابهت في الصوت أو الاسم (البند 19).
     */
    fun displayEntries(): List<Pair<String, VoiceProfile>> {
        val records = loadRecords()
        return records.map { it.primaryKey to it.profile }
    }

    companion object {
        private const val TAG = "ContactVoiceStore"

        @Volatile
        private var instance: ContactVoiceStore? = null

        fun create(context: Context): ContactVoiceStore {
            val app = context.applicationContext ?: context
            val current = instance
            if (current != null && current.context === app) {
                return current
            }
            return synchronized(this) {
                val existing = instance
                if (existing != null && existing.context === app) {
                    existing
                } else {
                    ContactVoiceStore(
                        app,
                        SettingsRepository.create(app)
                    ).also { instance = it }
                }
            }
        }

        fun isPhoneCandidate(s: String): Boolean =
            ContactLookupNormalizer.isPhoneCandidate(s)

        fun areSamePhone(a: String, b: String): Boolean =
            ContactLookupNormalizer.areSamePhone(a, b)

        /**
         * ترحيل محافظ للبيانات القديمة دون فقدان أو حسم عشوائي (البند 18).
         */
        internal fun migrateLegacyEntries(
            rawMap: Map<String, VoiceProfile>
        ): List<ContactVoiceRecord> {
            if (rawMap.isEmpty()) return emptyList()

            val phoneEntries = mutableListOf<Pair<String, VoiceProfile>>()
            val nonPhoneEntries = mutableListOf<Pair<String, VoiceProfile>>()

            for ((key, profile) in rawMap) {
                if (isPhoneCandidate(key)) {
                    phoneEntries.add(key to profile)
                } else {
                    nonPhoneEntries.add(key to profile)
                }
            }

            val primaryPhoneRecords = mutableListOf<ContactVoiceRecord>()
            val handledPhoneKeys = mutableSetOf<String>()

            for (entry in phoneEntries) {
                val key = entry.first
                if (handledPhoneKeys.contains(key)) continue

                val related = phoneEntries.filter { other ->
                    areSamePhone(key, other.first)
                }
                related.forEach { handledPhoneKeys.add(it.first) }

                val bestKey = related.map { it.first }.maxWithOrNull(
                    compareBy<String> { it.startsWith("+") }
                        .thenBy { it.length }
                ) ?: key

                val bestProfile = related.map { it.second }.firstOrNull {
                    !it.displayName.isNullOrBlank()
                } ?: entry.second

                primaryPhoneRecords.add(
                    ContactVoiceRecord(bestKey, bestProfile)
                )
            }

            val result = primaryPhoneRecords.toMutableList()

            val derivedNamesByDisp =
                mutableMapOf<String, MutableList<ContactVoiceRecord>>()
            for (rec in primaryPhoneRecords) {
                val disp = rec.profile.displayName
                if (!disp.isNullOrBlank()) {
                    val norm = ContactLookupNormalizer.normalizeName(disp)
                    if (norm.isNotEmpty()) {
                        derivedNamesByDisp.getOrPut(norm) {
                            mutableListOf()
                        }.add(rec)
                    }
                }
            }

            for ((key, profile) in nonPhoneEntries) {
                val normKey = ContactLookupNormalizer.normalizeName(key)
                val matchingPhoneRecs = derivedNamesByDisp[normKey]
                if (matchingPhoneRecs != null &&
                    matchingPhoneRecs.isNotEmpty()
                ) {
                    continue
                }
                result.add(ContactVoiceRecord(key, profile))
            }

            return result
        }

        private fun detectProcessName(context: Context): String {
            val pid = Process.myPid()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? ActivityManager
            val proc = am?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }?.processName
            return when {
                proc?.endsWith(":tts") == true -> "tts"
                proc == context.packageName -> "main"
                else -> proc ?: "unknown"
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTesting() {
            instance = null
        }
    }
}
