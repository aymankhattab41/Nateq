package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.providers.MicrosoftModelCatalog
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceManifest
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * جلسة محرك مايكروسوفت المدمج (Microsoft Neural Embedded) داخل :engine_host.
 */
internal class MicrosoftHostSession(
    private val context: Context
) : HostEngineSession {

    companion object {
        private const val TAG = "MsHostSession"
        private const val SAMPLE_RATE_HZ = 24000
    }

    private var baseDataDir: File? = null
    @Volatile private var activeSynthesizer: SpeechSynthesizer? = null

    override fun init(dataDir: String): HostResult {
        val dir = File(dataDir)
        baseDataDir = dir
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

        val licenseKey = MicrosoftLicenseExtractor.extractLicense(voiceDir)
        if (licenseKey.isBlank()) {
            return@withContext HostResult.failure(
                "LICENSE_MISSING",
                "مفتاح ترخيص النموذج مفقود في am_v5_decoder.bin"
            )
        }

        val extractedModel = extractModelName(voiceDir)
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

        var config: EmbeddedSpeechConfig? = null
        var synthesizer: SpeechSynthesizer? = null
        var synthResult: SpeechSynthesisResult? = null

        try {
            config = EmbeddedSpeechConfig.fromPaths(
                listOf(voiceDir.absolutePath)
            )
            config.setSpeechSynthesisVoice(modelName, licenseKey)
            config.setProperty(
                PropertyId.SpeechServiceConnection_SynthModelKey,
                licenseKey
            )
            config.setSpeechSynthesisOutputFormat(
                SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm
            )

            // تمرير `null` كـ AudioConfig مقصود: البانية ذات المعامل الواحد
            // تُنشئ `AudioConfig.fromDefaultSpeakerOutput()` داخلياً فينطق
            // SDK إلى مكبّر الصوت، ثم نكتب نحن نفس PCM إلى AudioTrack
            // فنسمع العبارة مرتين (الازدواج المُبلَّغ عنه). غياب مخرج
            // الصوت يجعل SDK يكتفي بإرجاع `audioData` للتشغيل عندنا.
            synthesizer = SpeechSynthesizer(
                config,
                null as AudioConfig?
            )
            activeSynthesizer = synthesizer

            val isSsml = req.text.trimStart()
                .startsWith("<speak", ignoreCase = true)
            synthResult = if (isSsml) {
                synthesizer.SpeakSsml(req.text)
            } else {
                val ssml = MicrosoftSsml.build(
                    text = req.text,
                    modelName = modelName,
                    langTag = langTag,
                    rate = req.rate
                )
                val res = synthesizer.SpeakSsml(ssml)
                if (res.reason == ResultReason.Canceled) {
                    val cancelDetails = SpeechSynthesisCancellationDetails
                        .fromResult(res)
                    Log.w(
                        TAG,
                        "SpeakSsml canceled: [${cancelDetails.errorCode}] " +
                            "${cancelDetails.errorDetails}; trying SpeakText"
                    )
                    synthesizer.SpeakText(req.text)
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
            runCatching { synthResult?.close() }
            runCatching { synthesizer?.close() }
            runCatching { config?.close() }
        }
    }

    override fun stop() {
        runCatching {
            activeSynthesizer?.StopSpeakingAsync()
        }
    }

    override fun shutdown() {
        stop()
        activeSynthesizer = null
    }

    private fun resolveVoiceDirectory(
        base: File,
        voiceId: String
    ): File? {
        val completeDirs = MicrosoftVoiceManifest.completeVoiceDirs(base)
        if (completeDirs.isEmpty()) return null

        val exactMatch = completeDirs.firstOrNull {
            it.name.equals(voiceId, ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch

        val q = voiceId.trim()
        val partial = completeDirs.firstOrNull {
            it.name.contains(q, ignoreCase = true) ||
                q.contains(it.name, ignoreCase = true)
        }
        if (partial != null) return partial

        val item = MicrosoftModelCatalog.findItemByNameOrModel(context, q)
        if (item != null) {
            val fromItem = MicrosoftModelCatalog.findInstalledVoiceDir(
                base,
                item
            )
            if (fromItem != null) return fromItem
        }

        return completeDirs.firstOrNull()
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
