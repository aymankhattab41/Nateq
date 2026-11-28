package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Looper
import android.util.Log
import com.aymankhattab.nateq.core.audio.announcement.LoudnessStage
import com.aymankhattab.nateq.core.audio.engine.SalmaSpeechTracker
import com.aymankhattab.nateq.core.audio.enginehost.AcapelaHostSession
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.enginehost.HostWarmStatusRegistry
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    @Volatile
    private var cachedResolvedDir: File? = null
    @Volatile
    private var lastResolvedDirRevision: Long = -1L

    fun resolveDataDir(): File? {
        if (customVoiceDir != null) return customVoiceDir
        val curRev = AcapelaVoiceRepository.getStorageRevision()
        val cached = cachedResolvedDir
        if (cached != null &&
            lastResolvedDirRevision == curRev &&
            cached.isDirectory
        ) {
            return cached
        }
        val snapshot = AcapelaVoiceRepository.getSnapshot()
        if (snapshot.isLoaded && snapshot.searchDirs.isNotEmpty()) {
            val candidate = snapshot.searchDirs.firstOrNull { dir ->
                snapshot.voices.any {
                    it.dir.canonicalPath.startsWith(dir.canonicalPath)
                }
            } ?: snapshot.searchDirs.firstOrNull()
            if (candidate != null) {
                AcapelaVoiceImporter.ensureValidConfigYamlForVoices(
                    context, candidate
                )
                cachedResolvedDir = candidate
                lastResolvedDirRevision = curRev
                return candidate
            }
        }
        val base = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        val resolved = if (AcapelaVoiceManifest.isCompleteVoiceDir(base) ||
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
        if (resolved != null) {
            AcapelaVoiceImporter.ensureValidConfigYamlForVoices(
                context, resolved
            )
        }
        cachedResolvedDir = resolved
        lastResolvedDirRevision = curRev
        return resolved
    }

    override fun isConfigured(): Boolean {
        if (customVoiceDir == null) {
            val snapshot = AcapelaVoiceRepository.getSnapshot()
            if (!snapshot.isLoaded) {
                AcapelaVoiceRepository.triggerBackgroundRefresh(context)
            }
            return snapshot.isConfigured
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AcapelaVoiceRepository.getSnapshot().isConfigured
        }
        return EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.ACAPELA,
            listOfNotNull(customVoiceDir)
        ).isCompleteVoicePresent
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> =
        withContext(Dispatchers.IO) {
            val voices = if (customVoiceDir == null) {
                AcapelaVoiceRepository.getOrRefreshSnapshot(context).voices
            } else {
                val searchDirs = EmbeddedStorage.allSearchDirs(
                    context,
                    EmbeddedStorage.Engine.ACAPELA,
                    listOfNotNull(customVoiceDir)
                )
                searchDirs.flatMap { AcapelaVoiceManifest.cachedVoices(it) }
            }
            val result = mutableListOf<VoiceDescriptor>()

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
    private val initMutex = Mutex()

    private suspend fun ensureInitialized(): Boolean = initMutex.withLock {
        val base = resolveDataDir() ?: return@withLock false
        val engineId = HostProtocol.ENGINE_ACAPELA
        val dir = base.absolutePath
        val res = EngineHostClient.init(
            context = context,
            engineId = engineId,
            dataDir = dir
        )
        if (res.ok) {
            EngineStatusRegistry.noteReady(PROVIDER_ID)
            return@withLock true
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
                return@withLock true
            }
            EngineStatusRegistry.noteFailure(
                PROVIDER_ID,
                retried.code,
                retried.message
            )
            return@withLock false
        }
        EngineStatusRegistry.noteFailure(PROVIDER_ID, res.code, res.message)
        return@withLock false
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
            val targetVoiceName = (desiredVoiceName ?: voice.id)
                .removePrefix("acapela_embedded:")
                .removePrefix("acapela:")
                .removePrefix("acapela_")
                .substringBefore('(')
                .trim()

            val isWarm = HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = HostProtocol.ENGINE_ACAPELA,
                voiceId = targetVoiceName,
                expectedRevision =
                    AcapelaVoiceRepository.getStorageRevision(context)
            ) || AcapelaHostSession.isWarmVoiceReady(targetVoiceName)

            val callCategory = when {
                category == "warmup" ->
                    SalmaSpeechTracker.Category.WARMUP
                category == "preview" || category == "diagnostic" ->
                    SalmaSpeechTracker.Category.DIAGNOSTIC
                isWarm ->
                    SalmaSpeechTracker.Category.WARM
                else -> SalmaSpeechTracker.Category.COLD
            }
            val tracker = SalmaSpeechTracker.startRequest(
                category = callCategory,
                voiceName = targetVoiceName,
                textLength = text.length,
                textHash = SalmaSpeechTracker.computeTextHash(text)
            )

            try {
                tracker.markQueueWaitStart()
                tracker.markEngineInitStart()
                val initOk = withTimeoutOrNull(5000L) {
                    ensureInitialized()
                } ?: false
                tracker.markEngineInitEnd()
                tracker.markQueueWaitEnd()

                if (!initOk) {
                    tracker.complete(
                        SalmaSpeechTracker.Status.FAILED,
                        "تأخرت تهيئة مضيف Acapela"
                    )
                    Log.w(
                        TAG,
                        "تأخرت تهيئة مضيف Acapela؛ تحويل آمن دون خطأ حرج"
                    )
                    com.aymankhattab.nateq.core.audio.engine
                        .SpeechFallbackTracker.noteAcapelaFailure(
                            reason = "تأخرت تهيئة مضيف Acapela",
                            substituteVoice = null,
                            substituteEngine = null
                        )
                    return@withContext
                }

                val isEvent = LoudnessStage.isEventCategory(category)
                val chunkConsumer: (ByteArray, Int) -> Unit = if (isEvent) {
                    val gainDb = runCatching {
                        SettingsRepository.create(context).getEventPcmGainDb()
                    }.getOrDefault(LoudnessStage.DEFAULT_GAIN_DB)
                    if (gainDb > 0f) {
                        { chunk, validLength ->
                            if (validLength > 0) {
                                tracker.onPcmChunkReceived(validLength)
                                LoudnessStage.processPcm16(
                                    chunk, 0, validLength, gainDb
                                )
                            }
                            onAudioChunk(chunk, validLength)
                        }
                    } else {
                        { chunk, validLength ->
                            if (validLength > 0) {
                                tracker.onPcmChunkReceived(validLength)
                            }
                            onAudioChunk(chunk, validLength)
                        }
                    }
                } else {
                    { chunk, validLength ->
                        if (validLength > 0) {
                            tracker.onPcmChunkReceived(validLength)
                        }
                        onAudioChunk(chunk, validLength)
                    }
                }

                tracker.markSpeakCallStart()
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
                tracker.markSpeakCallEnd()

                if (!result.ok) {
                    tracker.complete(
                        SalmaSpeechTracker.Status.FAILED,
                        result.message
                    )
                    Log.e(
                        TAG,
                        "نطق Acapela المدمج فشل [${result.code}]: " +
                            result.message
                    )
                    com.aymankhattab.nateq.core.audio.engine
                        .SpeechFallbackTracker.noteAcapelaFailure(
                            reason = "فشل نطق Acapela [${result.code}]: " +
                                (result.message ?: ""),
                            substituteVoice = null,
                            substituteEngine = null
                        )
                    EngineStatusRegistry.recordVoiceStatus(
                        engineId = PROVIDER_ID,
                        voiceId = voice.id,
                        isReady = false,
                        code = result.code,
                        errorMessage = result.message
                    )
                    EngineStatusRegistry.noteSpeakFailure(
                        PROVIDER_ID,
                        result.code,
                        result.message
                    )
                } else {
                    tracker.complete(SalmaSpeechTracker.Status.SUCCESS)
                    com.aymankhattab.nateq.core.audio.engine
                        .SpeechFallbackTracker.clearAcapelaFallbackState()
                    EngineStatusRegistry.recordVoiceStatus(
                        engineId = PROVIDER_ID,
                        voiceId = voice.id,
                        isReady = true
                    )
                    EngineStatusRegistry.noteReady(PROVIDER_ID)
                    EngineStatusRegistry.noteSpeakSuccess(PROVIDER_ID)
                }
            } finally {
                if (!tracker.isCompletedRequest) {
                    tracker.complete(
                        SalmaSpeechTracker.Status.CANCELLED,
                        "انتهى أو ألغي طلب Acapela"
                    )
                }
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
