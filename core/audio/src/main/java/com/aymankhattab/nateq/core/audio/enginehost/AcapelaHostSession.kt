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
 * والخصائص ثم nSpeak) — نُقل دون تغيير سلوك؛
 * الفرق الوحيد أنه لم يعد يُستدعى في العملية الأمامية أبداً.
 * ع٢٩-7: المكتبة لا تستدعي دوال Context على كائن الخدمة
 * (مُتحقق في AcapelaJniContractTest).
 */
internal class AcapelaHostSession : HostEngineSession {

    companion object {
        private const val ENGINE_ID = HostProtocol.ENGINE_ACAPELA
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
            if (service != null) {
                HostLog.d(ENGINE_ID, "المحرك مهيّأ مسبقاً")
                return HostProtocol.success()
            }
            if (dataDir.isBlank()) {
                HostLog.w(ENGINE_ID, "مسار مجلد البيانات فارغ")
                return HostResult.failure(
                    "NO_DATA_DIR",
                    "لا مجلد بيانات لمحرك Acapela"
                )
            }
            HostLog.logInitDetails(
                ENGINE_ID,
                dataDir,
                mapOf(
                    "library" to "libttsacattsandroidextended.so"
                )
            )
            // ع٢٩-7: المكتب لا تستدعي دوال Context على كائن الخدمة
            val s = AcattsandroidService()
            val res = try {
                s.nInit(dataDir)
            } catch (t: Throwable) {
                HostLog.e(ENGINE_ID, "استثناء أثناء nInit: ${t.message}", t)
                -1
            }
            if (res < 0) {
                HostLog.e(ENGINE_ID, "nInit=$res على $dataDir")
                // ع٢٩-7: مطابقة أكواد الترخيص الخاصة بأكابيلا
                val code = when (res) {
                    -100 -> "LICENSE_EXPIRED"
                    -101 -> "LICENSE_INVALID"
                    -102 -> "LICENSE_MISSING"
                    -103 -> "LICENSE_DEVICE_LIMIT"
                    -104 -> "LICENSE_VERSION_MISMATCH"
                    else -> "INIT_FAILED"
                }
                val msg = when (code) {
                    "LICENSE_EXPIRED" -> "ترخيص Acapela منتهي الصلاحية"
                    "LICENSE_INVALID" -> "ترخيص Acapela غير صالح"
                    "LICENSE_MISSING" -> "ملف ترخيص Acapela مفقود (0x00*)"
                    "LICENSE_DEVICE_LIMIT" -> "تجاوز حد الأجهزة المرخصة"
                    "LICENSE_VERSION_MISMATCH" -> "عدم تطابق إصدار الترخيص"
                    else -> "تعذّرت تهيئة Acapela على $dataDir (nInit=$res)"
                }
                return HostResult.failure(code, msg)
            }
            service = s
            HostLog.i(ENGINE_ID, "Acapela جاهز على $dataDir")
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
                    HostLog.e(ENGINE_ID, "Acapela أبلغ خطأً عاماً")
                }

                override fun error(errorCode: Int) {
                    HostLog.e(ENGINE_ID, "Acapela أبلغ خطأ $errorCode")
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