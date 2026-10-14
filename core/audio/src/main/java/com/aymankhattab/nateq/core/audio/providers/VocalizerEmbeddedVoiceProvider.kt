package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.announcement.LoudnessStage
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.data.SettingsRepository
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

        fun resolveDataDir(context: Context): File? =
            VocalizerEmbeddedVoiceProvider(context).resolveDataDir()
    }

    /**
     * ع٣١-2: تحديد مجلد البيانات الفعلي لـ Vocalizer.
     */
    fun resolveDataDir(): File? {
        EmbeddedStorage.migrateExternalToInternal(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        return findDataDir()
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
     * يبحث عن مجلد فيه ملفات بيانات Vocalizer (.dat/.hdr) لصوت مكتمل.
     * فحصُ قرصٍ فقط — لا يلمس المكتبة الأصلية ولا المضيف ولا يُنشئ مجلدات.
     */
    private fun findDataDir(): File? {
        for (dir in searchDirs()) {
            if (VocalizerVoiceManifest.hasCompleteVoice(dir)) {
                return dir
            }
        }
        return null
    }

    /**
     * ع٣١-4: فحص خفيف لا يلمس المكتبة الأصلية — يعود true
     * فقط عند وجود صوت مكتمل واحد على الأقل (.hdr + .dat >= 1MB).
     */
    override fun isConfigured(): Boolean {
        return EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER,
            listOfNotNull(customVoiceDir)
        ).isCompleteVoicePresent
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
                EngineStatusRegistry.record(
                    PROVIDER_ID,
                    EngineStatusRegistry.State.NOT_INSTALLED,
                    message = "لا ملفات أصوات Vocalizer على القرص"
                )
                return false
            }
            cachedDataPath = dataDir.absolutePath
        }
        val prevState = EngineStatusRegistry.statusOf(PROVIDER_ID)?.state
        EngineStatusRegistry.noteInitPending(PROVIDER_ID)
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
            EngineStatusRegistry.noteFailure(
                PROVIDER_ID,
                result.code,
                result.message
            )
            return false
        }
        EngineStatusRegistry.noteReady(PROVIDER_ID)
        // ع٣٠-9: إبطال الكاش فقط عند الانتقال إلى READY من غيرها
        if (prevState != EngineStatusRegistry.State.READY) {
            EnginePicker.invalidateAfterStorageChange()
        }
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
                EngineStatusRegistry.noteFailure(
                    PROVIDER_ID,
                    failure.code,
                    failure.message
                )
                return@withContext emptyList()
            }
            result.voices.mapNotNull { voice ->
                val name = voice.name.ifBlank { return@mapNotNull null }
                
                // ع٣١-10 (4): أصلح locale باستخدام الكتالوج الثابت لأن المضيف
                // يرجع display name مثل "American English" وليس language tag.
                val catalogItem =
                    VocalizerModelCatalog.ITEMS.firstOrNull { item ->
                    item.voiceName == name || item.id == voice.id
                }
                val voiceLocale = catalogItem?.locale
                    ?: if (voice.langTag.isBlank()) {
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
        desiredVoiceName: String?,
        category: String?
    ) {
        withContext(Dispatchers.IO) {
            if (!ensureInitialized()) {
                Log.e(TAG, "Cannot synthesize: host not initialized")
                return@withContext
            }
            val rawName = (desiredVoiceName ?: voice.displayName)
                .removePrefix("vocalizer_")
                .trim()
            val targetVoiceName = when {
                rawName.isNotBlank() &&
                    !rawName.equals("Device Default", ignoreCase = true) &&
                    !rawName.equals("ar-EG", ignoreCase = true) &&
                    !rawName.equals("en-US", ignoreCase = true) &&
                    !rawName.equals("ar", ignoreCase = true) &&
                    !rawName.equals("en", ignoreCase = true) ->
                    rawName
                voiceLocale?.language.equals("en", ignoreCase = true) ->
                    "Samantha"
                else ->
                    "Laila"
            }
            val isEvent = LoudnessStage.isEventCategory(category)
            val chunkConsumer: (ByteArray, Int) -> Unit = if (isEvent) {
                val gainDb = runCatching {
                    SettingsRepository.create(context).getEventPcmGainDb()
                }.getOrDefault(LoudnessStage.DEFAULT_GAIN_DB)
                if (gainDb > 0f) {
                    { chunk, validLength ->
                        if (validLength > 0) {
                            LoudnessStage.processPcm16(
                                chunk, 0, validLength, gainDb
                            )
                        }
                        onAudioChunk(chunk, validLength)
                    }
                } else {
                    onAudioChunk
                }
            } else {
                onAudioChunk
            }
            val result = EngineHostClient.synthesize(
                context = context,
                engineId = HostProtocol.ENGINE_VOCALIZER,
                voiceId = targetVoiceName,
                text = text,
                rate = speechRate,
                pitch = pitch,
                volume = volume,
                onFormatInfo = onFormatInfo,
                onAudioChunk = chunkConsumer
            )
            if (!result.ok) {
                Log.e(
                    TAG,
                    "نطق المضيف فشل [${result.code}]: ${result.message}"
                )
                EngineStatusRegistry.noteFailure(
                    PROVIDER_ID,
                    result.code,
                    result.message
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
