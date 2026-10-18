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
 * مخزن أصوات جهات الاتصال — مشترك بين العمليات عبر SettingsRepository (ع٤٥).
 */
class ContactVoiceStore private constructor(
    private val context: Context,
    private val settings: SettingsRepository
) {

    val all: Map<String, VoiceProfile>
        get() = VoiceProfileCodec.decode(settings.getContactVoicesRaw())

    /** يبحث بأيٍّ من صِيغ الرقم حتى يجد أول تطابق. */
    fun find(keys: List<String>): VoiceProfile? {
        val map = all
        for (key in keys) {
            val normalized = key.trim()
            if (normalized.isEmpty()) continue
            map[normalized]?.let { return it }
            val normName = ContactLookupNormalizer.normalizeName(normalized)
            if (normName.isNotEmpty()) {
                map[normName]?.let { return it }
            }
        }
        return null
    }

    fun get(key: String): VoiceProfile? {
        val trimmed = key.trim()
        val currentAll = all
        var result = currentAll[trimmed]
        if (result == null) {
            val normName = ContactLookupNormalizer.normalizeName(trimmed)
            if (normName.isNotEmpty()) {
                result = currentAll[normName]
            }
        }
        if (result == null && trimmed.any { it.isDigit() }) {
            val forms = ContactLookupNormalizer.phoneLookupForms(trimmed)
            for (f in forms) {
                val found = currentAll[f]
                if (found != null) {
                    result = found
                    break
                }
            }
        }

        val proc = detectProcessName(context)
        Log.d(
            TAG,
            "ContactVoice.get: proc=$proc, key=$trimmed, " +
                "found=${result != null}, count=${currentAll.size}"
        )
        return result
    }

    /**
     * حفظ صوت جهة اتصال مع تطبيع المفتاح وفهرسته (ع٤٥).
     */
    @Synchronized
    fun put(key: String, profile: VoiceProfile) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        val map = LinkedHashMap(all)

        val disp = profile.displayName ?: trimmed
        val enriched = profile.copy(displayName = disp)
        map[trimmed] = enriched

        if (trimmed.any { it.isDigit() }) {
            val forms = ContactLookupNormalizer.phoneLookupForms(trimmed)
            for (f in forms) {
                map[f] = enriched
            }
            if (!profile.displayName.isNullOrBlank()) {
                val normDisp = ContactLookupNormalizer.normalizeName(
                    profile.displayName
                )
                if (normDisp.isNotEmpty()) {
                    map[normDisp] = enriched
                }
            }
        } else {
            val normName = ContactLookupNormalizer.normalizeName(trimmed)
            if (normName.isNotEmpty()) {
                map[normName] = enriched
            }
        }

        settings.setContactVoicesRaw(VoiceProfileCodec.encode(map))
    }

    @Synchronized
    fun remove(key: String): Boolean {
        val trimmed = key.trim()
        val map = LinkedHashMap(all)
        val existing = map[trimmed] ?: get(trimmed) ?: return false

        map.remove(trimmed)
        if (trimmed.any { it.isDigit() }) {
            val forms = ContactLookupNormalizer.phoneLookupForms(trimmed)
            for (f in forms) {
                map.remove(f)
            }
        }
        val norm = ContactLookupNormalizer.normalizeName(trimmed)
        if (norm.isNotEmpty()) {
            map.remove(norm)
        }
        val disp = existing.displayName
        if (!disp.isNullOrBlank()) {
            map.remove(disp)
            map.remove(ContactLookupNormalizer.normalizeName(disp))
        }

        settings.setContactVoicesRaw(VoiceProfileCodec.encode(map))
        return true
    }

    fun keys(): Set<String> = all.keys

    /**
     * قائمة السجلات الأساسية للعرض في الواجهة (استبعاد التكرار المفهرس).
     */
    fun displayEntries(): List<Pair<String, VoiceProfile>> {
        val map = all
        val seen = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, VoiceProfile>>()

        for ((k, v) in map) {
            val label = v.displayName ?: k
            val id = "${v.voiceId}_${v.engine}_$label"
            if (seen.add(id)) {
                result.add(k to v)
            }
        }
        return result
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
