package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.SharedPreferences

/**
 * إعدادات صوتٍ جُزئية مربوطة بمفتاح (جهة اتصال/تطبيق) — ع٤١-ب10، ب11.
 * الحقول الفارغة (null) تعني «لا تُغيِّر القيمة الأثقل منها».
 */
data class VoiceProfile(
    val engine: String? = null,
    val voiceId: String? = null,
    val speechRate: Float? = null,
    val pitch: Float? = null,
    val volume: Float? = null
)

/** معاملات النطق الفعلية بعد الحسم (كلها قابلة للإلغاء: null = ورث). */
data class EffectiveVoice(
    val engine: String? = null,
    val voiceId: String? = null,
    val speechRate: Float? = null,
    val pitch: Float? = null,
    val volume: Float? = null
)

/**
 * حاسم أولوية الأصوات (ع٤١-ب10.3، ب11.2، ع٤٢-11):
 * الافتراضي ← الفئة ← ملف التطبيق ← جهة الاتصال.
 * كل طبقةٍ تُمرَّر بعد الأثقل منها، والسرعة والنبرة تتضاعفان بدلاً
 * من الاستبدال المطلق، بينما يُلغي مستوى الصوت ما قبله عند التحديد.
 * دالة نقية قابلة للاختبار.
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
            out = EffectiveVoice(
                engine = newEngine,
                voiceId = newVoiceId,
                speechRate = newRate,
                pitch = newPitch,
                volume = newVolume
            )
        }
        return out
    }
}

/**
 * ترميز/فكّ ترميز خريطة أصواتٍ مربوطة بمفاتيح — نقيّ وقابل للاختبار.
 * فاصل الحقول 0x1F وفاصل السجلات 0x1E (غير قابلين للظهور في الأسماء).
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
                v.volume?.toString().orEmpty()
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
            result[key] = VoiceProfile(
                engine = parts[1].ifBlank { null },
                voiceId = parts[2].ifBlank { null },
                speechRate = parts[3].toFloatOrNull(),
                pitch = parts[4].toFloatOrNull(),
                volume = parts[5].toFloatOrNull()
            )
        }
        return result
    }
}

/**
 * مخزن أصوات جهات الاتصال (ع٤١-ب10.1) — محليٌّ في `SharedPreferences`
 * بلا مزامنة. تُطبَّع المفاتيح عند القراءة والكتابة.
 */
class ContactVoiceStore(
    private val prefs: SharedPreferences
) {

    val all: Map<String, VoiceProfile>
        get() = VoiceProfileCodec.decode(prefs.getString(KEY, null))

    /** يبحث بأيٍّ من صِيغ الرقم حتى يجد أول تطابق. */
    fun find(keys: List<String>): VoiceProfile? {
        val map = all
        for (key in keys) {
            val normalized = key.trim()
            if (normalized.isEmpty()) continue
            map[normalized]?.let { return it }
        }
        return null
    }

    fun get(key: String): VoiceProfile? = all[key.trim()]

    fun put(key: String, profile: VoiceProfile) {
        val normalized = key.trim()
        if (normalized.isEmpty()) return
        val map = LinkedHashMap(all)
        map[normalized] = profile
        prefs.edit()
            .putString(KEY, VoiceProfileCodec.encode(map))
            .apply()
    }

    /** حذف صوت جهة؛ @return هل أُزيل شيء؟ (ع٤١-ب10.4) */
    fun remove(key: String): Boolean {
        val normalized = key.trim()
        val map = LinkedHashMap(all)
        if (map.remove(normalized) == null) return false
        prefs.edit()
            .putString(KEY, VoiceProfileCodec.encode(map))
            .apply()
        return true
    }

    fun keys(): Set<String> = all.keys

    companion object {
        private const val KEY = "contact_voices"
        private const val PREFS = "nateq_contact_voices"

        fun create(context: Context): ContactVoiceStore =
            ContactVoiceStore(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
    }
}
