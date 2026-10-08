package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.util.Log
import com.nuance.android.vocalizer.VocalizerAudioOutputListener
import com.nuance.android.vocalizer.VocalizerAudioSettings
import com.nuance.android.vocalizer.VocalizerEngine
import com.nuance.android.vocalizer.VocalizerVoice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ع٢٩-5: جلسة Nuance Vocalizer داخل `:engine_host`.
 *
 * منطقُ التهيئة والنطق مطابقٌ لما كان في
 * `VocalizerEmbeddedVoiceProvider` (نفس ترتيب loadVoice ثم
 * setSpeechRate/Pitch/Volume ثم speak) — نُقل دون تغيير سلوك؛
 * الفرق الوحيد أنه لم يعد يُستدعى في العملية الأمامية أبداً.
 */
internal class VocalizerHostSession(
    private val context: Context
) : HostEngineSession {

    companion object {
        private const val TAG = "VocalizerHost"
    }

    private val engineLock = Any()

    /** قفل توليد واحد: محرّك واحد لا يتولّى نطقين معاً. */
    private val speakLock = Any()

    @Volatile
    private var engine: VocalizerEngine? = null

    @Volatile
    private var stopRequested = false

    override fun init(dataDir: String): HostResult {
        synchronized(engineLock) {
            if (engine != null) return HostProtocol.success()
            if (dataDir.isBlank()) {
                return HostResult.failure(
                    "NO_DATA_DIR",
                    "لا مجلد بيانات لمحرك Vocalizer"
                )
            }
            val eng = VocalizerEngine(context)
            val initialized = try {
                eng.initialize(dataDir)
            } catch (t: Throwable) {
                Log.e(TAG, "استثناء أثناء initialize: ${t.message}")
                false
            }
            if (!initialized) {
                runCatching { eng.release() }
                Log.e(TAG, "initialize أعاد false على $dataDir")
                return HostResult.failure(
                    "INIT_FAILED",
                    "تعذّرت تهيئة Vocalizer على $dataDir"
                )
            }
            engine = eng
            Log.i(TAG, "Vocalizer جاهز على $dataDir")
            return HostProtocol.success()
        }
    }

    /** أصوات المحرك من المكتبة (يستلزم [init] ناجحاً أولاً). */
    fun listVoices(): List<HostVoice> {
        val eng = engine ?: return emptyList()
        val voices = try {
            eng.voiceList
        } catch (t: Throwable) {
            Log.e(TAG, "voiceList فشل: ${t.message}")
            return emptyList()
        }
        return voices?.mapNotNull { v ->
            val name = v.voiceName ?: return@mapNotNull null
            HostVoice(
                id = "vocalizer_${name.lowercase()}",
                name = name,
                langTag = v.language ?: ""
            )
        } ?: emptyList()
    }

    override suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val eng = engine ?: return HostResult.failure(
            "NOT_INITIALIZED",
            "محرك Vocalizer غير مهيّأ"
        )
        synchronized(speakLock) {
            stopRequested = false

            val voices: Array<VocalizerVoice> = try {
                eng.voiceList ?: emptyArray()
            } catch (t: Throwable) {
                Log.e(TAG, "voiceList فشل: ${t.message}")
                return HostResult.failure(
                    "VOICE_LIST_FAILED",
                    "تعذّرت قراءة أصوات المحرك"
                )
            }

            val target = req.voiceId.removePrefix("vocalizer_")
            val matched = voices.firstOrNull {
                it.voiceName.equals(req.voiceId, ignoreCase = true) ||
                    it.voiceName.equals(target, ignoreCase = true)
            } ?: voices.firstOrNull()
            if (matched != null) eng.loadVoice(matched)

            val rateInt = (req.rate * 100).toInt().coerceIn(20, 400)
            val pitchInt = (req.pitch * 100).toInt().coerceIn(50, 200)
            val volumeInt = (req.volume * 100).toInt().coerceIn(0, 100)
            eng.setSpeechRate(rateInt)
            eng.setSpeechPitch(pitchInt)
            eng.setSpeechVolume(volumeInt)

            sink.writeHeader(matched?.sampleRate ?: 22050, 1)

            val pipeBroken = AtomicBoolean(false)
            eng.setAudioOutputListener(
                object : VocalizerAudioOutputListener {
                    override fun onAudioData(
                        settings: VocalizerAudioSettings?,
                        audioData: ShortArray?
                    ) {
                        if (audioData == null || audioData.isEmpty()) return
                        if (cancelled.get() || stopRequested) return
                        if (pipeBroken.get()) return
                        try {
                            sink.writePcm(
                                toLePcm(audioData),
                                audioData.size * 2
                            )
                        } catch (t: Throwable) {
                            // انغلق الأنبوب (مات العميل): أوقف التوليد
                            // بدل رمي استثناء داخل رد اتصال JNI.
                            pipeBroken.set(true)
                            stopRequested = true
                        }
                    }
                },
                true
            )

            try {
                eng.speak(req.text)
            } finally {
                runCatching { eng.stop() }
            }

            return when {
                pipeBroken.get() -> HostResult.failure(
                    "PIPE_CLOSED",
                    "انغلق أنبوب الصوت"
                )
                cancelled.get() -> HostResult.failure(
                    "CANCELLED",
                    "أُلغي طلب النطق"
                )
                stopRequested -> HostResult.failure(
                    "STOPPED",
                    "أُوقف التوليد"
                )
                else -> HostProtocol.success()
            }
        }
    }

    override fun stop() {
        stopRequested = true
        val eng = engine ?: return
        // stop() على نفس قفل speak: إن كان النطق جارياً ينتظر
        // رجوع speak (سلوك المكتبة) فلا يُقتطع صوتٌ في منتصفه.
        runCatching { eng.stop() }
    }

    override fun shutdown() {
        synchronized(engineLock) {
            val eng = engine ?: return
            engine = null
            runCatching { eng.release() }
        }
    }

    private fun toLePcm(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        return buffer.array()
    }
}
