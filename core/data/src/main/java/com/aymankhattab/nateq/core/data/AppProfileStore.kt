package com.aymankhattab.nateq.core.data

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log

/**
 * ملف تعريف تطبيقٍ (ع٤١-ب11.1، ع٤٥): إعدادات صوتٍ ومستوى تفاصيل وميزات
 * مفعّلة خاصة بحزمة تطبيق. مشترك بين العمليات عبر SettingsRepository.
 */
data class AppProfile(
    val packageName: String,
    val engine: String? = null,
    val voiceId: String? = null,
    val speechRate: Float? = null,
    val pitch: Float? = null,
    val volume: Float? = null,
    val detailLevel: Int? = null,
    val features: Set<String> = emptySet(),
    val voiceLanguage: String? = null
) {
    /** تحويل الجزء الصوتي منه إلى طبقة [VoiceProfile] للحسم. */
    fun toVoiceProfile(): VoiceProfile = VoiceProfile(
        engine = engine,
        voiceId = voiceId,
        speechRate = speechRate,
        pitch = pitch,
        volume = volume,
        voiceLanguage = voiceLanguage
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
                p.features.joinToString(FEATURE_SEP.toString()),
                p.voiceLanguage.orEmpty()
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
            val voiceLang = if (parts.size >= 9) {
                parts[8].ifBlank { null }
            } else {
                null
            }
            result[pkg] = AppProfile(
                packageName = pkg,
                engine = parts[1].ifBlank { null },
                voiceId = parts[2].ifBlank { null },
                speechRate = parts[3].toFloatOrNull(),
                pitch = parts[4].toFloatOrNull(),
                volume = parts[5].toFloatOrNull(),
                detailLevel = parts[6].toIntOrNull(),
                features = features,
                voiceLanguage = voiceLang
            )
        }
        return result
    }
}

/**
 * مخزن ملفات التطبيقات — مشترك بين العمليات عبر SettingsRepository (ع٤٥).
 */
class AppProfileStore private constructor(
    private val context: Context,
    private val settings: SettingsRepository
) {

    val all: Map<String, AppProfile>
        get() = AppProfileCodec.decode(settings.getAppProfilesRaw())

    fun get(packageName: String): AppProfile? {
        val trimmed = packageName.trim()
        val currentAll = all
        val result = currentAll[trimmed]
        val proc = detectProcessName(context)
        Log.d(
            TAG,
            "AppProfile.get: proc=$proc, key=$trimmed, " +
                "found=${result != null}, count=${currentAll.size}"
        )
        return result
    }

    @Synchronized
    fun put(profile: AppProfile) {
        val pkg = profile.packageName.trim()
        if (pkg.isEmpty()) return
        val map = LinkedHashMap(all)
        map[pkg] = profile.copy(packageName = pkg)
        settings.setAppProfilesRaw(AppProfileCodec.encode(map))
    }

    @Synchronized
    fun remove(packageName: String): Boolean {
        val map = LinkedHashMap(all)
        if (map.remove(packageName.trim()) == null) return false
        settings.setAppProfilesRaw(AppProfileCodec.encode(map))
        return true
    }

    fun packages(): Set<String> = all.keys

    companion object {
        private const val TAG = "AppProfileStore"

        @Volatile
        private var instance: AppProfileStore? = null

        fun create(context: Context): AppProfileStore {
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
                    AppProfileStore(
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
