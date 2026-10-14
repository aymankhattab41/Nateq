package com.aymankhattab.nateq.core.data

import android.content.Context
import android.content.SharedPreferences

/**
 * ملف تعريف تطبيقٍ (ع٤١-ب11.1): إعدادات صوتٍ ومستوى تفاصيل وميزات
 * مفعّلة خاصة بحزمة تطبيق. يُخزَّن محلياً بلا مزامنة.
 */
data class AppProfile(
    val packageName: String,
    val engine: String? = null,
    val voiceId: String? = null,
    val speechRate: Float? = null,
    val pitch: Float? = null,
    val volume: Float? = null,
    val detailLevel: Int? = null,
    val features: Set<String> = emptySet()
) {
    /** تحويل الجزء الصوتي منه إلى طبقة [VoiceProfile] للحسم. */
    fun toVoiceProfile(): VoiceProfile = VoiceProfile(
        engine = engine,
        voiceId = voiceId,
        speechRate = speechRate,
        pitch = pitch,
        volume = volume
    )
}

/**
 * ترميز/فكّ ترميز ملفات التطبيقات — نقيّ قابل للاختبار.
 */
object AppProfileCodec {

    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'
    private const val FEATURE_SEP = '|'

    fun encode(map: Map<String, AppProfile>): String =
        map.entries.joinToString(RECORD.toString()) { (_, p) ->
            listOf(
                p.packageName,
                p.engine.orEmpty(),
                p.voiceId.orEmpty(),
                p.speechRate?.toString().orEmpty(),
                p.pitch?.toString().orEmpty(),
                p.volume?.toString().orEmpty(),
                p.detailLevel?.toString().orEmpty(),
                p.features.joinToString(FEATURE_SEP.toString())
            ).joinToString(FIELD.toString())
        }

    fun decode(raw: String?): Map<String, AppProfile> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, AppProfile>()
        for (record in raw.split(RECORD)) {
            if (record.isEmpty()) continue
            val parts = record.split(FIELD)
            if (parts.size < 8) continue
            val pkg = parts[0]
            if (pkg.isBlank()) continue
            val features = parts[7]
                .split(FEATURE_SEP)
                .filter { it.isNotBlank() }
                .toSet()
            result[pkg] = AppProfile(
                packageName = pkg,
                engine = parts[1].ifBlank { null },
                voiceId = parts[2].ifBlank { null },
                speechRate = parts[3].toFloatOrNull(),
                pitch = parts[4].toFloatOrNull(),
                volume = parts[5].toFloatOrNull(),
                detailLevel = parts[6].toIntOrNull(),
                features = features
            )
        }
        return result
    }
}

/**
 * مخزن ملفات التطبيقات (ع٤١-ب11) — محليٌّ في `SharedPreferences`.
 */
class AppProfileStore(
    private val prefs: SharedPreferences
) {

    val all: Map<String, AppProfile>
        get() = AppProfileCodec.decode(prefs.getString(KEY, null))

    fun get(packageName: String): AppProfile? = all[packageName.trim()]

    fun put(profile: AppProfile) {
        val pkg = profile.packageName.trim()
        if (pkg.isEmpty()) return
        val map = LinkedHashMap(all)
        map[pkg] = profile.copy(packageName = pkg)
        prefs.edit().putString(KEY, AppProfileCodec.encode(map)).apply()
    }

    fun remove(packageName: String): Boolean {
        val map = LinkedHashMap(all)
        if (map.remove(packageName.trim()) == null) return false
        prefs.edit().putString(KEY, AppProfileCodec.encode(map)).apply()
        return true
    }

    fun packages(): Set<String> = all.keys

    companion object {
        private const val KEY = "app_profiles"
        private const val PREFS = "nateq_app_profiles"

        fun create(context: Context): AppProfileStore =
            AppProfileStore(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
    }
}
