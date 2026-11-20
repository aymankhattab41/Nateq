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
                val voices = AcapelaVoiceManifest.cachedVoices(dir)
                for (v in voices) {
                    val resolvedLocale =
                        if (v.locale.language.isNotBlank()) {
                            v.locale
                        } else {
                            inferVoiceLocale(v, locale)
                        }
                    val requested = locale.language.takeIf {
                        it.isNotBlank()
                    }?.let {
                        com.aymankhattab.nateq.util.LocaleUtils
                            .normalizeLanguageCode(it)
                    }
                    val voiceLang =
                        com.aymankhattab.nateq.util.LocaleUtils
                            .normalizeLanguageCode(
                                resolvedLocale.toLanguageTag()
                            )
                    val matches = locale == Locale.ROOT ||
                        requested == null ||
                        voiceLang.equals(requested, ignoreCase = true) ||
                        (requested == com.aymankhattab.nateq.util
                            .LanguageCode.AR.tag &&
                            (v.voiceName.contains(
                                "mariam", ignoreCase = true
                            ) || v.id.contains(
                                "mariam", ignoreCase = true
                            )))
                    if (matches) {
                        result.add(
                            VoiceDescriptor(
                                id = v.id,
                                providerId = PROVIDER_ID,
                                displayName = v.displayName,
                                locale = resolvedLocale
                            )
                        )
                    }
                }
            }
            result.distinctBy {
                "${it.id}|${it.locale.toLanguageTag()}"
            }
        }

    private fun inferVoiceLocale(
        voice: AcapelaVoiceManifest.VoiceInfo,
        fallback: Locale
    ): Locale {
        val combined =
            "${voice.voiceName} ${voice.id} ${voice.rawSpeaker.orEmpty()}"
        val tag = when {
            combined.contains("mariam", ignoreCase = true) ||
                combined.contains("salma", ignoreCase = true) ||
                combined.contains("leila", ignoreCase = true) ||
                combined.contains("layla", ignoreCase = true) ||
                combined.contains("tarik", ignoreCase = true) ||
                combined.contains("mehdi", ignoreCase = true) ||
                combined.contains("nizar", ignoreCase = true) ->
                com.aymankhattab.nateq.util.LanguageCode.AR.tag
            else -> fallback.toLanguageTag().takeIf {
                fallback.language.isNotBlank()
            } ?: com.aymankhattab.nateq.util.LanguageCode.AR.tag
        }
        return runCatching { Locale.forLanguageTag(tag) }
            .getOrDefault(
                Locale.forLanguageTag(
                    com.aymankhattab.nateq.util.LanguageCode.AR.tag
                )
            )
    }

    @Volatile
    private var crashRetryDone = false

    private suspend fun ensureInitialized(): Boolean {
        val base = resolveDataDir() ?: return false
        val engineId = HostProtocol.ENGINE_ACAPELA
        val dir = base.absolutePath
        val res = EngineHostClient.init(
            context = context,
            engineId = engineId,
            dataDir = dir
        )
        if (res.ok) {
            EngineStatusRegistry.noteReady(PROVIDER_ID)
            return true
        }
        if (res.code == "CRASHED_LAST_RUN" && !crashRetryDone) {
            crashRetryDone = true
            Log.w(TAG, "شفاء Acapela بعد انهيار سابق: إعادة قسرية")
            val retried = EngineHostClient.init(
                context = context,
                engineId = engineId,
                dataDir = dir,
                force = true
            )
            if (retried.ok) {
                EngineStatusRegistry.noteReady(PROVIDER_ID)
                return true
            }
            EngineStatusRegistry.noteFailure(
                PROVIDER_ID,
                retried.code,
                retried.message
            )
            return false
        }
        EngineStatusRegistry.noteFailure(PROVIDER_ID, res.code, res.message)
        return false
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
                com.aymankhattab.nateq.core.audio.engine
                    .SpeechFallbackTracker.noteAcapelaFailure(
                        reason = "تعذر تهيئة مضيف Acapela",
                        substituteVoice = null,
                        substituteEngine = null
                    )
                return@withContext
            }

            val targetVoiceName = (desiredVoiceName ?: voice.id)
                .removePrefix("acapela_embedded:")
                .removePrefix("acapela:")
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
                com.aymankhattab.nateq.core.audio.engine
                    .SpeechFallbackTracker.noteAcapelaFailure(
                        reason = "فشل نطق Acapela [${result.code}]: " +
                            (result.message ?: ""),
                        substituteVoice = null,
                        substituteEngine = null
                    )
                EngineStatusRegistry.noteFailure(
                    PROVIDER_ID,
                    result.code,
                    result.message
                )
                EngineStatusRegistry.noteSpeakFailure(
                    PROVIDER_ID,
                    result.code,
                    result.message
                )
            } else {
                EngineStatusRegistry.noteSpeakSuccess(PROVIDER_ID)
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
