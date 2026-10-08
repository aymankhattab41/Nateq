package com.aymankhattab.nateq.core.audio.enginehost

import android.speech.tts.SynthesisCallback
import android.util.Log
import com.acapelagroup.android.tts.AcattsandroidService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ع٢٩-5: جلسة Acapela TTS داخل `:engine_host`.
 *
 * منطقُ التهيئة والنطق مطابقٌ لما كان في
 * `AcapelaEmbeddedVoiceProvider` (نفس nInit ثم nSetCurrentVoice
 * والخصائص ثم nSpeak) — نُقل دون تغيير سلوك.
 */
internal class AcapelaHostSession : HostEngineSession {

    companion object {
        private const val TAG = "AcapelaHost"
    }

    private val engineLock = Any()

    /** قفل توليد واحد: محرّك واحد لا يتولّى نطقين معاً. */
    private val speakLock = Any()

    @Volatile
    private var service: AcattsandroidService? = null

    @Volatile
    private var stopRequested = false

    override fun init(dataDir: String): HostResult {
        synchronized(engineLock) {
            if (service != null) return HostProtocol.success()
            if (dataDir.isBlank()) {
                return HostResult.failure(
                    "NO_DATA_DIR",
                    "لا مجلد بيانات لمحرك Acapela"
                )
            }
            val s = AcattsandroidService()
            val res = try {
                s.nInit(dataDir)
            } catch (t: Throwable) {
                Log.e(TAG, "استثناء أثناء nInit: ${t.message}")
                -1
            }
            if (res < 0) {
                Log.e(TAG, "nInit=$res على $dataDir")
                return HostResult.failure(
                    "INIT_FAILED",
                    "تعذّرت تهيئة Acapela على $dataDir (nInit=$res)"
                )
            }
            service = s
            Log.i(TAG, "Acapela جاهز على $dataDir")
            return HostProtocol.success()
        }
    }

    override suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val s = service ?: return HostResult.failure(
            "NOT_INITIALIZED",
            "محرك Acapela غير مهيّأ"
        )
        synchronized(speakLock) {
            stopRequested = false

            val target = req.voiceId.removePrefix("acapela_")
            s.nSetCurrentVoice(target)

            val rateInt = (req.rate * 100).toInt().coerceIn(20, 400)
            val pitchInt = (req.pitch * 100).toInt().coerceIn(50, 200)
            val volumeInt = (req.volume * 100).toInt().coerceIn(0, 100)
            s.nSetProperty("Rate", rateInt.toString())
            s.nSetProperty("Pitch", pitchInt.toString())
            s.nSetProperty("Volume", volumeInt.toString())

            var started = false
            val pipeBroken = AtomicBoolean(false)
            val callback = object : SynthesisCallback {
                override fun getMaxBufferSize(): Int = 8192

                override fun start(
                    sampleRateInHz: Int,
                    audioFormat: Int,
                    channelCount: Int
                ): Int {
                    started = true
                    sink.writeHeader(sampleRateInHz, channelCount)
                    return 0
                }

                override fun audioAvailable(
                    buffer: ByteArray?,
                    offset: Int,
                    length: Int
                ): Int {
                    if (buffer == null || length <= 0) return 0
                    if (!started) {
                        started = true
                        sink.writeHeader(22050, 1)
                    }
                    if (cancelled.get() || stopRequested) return 0
                    val chunk = if (
                        offset == 0 && length == buffer.size
                    ) {
                        buffer
                    } else {
                        buffer.copyOfRange(offset, offset + length)
                    }
                    try {
                        sink.writePcm(chunk, chunk.size)
                    } catch (t: Throwable) {
                        // انغلق الأنبوب (مات العميل): أوقف التوليد
                        // بدل رمي استثناء داخل رد اتصال المحرّك.
                        pipeBroken.set(true)
                        stopRequested = true
                    }
                    return 0
                }

                override fun done(): Int = 0

                override fun error() {
                    Log.e(TAG, "Acapela أبلغ خطأً عاماً")
                }

                override fun error(errorCode: Int) {
                    Log.e(TAG, "Acapela أبلغ خطأ $errorCode")
                }

                override fun hasStarted(): Boolean = started

                override fun hasFinished(): Boolean = false
            }

            try {
                s.nSpeak(req.text, callback)
            } finally {
                runCatching { s.nStop() }
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
        val s = service ?: return
        runCatching { s.nStop() }
    }

    override fun shutdown() {
        synchronized(engineLock) {
            val s = service ?: return
            service = null
            runCatching { s.nShutdown() }
        }
    }
}
