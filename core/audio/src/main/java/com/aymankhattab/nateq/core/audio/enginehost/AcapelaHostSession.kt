package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.acapelagroup.android.tts.acattsandroid
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.providers.AcapelaLicense
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

        internal fun collectVoiceSearchPaths(
            baseDir: File,
            extraDirs: List<File> = emptyList()
        ): Array<String> {
            val paths = LinkedHashSet<String>()
            val allBases = (listOf(baseDir) + extraDirs).filter {
                it.exists() && it.isDirectory
            }
            for (base in allBases) {
                paths.add(base.absolutePath)
                AcapelaVoiceManifest.ensureVoiceInfFiles(base)
                base.walkTopDown().maxDepth(6).forEach { f ->
                    if (f.isDirectory &&
                        AcapelaVoiceManifest.isCompleteVoiceDir(f)
                    ) {
                        paths.add(f.absolutePath)
                    } else if (f.isFile && f.extension.lowercase() in setOf(
                        "inf", "ini", "bvcu", "dat", "mdd", "pho", "bnf"
                    )) {
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

    private val headerWritten = AtomicBoolean(false)
    private val audioReceived = AtomicBoolean(false)
    private val speechCompleted = AtomicBoolean(false)
    private val lastAudioTime = AtomicLong(0L)

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
            val extraDirs = EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            val paths = collectVoiceSearchPaths(dir, extraDirs)
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

        headerWritten.set(false)
        audioReceived.set(false)
        speechCompleted.set(false)
        lastAudioTime.set(SystemClock.uptimeMillis())

        try {
            val cleanVoiceId = req.voiceId
                .removePrefix("acapela_")
                .substringBefore('(')
                .trim()
            val cleanSpeaker = AcapelaVoiceManifest
                .cleanAcapelaSpeaker(cleanVoiceId)

            val dir = dataDirectory
            val voiceInfo = dir?.let {
                AcapelaVoiceManifest.listVoices(it).firstOrNull { v ->
                    v.id.equals(cleanSpeaker, ignoreCase = true) ||
                        v.voiceName.equals(cleanSpeaker, ignoreCase = true) ||
                        v.id.equals(cleanVoiceId, ignoreCase = true) ||
                        v.voiceName.equals(cleanVoiceId, ignoreCase = true) ||
                        v.displayName.startsWith(
                            cleanSpeaker,
                            ignoreCase = true
                        )
                }
            }
            val targetName = voiceInfo?.voiceName ?: cleanSpeaker
            val candidates = listOfNotNull(
                targetName,
                cleanSpeaker,
                cleanVoiceId,
                cleanSpeaker.lowercase(),
                cleanSpeaker.uppercase()
            ).distinct()

            if (currentVoiceId != targetName &&
                currentVoiceId != cleanSpeaker
            ) {
                var loadRes = -1
                var loadedName: String? = null
                for (cand in candidates) {
                    loadRes = inst.nLoadVoice(cand)
                    if (loadRes == 0) {
                        loadedName = cand
                        break
                    }
                }
                if (loadRes != 0 && dir != null) {
                    val extraDirs = EmbeddedStorage.allSearchDirs(
                        context,
                        EmbeddedStorage.Engine.ACAPELA
                    )
                    val paths = collectVoiceSearchPaths(dir, extraDirs)
                    inst.nGetVoiceList(paths)
                    for (cand in candidates) {
                        loadRes = inst.nLoadVoice(cand)
                        if (loadRes == 0) {
                            loadedName = cand
                            break
                        }
                    }
                }
                if (loadRes == 0) {
                    currentVoiceId = loadedName ?: targetName
                    if (voiceInfo != null && voiceInfo.sampleRate > 0) {
                        currentSampleRate = voiceInfo.sampleRate
                    }
                } else {
                    Log.e(
                        TAG,
                        "فشل تحميل صوت Acapela ($targetName): كود $loadRes"
                    )
                    sink.end(
                        HostProtocol.STATUS_ERROR,
                        "VOICE_LOAD_FAILED",
                        "تعذر تحميل الصوت: $targetName"
                    )
                    return HostResult.failure(
                        "VOICE_LOAD_FAILED",
                        "تعذر تحميل الصوت: $targetName"
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

            // بدء النطق (يتم تسليم العينات عبر samplesCallback غير المتزامنة)
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

            if (speakRes != 0) {
                sink.end(
                    HostProtocol.STATUS_ERROR,
                    "SPEAK_ERROR",
                    "فشل استدعاء nSpeak: $speakRes"
                )
                return HostResult.failure(
                    "SPEAK_ERROR",
                    "فشل استدعاء nSpeak: $speakRes"
                )
            }

            // انتظار اكتمال التخليق وبث العينات الصوتية بالكامل
            val startTime = SystemClock.uptimeMillis()
            val timeoutMs = SynthesisBudget.pieceTimeoutMs(req.text.length)
            while (!cancelled.get() && !speechCompleted.get()) {
                val now = SystemClock.uptimeMillis()
                if (now - startTime > timeoutMs) {
                    Log.w(TAG, "تجاوز مهلة تخليق Acapela (${timeoutMs}ms)")
                    break
                }
                if (audioReceived.get()) {
                    val speaking = runCatching {
                        inst.nIsSpeaking()
                    }.getOrDefault(0)
                    if (speaking == 0 && now - lastAudioTime.get() > 150L) {
                        break
                    }
                } else if (now - startTime > 2500L) {
                    val speaking = runCatching {
                        inst.nIsSpeaking()
                    }.getOrDefault(0)
                    if (speaking == 0) {
                        break
                    }
                }
                delay(15L)
            }

            // ضمان كتابة الترويسة إن لم يُستلم أي صوت لمنع تعليق العميل
            if (!headerWritten.get()) {
                sink.writeHeader(currentSampleRate, 1)
            }
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

        if (!headerWritten.getAndSet(true)) {
            sink.writeHeader(currentSampleRate, 1)
        }

        val byteBuffer = ByteBuffer.allocate(length * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            byteBuffer.putShort(samples[i])
        }
        val bytes = byteBuffer.array()

        sink.writePcm(bytes, bytes.size)
        audioReceived.set(true)
        lastAudioTime.set(SystemClock.uptimeMillis())
    }

    override fun onSpeechEvent(
        eventId: Long,
        p1: Long,
        p2: Long,
        p3: Long,
        p4: Long
    ): Long {
        if (eventId == 2L || eventId == 5L) {
            speechCompleted.set(true)
        }
        return 0L
    }

    override fun stop() {
        speechCompleted.set(true)
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
