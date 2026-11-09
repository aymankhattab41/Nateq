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
 * مزود صوت محرك Acapela المدمج (Acapela Embedded Voice Provider).
 * يعمل عبر [EngineHostClient] معزولاً في عملية :engine_host.
 */
class AcapelaEmbeddedVoiceProvider(
    private val context: Context,
    private val customVoiceDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "AcapelaProvider"
        const val PROVIDER_ID = "acapela_embedded"

        fun resolveDataDir(context: Context): File? =
            AcapelaEmbeddedVoiceProvider(context).resolveDataDir()
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Acapela (مدمج)"

    fun resolveDataDir(): File? {
        val base = customVoiceDir ?: EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        return if (AcapelaVoiceManifest.isCompleteVoiceDir(base) ||
            AcapelaVoiceManifest.completeVoiceDirs(base).isNotEmpty()
        ) {
            base
        } else {
            val search = EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.ACAPELA,
                listOfNotNull(customVoiceDir)
            )
            search.firstOrNull {
                AcapelaVoiceManifest.completeVoiceDirs(it).isNotEmpty()
            }
        }
    }

    override fun isConfigured(): Boolean {
        return EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.ACAPELA,
            listOfNotNull(customVoiceDir)
        ).isCompleteVoicePresent
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> =
        withContext(Dispatchers.IO) {
            val searchDirs = EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.ACAPELA,
                listOfNotNull(customVoiceDir)
            )
            val result = mutableListOf<VoiceDescriptor>()

            for (dir in searchDirs) {
                val voices = AcapelaVoiceManifest.listVoices(dir)
                for (v in voices) {
                    // لغةٌ غير معروفة (ROOT) تظهر في القائمة العامة فقط،
                    // ولا تُعرض تحت لغةٍ صريحة كي لا تبدو كل الأصوات
                    // عربية عند اختيار العربية.
                    val matches = locale == Locale.ROOT ||
                        locale.language.isBlank() ||
                        (v.locale.language.isNotEmpty() &&
                            v.locale.language.equals(
                                locale.language,
                                ignoreCase = true
                            ))
                    if (matches) {
                        result.add(
                            VoiceDescriptor(
                                id = v.id,
                                providerId = PROVIDER_ID,
                                displayName = v.displayName,
                                locale = v.locale
                            )
                        )
                    }
                }
            }
            result.distinctBy { "${it.id}|${it.locale.language}" }
        }

    private suspend fun ensureInitialized(): Boolean {
        val base = resolveDataDir() ?: return false
        val res = EngineHostClient.init(
            context = context,
            engineId = HostProtocol.ENGINE_ACAPELA,
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
                Log.e(TAG, "Cannot synthesize: Acapela host not initialized")
                return@withContext
            }

            val targetVoiceName = (desiredVoiceName ?: voice.id)
                .removePrefix("acapela_")
                .substringBefore('(')
                .trim()

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
                engineId = HostProtocol.ENGINE_ACAPELA,
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
                    "نطق Acapela المدمج فشل [${result.code}]: ${result.message}"
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
                HostProtocol.ENGINE_ACAPELA
            )
        }
    }
}
