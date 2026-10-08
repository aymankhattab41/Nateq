package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * مزود صوت محرك Nuance Vocalizer Expressive 2 (Embedded Engine) —
 * **لا تُحمَّل المكتبة الأصلية في هذه العملية أبداً** (ع٢٩-5): كل
 * نداء (تهيئة/قائمة أصوات/نطق) يمرّ عبر [EngineHostClient] إلى
 * `:engine_host`، فيه يعيش `VocalizerEngine` وحده؛ فإن انهار
 *JNI قُتلت تلك العملية فقط.
 */
class VocalizerEmbeddedVoiceProvider(
    private val context: Context,
    private val customVoiceDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "VocalizerEmbedded"
        const val PROVIDER_ID = "vocalizer_embedded"
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Vocalizer Expressive 2 (مدمج)"

    /** مسار بيانات مُتحقق منه يُعاد استخدامه (بلا مسح قرصي متكرر). */
    @Volatile
    private var cachedDataPath: String? = null

    /** كل مجلدات البحث من المصدر الموحّد (ع٢٩-3) — بلا مسارات خام. */
    private fun searchDirs(): List<File> {
        val extra = listOfNotNull(customVoiceDir)
        return EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.VOCALIZER,
            extra
        )
    }

    /**
     * يبحث عن مجلد فيه ملفات بيانات Vocalizer (.dat/.hdr).
     * فحصُ قرصٍ فقط — لا يلمس المكتبة الأصلية ولا المضيف.
     */
    private fun findDataDir(): File? {
        for (dir in searchDirs()) {
            if (dir.exists() && dir.isDirectory) {
                val datFiles = dir.listFiles { f ->
                    f.extension == "dat" || f.extension == "hdr"
                }
                if (!datFiles.isNullOrEmpty()) return dir
            }
        }
        return null
    }

    /**
     * ع٢٩-2 (3): فحص خفيف لا يلمس المكتبة الأصلية — يعود true
     * إذا وُجدت ملفات البيانات في أحد المجلدات. لا يلزم نجاح
     * التهيئة ليظهر المحرك.
     */
    override fun isConfigured(): Boolean {
        return findDataDir() != null
    }

    /**
     * ع٢٩-5: تهيئة المحرّك **في المضيف**. المسار يُحقَن مرة واحدة،
     * ثم يُستدعى init عند كل نطق/قائمة حتى يعود المضيف المُعاد
     * تشغيله (session جديدة) إلى جاهزيته بنداءٍ واحد سريع.
     */
    private suspend fun ensureInitialized(): Boolean {
        if (cachedDataPath == null) {
            // ع٢٩-3: تُنجَز هجرة المجلد الخارجي قبل البحث.
            EmbeddedStorage.migrateExternalToInternal(
                context,
                EmbeddedStorage.Engine.VOCALIZER
            )
            // ع٢٩-2 (6): بلا مجلد بيانات صالح لا ننشئ محركاً أصلاً.
            val dataDir = findDataDir()
            if (dataDir == null) {
                Log.w(
                    TAG,
                    "No Vocalizer data dir (.dat/.hdr) found — " +
                        "engine will not be created"
                )
                return false
            }
            cachedDataPath = dataDir.absolutePath
        }
        val result = EngineHostClient.init(
            context,
            HostProtocol.ENGINE_VOCALIZER,
            cachedDataPath.orEmpty()
        )
        if (!result.ok) {
            Log.e(
                TAG,
                "تهيئة المضيف فشلت [${result.code}]: ${result.message}"
            )
            return false
        }
        // ع٢٩-9: نجاح التهيئة يُبطل كاش المحركات والاكتشاف.
        EnginePicker.invalidateAfterStorageChange()
        return true
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        return withContext(Dispatchers.IO) {
            if (!ensureInitialized()) return@withContext emptyList()
            val result = EngineHostClient.listVoices(
                context,
                HostProtocol.ENGINE_VOCALIZER
            )
            result.failure?.let { failure ->
                Log.e(
                    TAG,
                    "قائمة أصوات المضيف فشلت " +
                        "[${failure.code}]: ${failure.message}"
                )
                return@withContext emptyList()
            }
            result.voices.mapNotNull { voice ->
                val name = voice.name.ifBlank { return@mapNotNull null }
                // ع٢٩-10 (4): الإقليم الفعلي من المحرّك لا يُسقط.
                val voiceLocale = if (voice.langTag.isBlank()) {
                    locale
                } else {
                    Locale.forLanguageTag(voice.langTag)
                }
                val matchesLang = locale.language.isEmpty() ||
                    voiceLocale.language.equals(
                        locale.language,
                        ignoreCase = true
                    )
                if (!matchesLang) return@mapNotNull null
                VoiceDescriptor(
                    id = voice.id,
                    providerId = PROVIDER_ID,
                    displayName = name,
                    locale = voiceLocale
                )
            }
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        enginePackage: String?,
        voiceLocale: Locale?,
        desiredVoiceName: String?
    ) {
        withContext(Dispatchers.IO) {
            if (!ensureInitialized()) {
                Log.e(TAG, "Cannot synthesize: host not initialized")
                return@withContext
            }
            val targetVoiceName = desiredVoiceName ?: voice.displayName
            val result = EngineHostClient.synthesize(
                context = context,
                engineId = HostProtocol.ENGINE_VOCALIZER,
                voiceId = targetVoiceName,
                text = text,
                rate = speechRate,
                pitch = pitch,
                volume = volume,
                onFormatInfo = onFormatInfo,
                onAudioChunk = onAudioChunk
            )
            if (!result.ok) {
                Log.e(
                    TAG,
                    "نطق المضيف فشل [${result.code}]: ${result.message}"
                )
            }
        }
    }

    override fun shutdown() {
        cachedDataPath = null
        EngineHostClient.release(
            context,
            HostProtocol.ENGINE_VOCALIZER
        )
    }
}
