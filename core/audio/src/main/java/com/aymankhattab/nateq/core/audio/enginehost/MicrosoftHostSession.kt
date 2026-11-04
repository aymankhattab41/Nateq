package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.providers.MicrosoftModelCatalog
import com.aymankhattab.nateq.core.audio.providers.MicrosoftVoiceManifest
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.PushAudioOutputStream
import com.microsoft.cognitiveservices.speech.audio.PushAudioOutputStreamCallback
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
    @Volatile private var activePushStream: PushAudioOutputStream? = null

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

        val catalogItem = MicrosoftModelCatalog.findItemByNameOrModel(
            context,
            req.voiceId
        )
        val modelName = catalogItem?.modelName ?: req.voiceId
        val langTag = catalogItem?.locale?.toLanguageTag() ?: "ar-EG"

        var config: EmbeddedSpeechConfig? = null
        var pushStream: PushAudioOutputStream? = null
        var audioConfig: AudioConfig? = null
        var synthesizer: SpeechSynthesizer? = null
        var synthResult: SpeechSynthesisResult? = null

        try {
            config = EmbeddedSpeechConfig.fromPaths(
                listOf(voiceDir.absolutePath)
            )
            config.setSpeechSynthesisVoice(modelName, licenseKey)
            config.setSpeechSynthesisOutputFormat(
                SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm
            )

            val pushCallback = object : PushAudioOutputStreamCallback() {
                override fun write(data: ByteArray): Int {
                    if (cancelled.get()) return data.size
                    sink.writeHeader(SAMPLE_RATE_HZ, 1)
                    sink.writePcm(data, data.size)
                    return data.size
                }

                override fun close() {}
            }

            pushStream = PushAudioOutputStream.create(pushCallback)
            activePushStream = pushStream
            audioConfig = AudioConfig.fromStreamOutput(pushStream)
            synthesizer = SpeechSynthesizer(config, audioConfig)
            activeSynthesizer = synthesizer

            val ssml = buildSsml(
                text = req.text,
                modelName = modelName,
                langTag = langTag,
                rate = req.rate,
                pitch = req.pitch
            )

            synthResult = synthesizer.SpeakSsml(ssml)
            activeSynthesizer = null
            activePushStream = null

            val reason = synthResult.reason
            when (reason) {
                ResultReason.SynthesizingAudioCompleted -> {
                    HostResult(ok = true)
                }
                ResultReason.Canceled -> {
                    val details = SpeechSynthesisCancellationDetails
                        .fromResult(synthResult)
                    Log.w(
                        TAG,
                        "Synthesis canceled: ${details.errorDetails}"
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
            activePushStream = null
            runCatching { synthResult?.close() }
            runCatching { synthesizer?.close() }
            runCatching { audioConfig?.close() }
            runCatching { pushStream?.close() }
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
        activePushStream = null
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

    private fun buildSsml(
        text: String,
        modelName: String,
        langTag: String,
        rate: Float,
        pitch: Float
    ): String {
        val ratePercent = ((rate - 1.0f) * 100f).toInt()
        val rateStr = if (ratePercent >= 0) {
            "+$ratePercent%"
        } else {
            "$ratePercent%"
        }
        val pitchPercent = ((pitch - 1.0f) * 100f).toInt()
        val pitchStr = if (pitchPercent >= 0) {
            "+$pitchPercent%"
        } else {
            "$pitchPercent%"
        }
        val escaped = escapeXml(text)

        val header = "<speak version=\"1.0\" " +
            "xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xml:lang=\"$langTag\">"
        return """
$header
    <voice name="$modelName">
        <prosody rate="$rateStr" pitch="$pitchStr">
            $escaped
        </prosody>
    </voice>
</speak>
        """.trimIndent()
    }

    private fun escapeXml(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
