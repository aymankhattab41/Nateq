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
import kotlin.math.roundToInt

/**
 * جلسة محرك Acapela داخل عملية :engine_host المعزولة.
 */
internal class AcapelaHostSession(
    private val context: Context
) : HostEngineSession, acattsandroid.AudioListener {

    companion object {
        private const val TAG = "AcapelaHostSession"
        private const val DEFAULT_SAMPLE_RATE = 22050

        internal fun calculateSpeedPercent(rate: Float): Int =
            (rate * 100f).roundToInt().coerceIn(30, 400)

        internal fun calculatePitchPercent(pitch: Float): Int =
            (pitch * 100f).roundToInt().coerceIn(50, 200)

        internal fun formatAcapelaText(
            text: String,
            rate: Float,
            pitch: Float
        ): String {
            val speed = calculateSpeedPercent(rate)
            val p = calculatePitchPercent(pitch)
            return "\\rspd=$speed\\ \\vct=$p\\ $text"
        }

        internal fun collectVoiceSearchPaths(baseDir: File): Array<String> {
            val paths = mutableSetOf<String>()
            if (baseDir.exists() && baseDir.isDirectory) {
                paths.add(baseDir.absolutePath)
                baseDir.walkTopDown().maxDepth(6).forEach { f ->
                    if (f.isFile && (
                        f.extension.equals("inf", ignoreCase = true) ||
                        f.extension.equals("ini", ignoreCase = true))
                    ) {
                        f.parentFile?.absolutePath?.let { paths.add(it) }
                    }
                }
            }
            return paths.toTypedArray()
        }
    }

    private var acatts: acattsandroid? = null
    private var dataDirectory: File? = null

    @Volatile
    private var currentVoiceId: String? = null

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
            val licRes = inst.nSetLicense(
                AcapelaLicense.USER_ID,
                AcapelaLicense.PASSWORD,
                AcapelaLicense.LICENSE
            )
            if (licRes != 0) {
                Log.w(TAG, "nSetLicense returned $licRes")
            }
            val paths = collectVoiceSearchPaths(dir)
            val voiceCount = inst.nGetVoiceList(paths)
            Log.d(
                TAG,
                "nGetVoiceList: $voiceCount voice(s) across " +
                    "${paths.size} dir(s)"
            )
            acatts = inst
            currentVoiceId = null
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
            // تحميل الصوت المحدد إن لم يكن محملاً مسبقاً لتفادي إعادة القراءة
            if (currentVoiceId != req.voiceId) {
                var loadRes = inst.nLoadVoice(req.voiceId)
                if (loadRes != 0) {
                    val dir = dataDirectory
                    if (dir != null) {
                        val paths = collectVoiceSearchPaths(dir)
                        inst.nGetVoiceList(paths)
                        loadRes = inst.nLoadVoice(req.voiceId)
                    }
                }
                if (loadRes == 0) {
                    currentVoiceId = req.voiceId
                    val dir = dataDirectory
                    if (dir != null) {
                        val voiceInfo = AcapelaVoiceManifest.listVoices(dir)
                            .firstOrNull {
                                it.id.equals(
                                    req.voiceId,
                                    ignoreCase = true
                                ) || it.voiceName.equals(
                                    req.voiceId,
                                    ignoreCase = true
                                )
                            }
                        if (voiceInfo != null && voiceInfo.sampleRate > 0) {
                            currentSampleRate = voiceInfo.sampleRate
                        }
                    }
                } else {
                    Log.e(
                        TAG,
                        "فشل تحميل صوت Acapela (${req.voiceId}): كود $loadRes"
                    )
                    sink.end(
                        HostProtocol.STATUS_ERROR,
                        "VOICE_LOAD_FAILED",
                        "تعذر تحميل الصوت: ${req.voiceId}"
                    )
                    return HostResult.failure(
                        "VOICE_LOAD_FAILED",
                        "تعذر تحميل الصوت: ${req.voiceId}"
                    )
                }
            }

            // حساب نسبة السرعة (100 = 1.0x) والنبرة
            val speedPercent = calculateSpeedPercent(req.rate)
            val pitchPercent = calculatePitchPercent(req.pitch)

            // ضبط سرعة النطق والشكل مباشرة على مستوى المحرك عبر nSetSpeechRate
            inst.nSetSpeechRate(speedPercent.toFloat())
            val shapeFloat = pitchPercent.toFloat().coerceIn(70f, 130f)
            inst.nSetShape(shapeFloat)

            // نطق النص (السرعة والنبرة مطبقتان عبر nSetSpeechRate)
            var speakRes = inst.nSpeak(req.text)
            if (speakRes != 0 && !cancelled.get()) {
                Log.w(
                    TAG,
                    "nSpeak raw returned $speakRes, retrying with tags"
                )
                val tagged = formatAcapelaText(
                    req.text,
                    req.rate,
                    req.pitch
                )
                speakRes = inst.nSpeak(tagged)
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
        currentVoiceId = null
    }
}
