package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.core.audio.engine.EnginePerformanceCounters
import com.aymankhattab.nateq.core.audio.providers.MicrosoftModelCatalog
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceRepository
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * جلسة محرك مايكروسوفت المدمج (Microsoft Neural Embedded) داخل :engine_host.
 *
 * الخصائص:
 *  1. الاحتفاظ بمثيل دافئ من [SpeechSynthesizer] و [EmbeddedSpeechConfig]
 *     عبر العبارات المتتابعة لنفس الصوت لتفادي إعادة قراءة وتحميل أوزان ONNX.
 *  2. تخزين مؤقت لمفتاح الترخيص واسم النموذج لتجنب فحص القرص المتكرر.
 *  3. إفراغ وإغلاق آمن للموارد الأصلية عند تبديل الصوت أو الإغلاق.
 */
internal class MicrosoftHostSession(
    private val context: Context
) : HostEngineSession {

    companion object {
        private const val TAG = "MsHostSession"
        private const val SAMPLE_RATE_HZ = 24000

        @Volatile
        private var activeSession: MicrosoftHostSession? = null

        @Volatile
        private var warmVoiceKey: String? = null

        fun isWarmReady(voiceId: String): Boolean {
            val session = activeSession
            if (session != null) {
                val warm = session.warmSession
                if (warm != null) {
                    val clean = voiceId.trim()
                    if (warm.key.contains(clean, ignoreCase = true) ||
                        clean.contains(
                            warm.key.substringAfter('|'),
                            ignoreCase = true
                        )
                    ) {
                        return true
                    }
                }
            }
            val warm = warmVoiceKey ?: return false
            val clean = voiceId.trim()
            return warm.equals(clean, ignoreCase = true) ||
                warm.contains(clean, ignoreCase = true) ||
                clean.contains(warm, ignoreCase = true)
        }

        internal fun noteWarmVoiceLoaded(voiceId: String) {
            warmVoiceKey = voiceId.trim()
        }

        internal fun noteWarmVoiceClosed() {
            warmVoiceKey = null
        }

        fun trimResources() {
            activeSession?.trimResources()
            noteWarmVoiceClosed()
        }
    }

    private data class WarmSession(
        val key: String,
        val config: EmbeddedSpeechConfig,
        val synthesizer: SpeechSynthesizer,
        val lastUsedRealtimeMs: Long
    )

    private var baseDataDir: File? = null
    @Volatile private var activeSynthesizer: SpeechSynthesizer? = null
    @Volatile private var warmSession: WarmSession? = null
    @Volatile private var boundRevision: Long = -1L
    private val activeSynthInUse =
        AtomicReference<SpeechSynthesizer?>(null)
    private val pendingDeferredCloseSynthesizer =
        AtomicReference<SpeechSynthesizer?>(null)
    private val sessionLock = Any()

    private val voiceDirCache = ConcurrentHashMap<String, File>()
    private val licenseKeyCache = ConcurrentHashMap<String, String>()
    private val modelNameCache = ConcurrentHashMap<String, String>()

    override fun init(dataDir: String): HostResult {
        activeSession = this
        val dir = File(dataDir)
        baseDataDir = dir
        val currentRev = MicrosoftVoiceRepository.getStorageRevision(context)
        synchronized(sessionLock) {
            if (boundRevision != -1L && boundRevision != currentRev) {
                closeWarmSessionLocked()
                voiceDirCache.clear()
                licenseKeyCache.clear()
                modelNameCache.clear()
            }
            boundRevision = currentRev
        }
        HostLog.i(
            HostProtocol.ENGINE_MICROSOFT,
            "تهيئة مايكروسوفت في: $dataDir"
        )
        return HostResult(ok = true)
    }

    override suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult = withContext(Dispatchers.IO) {
        if (cancelled.get()) {
            return@withContext HostResult.failure("CANCELLED", "أُلغي الطلب")
        }

        val base = baseDataDir ?: return@withContext HostResult.failure(
            "NOT_INITIALIZED",
            "لم يتم تهيئة مجلد بيانات مايكروسوفت"
        )

        val voiceDir = resolveVoiceDirectory(base, req.voiceId)
            ?: return@withContext HostResult.failure(
                "VOICE_NOT_FOUND",
                "تعذر العثور على مجلد الصوت: ${req.voiceId}"
            )

        val dirPath = voiceDir.absolutePath
        val cachedLic = licenseKeyCache[dirPath]
        val licenseKey = if (cachedLic != null && cachedLic.isNotBlank()) {
            EnginePerformanceCounters.microsoft.licenseCacheHits
                .incrementAndGet()
            cachedLic
        } else {
            EnginePerformanceCounters.microsoft.licenseCacheMisses
                .incrementAndGet()
            val lic = MicrosoftLicenseExtractor.extractLicense(voiceDir)
            if (lic.isNotBlank()) {
                licenseKeyCache[dirPath] = lic
            }
            lic
        }

        if (licenseKey.isBlank()) {
            return@withContext HostResult.failure(
                "LICENSE_MISSING",
                "مفتاح ترخيص النموذج مفقود في am_v5_decoder.bin"
            )
        }

        val cachedModel = modelNameCache[dirPath]
        val extractedModel = if (cachedModel != null) {
            EnginePerformanceCounters.microsoft.modelNameCacheHits
                .incrementAndGet()
            cachedModel
        } else {
            EnginePerformanceCounters.microsoft.modelNameCacheMisses
                .incrementAndGet()
            val m = extractModelName(voiceDir)
            if (!m.isNullOrBlank()) {
                modelNameCache[dirPath] = m
            }
            m
        }

        val catalogItem = MicrosoftModelCatalog.findItemByNameOrModel(
            context,
            req.voiceId
        )
        val modelName = extractedModel
            ?: catalogItem?.modelName
            ?: req.voiceId
        val langTag = catalogItem?.locale?.toLanguageTag()
            ?: Regex("""\(([a-zA-Z]{2,3}-[a-zA-Z]{2,3})""")
                .find(modelName)?.groupValues?.get(1)
            ?: "ar-EG"

        val warmKey = "$dirPath|$modelName"
        var synthesizer: SpeechSynthesizer? = null
        var config: EmbeddedSpeechConfig? = null
        var isWarmHit = false

        val currentRev = MicrosoftVoiceRepository.getStorageRevision(context)
        synchronized(sessionLock) {
            val warm = warmSession
            if (warm != null &&
                (boundRevision != currentRev ||
                    (warm.lastUsedRealtimeMs > 0 &&
                        voiceDir.lastModified() > warm.lastUsedRealtimeMs))
            ) {
                closeWarmSessionLocked()
                voiceDirCache.clear()
                licenseKeyCache.clear()
                modelNameCache.clear()
            }
            boundRevision = currentRev

            val freshWarm = warmSession
            if (freshWarm != null && freshWarm.key == warmKey) {
                EnginePerformanceCounters.microsoft.synthWarmHits
                    .incrementAndGet()
                synthesizer = freshWarm.synthesizer
                config = freshWarm.config
                isWarmHit = true
                warmSession = freshWarm.copy(
                    lastUsedRealtimeMs = SystemClock.elapsedRealtime()
                )
            } else {
                EnginePerformanceCounters.microsoft.synthWarmMisses
                    .incrementAndGet()
                closeWarmSessionLocked()

                try {
                    EnginePerformanceCounters.microsoft.engineInitCount
                        .incrementAndGet()
                    val newConfig = EmbeddedSpeechConfig.fromPaths(
                        listOf(dirPath)
                    )
                    newConfig.setSpeechSynthesisVoice(modelName, licenseKey)
                    newConfig.setProperty(
                        PropertyId.SpeechServiceConnection_SynthModelKey,
                        licenseKey
                    )
                    newConfig.setSpeechSynthesisOutputFormat(
                        SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm
                    )

                    // تمرير `null` كـ AudioConfig مقصود لعدم إخراج الصوت مرتين
                    val newSynth = SpeechSynthesizer(
                        newConfig,
                        null as AudioConfig?
                    )
                    config = newConfig
                    synthesizer = newSynth
                    warmSession = WarmSession(
                        key = warmKey,
                        config = newConfig,
                        synthesizer = newSynth,
                        lastUsedRealtimeMs = SystemClock.elapsedRealtime()
                    )
                    noteWarmVoiceLoaded(modelName)
                } catch (t: Throwable) {
                    Log.e(TAG, "فشل إنشاء جلسة مايكروسوفت الدافئة", t)
                    return@withContext HostResult.failure(
                        "INIT_FAILED",
                        t.message ?: "Failed to create synthesizer"
                    )
                }
            }
        }

        val activeSynth = synthesizer ?: return@withContext HostResult.failure(
            "SYNTH_UNAVAILABLE",
            "محرك مايكروسوفت غير متاح"
        )
        activeSynthesizer = activeSynth
        activeSynthInUse.set(activeSynth)

        var synthResult: SpeechSynthesisResult? = null

        try {
            val isSsml = req.text.trimStart()
                .startsWith("<speak", ignoreCase = true)
            synthResult = if (isSsml) {
                activeSynth.SpeakSsml(req.text)
            } else {
                val ssml = MicrosoftSsml.build(
                    text = req.text,
                    modelName = modelName,
                    langTag = langTag,
                    rate = req.rate
                )
                val res = activeSynth.SpeakSsml(ssml)
                if (res.reason == ResultReason.Canceled) {
                    val cancelDetails = SpeechSynthesisCancellationDetails
                        .fromResult(res)
                    Log.w(
                        TAG,
                        "SpeakSsml canceled: [${cancelDetails.errorCode}] " +
                            "${cancelDetails.errorDetails}; trying SpeakText"
                    )
                    runCatching { res.close() }
                    activeSynth.SpeakText(req.text)
                } else {
                    res
                }
            }
            activeSynthesizer = null

            val reason = synthResult.reason
            when (reason) {
                ResultReason.SynthesizingAudioCompleted -> {
                    val pcm = synthResult.audioData
                    if (pcm != null && pcm.isNotEmpty()) {
                        sink.writeHeader(SAMPLE_RATE_HZ, 1)
                        val chunkSize = 16384
                        var offset = 0
                        while (offset < pcm.size && !cancelled.get()) {
                            val len = minOf(chunkSize, pcm.size - offset)
                            val chunk = pcm.copyOfRange(offset, offset + len)
                            sink.writePcm(chunk, len)
                            offset += len
                        }
                        HostWarmStatusRegistry.recordWarmVoice(
                            context = context,
                            engineId = HostProtocol.ENGINE_MICROSOFT,
                            actualVoiceId = modelName,
                            dataRevision = boundRevision,
                            hostGeneration = 1L,
                            pcmSuccess = true
                        )
                        HostResult(ok = true)
                    } else {
                        HostResult.failure(
                            "EMPTY_PCM",
                            "لم يتم توليد أي بيانات صوتية"
                        )
                    }
                }
                ResultReason.Canceled -> {
                    val details = SpeechSynthesisCancellationDetails
                        .fromResult(synthResult)
                    Log.w(
                        TAG,
                        "Synthesis canceled: [${details.errorCode}] " +
                            "${details.errorDetails}"
                    )
                    HostResult.failure(
                        "SYNTH_CANCELED",
                        details.errorDetails ?: "Cancelled"
                    )
                }
                else -> {
                    Log.w(TAG, "Synthesis failed with reason: $reason")
                    HostResult.failure("SYNTH_FAILED", reason.toString())
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "فشل تخليق صوت مايكروسوفت", t)
            HostResult.failure("SYNTH_EXCEPTION", t.message ?: "Unknown error")
        } finally {
            activeSynthesizer = null
            activeSynthInUse.set(null)
            runCatching { synthResult?.close() }
            pendingDeferredCloseSynthesizer.getAndSet(null)?.let {
                runCatching { it.close() }
            }
            if (!isWarmHit && warmSession?.synthesizer != activeSynth) {
                runCatching { activeSynth.close() }
                runCatching { config?.close() }
            }
        }
    }

    override fun stop() {
        runCatching {
            activeSynthesizer?.StopSpeakingAsync()
        }
    }

    private fun closeWarmSessionLocked() {
        val warm = warmSession
        warmSession = null
        noteWarmVoiceClosed()
        HostWarmStatusRegistry.clearWarmStatus(
            context,
            HostProtocol.ENGINE_MICROSOFT
        )
        if (warm != null) {
            EnginePerformanceCounters.microsoft.serviceCloseCount
                .incrementAndGet()
            if (activeSynthInUse.get() === warm.synthesizer) {
                pendingDeferredCloseSynthesizer.set(warm.synthesizer)
                runCatching { warm.config.close() }
            } else {
                runCatching { warm.synthesizer.close() }
                runCatching { warm.config.close() }
            }
        }
    }

    fun closeWarmSynthesizer() {
        synchronized(sessionLock) {
            closeWarmSessionLocked()
        }
    }

    fun trimResources() {
        synchronized(sessionLock) {
            closeWarmSessionLocked()
            voiceDirCache.clear()
            licenseKeyCache.clear()
            modelNameCache.clear()
        }
        HostWarmStatusRegistry.clearWarmStatus(
            context,
            HostProtocol.ENGINE_MICROSOFT
        )
    }

    @VisibleForTesting
    internal fun isWarmReady(voiceDirPath: String, modelName: String): Boolean {
        val warm = warmSession
        return warm != null && warm.key == "$voiceDirPath|$modelName"
    }

    override fun shutdown() {
        stop()
        trimResources()
        activeSynthesizer = null
        if (activeSession === this) {
            activeSession = null
        }
    }

    private fun resolveVoiceDirectory(
        base: File,
        voiceId: String
    ): File? {
        val cached = voiceDirCache[voiceId]
        if (cached != null && cached.exists() && cached.isDirectory) {
            return cached
        }
        val completeDirs = MicrosoftVoiceManifest.completeVoiceDirs(base)
        if (completeDirs.isEmpty()) return null

        val exactMatch = completeDirs.firstOrNull {
            it.name.equals(voiceId, ignoreCase = true)
        }
        if (exactMatch != null) {
            voiceDirCache[voiceId] = exactMatch
            return exactMatch
        }

        val q = voiceId.trim()
        val partial = completeDirs.firstOrNull {
            it.name.contains(q, ignoreCase = true) ||
                q.contains(it.name, ignoreCase = true)
        }
        if (partial != null) {
            voiceDirCache[voiceId] = partial
            return partial
        }

        val item = MicrosoftModelCatalog.findItemByNameOrModel(context, q)
        if (item != null) {
            val fromItem = MicrosoftModelCatalog.findInstalledVoiceDir(
                base,
                item
            )
            if (fromItem != null) {
                voiceDirCache[voiceId] = fromItem
                return fromItem
            }
        }

        val fallback = completeDirs.firstOrNull()
        if (fallback != null) {
            voiceDirCache[voiceId] = fallback
        }
        return fallback
    }

    private fun extractModelName(voiceDir: File): String? {
        val directTokens = File(voiceDir, "Tokens.xml")
        val tokensFile = if (directTokens.exists() && directTokens.isFile) {
            directTokens
        } else {
            voiceDir.walkTopDown().maxDepth(3).firstOrNull {
                it.name.equals("Tokens.xml", ignoreCase = true)
            }
        } ?: return null
        return runCatching {
            val content = tokensFile.readText(Charsets.UTF_8)
            val pattern = java.util.regex.Pattern.compile(
                "<String\\s+name=\"\"\\s+value=\"([^\"]+)\""
            )
            val matcher = pattern.matcher(content)
            if (matcher.find()) matcher.group(1) else null
        }.getOrNull()
    }
}
