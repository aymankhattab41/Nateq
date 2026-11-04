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
 * مزود صوت محرك مايكروسوفت المدمج (Microsoft Neural Embedded Voice Provider).
 * يعمل عبر [EngineHostClient] معزولاً في عملية :engine_host.
 */
class MicrosoftEmbeddedVoiceProvider(
    private val context: Context,
    private val customVoiceDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "MsEmbeddedProvider"
        const val PROVIDER_ID = "microsoft_embedded"

        fun resolveDataDir(context: Context): File? =
            MicrosoftEmbeddedVoiceProvider(context).resolveDataDir()
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Microsoft (مدمج)"

    fun resolveDataDir(): File? {
        val base = customVoiceDir ?: MicrosoftDownloader.getMicrosoftBaseDir(
            context
        )
        return if (MicrosoftVoiceManifest.hasCompleteVoice(base)) base else null
    }

    override fun isConfigured(): Boolean {
        return EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.MICROSOFT,
            listOfNotNull(customVoiceDir)
        ).isCompleteVoicePresent
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> =
        withContext(Dispatchers.IO) {
            val base = resolveDataDir() ?: return@withContext emptyList()
            val completeDirs = MicrosoftVoiceManifest.completeVoiceDirs(base)
            if (completeDirs.isEmpty()) return@withContext emptyList()

            val catalogItems = MicrosoftModelCatalog.getItems(context)
            val result = mutableListOf<VoiceDescriptor>()

            for (dir in completeDirs) {
                val matched = catalogItems.firstOrNull { item ->
                    item.topDirName.equals(dir.name, ignoreCase = true) ||
                        dir.name.contains(item.topDirName, ignoreCase = true) ||
                        item.id.equals(dir.name, ignoreCase = true)
                }

                if (matched != null) {
                    if (locale == Locale.ROOT ||
                        locale.language.isBlank() ||
                        matched.locale.language.equals(
                            locale.language,
                            ignoreCase = true
                        )
                    ) {
                        result.add(
                            VoiceDescriptor(
                                id = matched.voiceName,
                                providerId = PROVIDER_ID,
                                displayName = matched.displayNameAr,
                                locale = matched.locale
                            )
                        )
                    }
                } else {
                    result.add(
                        VoiceDescriptor(
                            id = dir.name,
                            providerId = PROVIDER_ID,
                            displayName = dir.name,
                            locale = Locale.getDefault()
                        )
                    )
                }
            }

            result.distinctBy { it.id }
        }

    private suspend fun ensureInitialized(): Boolean {
        val base = resolveDataDir() ?: return false
        val res = EngineHostClient.init(
            context = context,
            engineId = HostProtocol.ENGINE_MICROSOFT,
            dataDir = base.absolutePath
        )
        return res.ok
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
                Log.e(TAG, "Cannot synthesize: Microsoft host not initialized")
                return@withContext
            }

            val targetQuery = (desiredVoiceName ?: voice.displayName)
                .removePrefix("microsoft_")
                .trim()

            val catalogItem = MicrosoftModelCatalog.findItemByNameOrModel(
                context,
                targetQuery
            )
            val targetVoiceName = catalogItem?.modelName
                ?: catalogItem?.voiceName
                ?: targetQuery

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
                engineId = HostProtocol.ENGINE_MICROSOFT,
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
                    "نطق مايكروسوفت المدمج فشل " +
                        "[${result.code}]: ${result.message}"
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
        runCatching {
            EngineHostClient.release(
                context,
                HostProtocol.ENGINE_MICROSOFT
            )
        }
    }
}
