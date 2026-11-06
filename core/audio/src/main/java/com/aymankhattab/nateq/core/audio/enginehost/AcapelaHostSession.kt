package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.util.Log
import com.acapelagroup.android.tts.acattsandroid
import com.aymankhattab.nateq.core.audio.providers.AcapelaLicense
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * جلسة محرك Acapela داخل عملية :engine_host المعزولة.
 */
internal class AcapelaHostSession(
    private val context: Context
) : HostEngineSession, acattsandroid.AudioListener {

    companion object {
        private const val TAG = "AcapelaHostSession"
        private const val DEFAULT_SAMPLE_RATE = 22050
    }

    private var acatts: acattsandroid? = null
    private var dataDirectory: File? = null

    @Volatile
    private var activeSink: PcmSink? = null

    @Volatile
    private var activeCancelled: AtomicBoolean? = null

    @Volatile
    private var currentSampleRate = DEFAULT_SAMPLE_RATE

    @Volatile
    private var isSpeaking = false

    override fun init(dataDir: String): HostResult {
        return try {
            val dir = File(dataDir)
            dataDirectory = dir
            if (!dir.exists()) {
                return HostResult.failure(
                    "DIR_NOT_FOUND",
                    "مجلد بيانات Acapela غير موجود: ${dir.absolutePath}"
                )
            }

            // تحميل المكتبة الأصلية مع معالجة غيابها بسلاسة
            try {
                System.loadLibrary("acattsandroid")
            } catch (e: Throwable) {
                Log.e(TAG, "فشل تحميل libacattsandroid.so: ${e.message}")
                return HostResult.failure(
                    "NATIVE_LOAD_ERROR",
                    "تعذر تحميل مكتبة Acapela الأصلية: ${e.message}"
                )
            }

            val inst = acattsandroid()
            inst.setAudioListener(this)
            val resInit = inst.nInitCallbacks()
            if (resInit != 0) {
                Log.w(TAG, "nInitCallbacks returned $resInit")
            }
            inst.nSetLog(false)
            val licRes = inst.nSetLicense(AcapelaLicense.LICENSE)
            if (licRes != 0) {
                Log.w(TAG, "nSetLicense returned $licRes")
            }
            inst.nGetVoiceList(arrayOf(dir.absolutePath))
            acatts = inst
            HostResult(ok = true)
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء تهيئة محرك Acapela", t)
            HostResult.failure(
                "INIT_EXCEPTION",
                t.message ?: "خطأ أثناء تهيئة Acapela"
            )
        }
    }

    /**
     * سرد الأصوات المتوفرة لـ Acapela من القرص والمحرك.
     */
    fun listVoices(): List<HostVoice> {
        val dir = dataDirectory ?: return emptyList()
        val manifestVoices = AcapelaVoiceManifest.listVoices(dir)
        return manifestVoices.map { v ->
            HostVoice(
                id = v.id,
                name = v.voiceName,
                langTag = v.locale.toLanguageTag()
            )
        }
    }

    override suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val inst = acatts ?: return HostResult.failure(
            "NOT_INITIALIZED",
            "محرك Acapela غير مهيأ"
        )
        if (cancelled.get()) {
            sink.end(HostProtocol.STATUS_OK, "CANCELLED", "")
            return HostResult(ok = true)
        }

        activeSink = sink
        activeCancelled = cancelled
        isSpeaking = true

        try {
            // تحميل الصوت المحدد
            val loadRes = inst.nLoadVoice(req.voiceId)
            if (loadRes != 0) {
                Log.w(TAG, "nLoadVoice(${req.voiceId}) returned $loadRes")
            }

            // ضبط سرعة النطق والشكل
            val rateFloat = (req.rate * 100f).coerceIn(50f, 400f)
            inst.nSetSpeechRate(rateFloat)
            inst.nSetShape(100f)

            // نطق النص (يتم بث العينات عبر samplesCallback)
            val speakRes = inst.nSpeak(req.text)
            if (speakRes != 0 && !cancelled.get()) {
                Log.w(TAG, "nSpeak returned $speakRes")
            }

            // كتابة نهاية التدفق بنجاح
            sink.writeHeader(currentSampleRate, 1)
            sink.end(HostProtocol.STATUS_OK, "OK", "")
            return HostResult(ok = true)
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء نطق Acapela", t)
            sink.end(
                HostProtocol.STATUS_ERROR,
                "SPEAK_ERROR",
                t.message ?: ""
            )
            return HostResult.failure(
                "SPEAK_ERROR",
                t.message ?: "خطأ أثناء النطق"
            )
        } finally {
            isSpeaking = false
            activeSink = null
            activeCancelled = null
        }
    }

    override fun onAudioAvailable(samples: ShortArray, length: Int) {
        val sink = activeSink ?: return
        val cancelled = activeCancelled
        if (cancelled != null && cancelled.get()) {
            stop()
            return
        }
        if (length <= 0) return

        val byteBuffer = ByteBuffer.allocate(length * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            byteBuffer.putShort(samples[i])
        }
        val bytes = byteBuffer.array()

        sink.writeHeader(currentSampleRate, 1)
        sink.writePcm(bytes, bytes.size)
    }

    override fun onSpeechEvent(
        eventId: Long,
        p1: Long,
        p2: Long,
        p3: Long,
        p4: Long
    ): Long {
        return 0L
    }

    override fun stop() {
        runCatching {
            acatts?.nStop()
        }
    }

    override fun shutdown() {
        stop()
        runCatching {
            acatts?.nShutdown()
        }
        acatts = null
        dataDirectory = null
    }
}
