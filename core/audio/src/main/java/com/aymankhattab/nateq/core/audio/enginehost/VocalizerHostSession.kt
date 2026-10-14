package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.SystemClock
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.nuance.android.vocalizer.VocalizerAudioOutputListener
import com.nuance.android.vocalizer.VocalizerAudioSettings
import com.nuance.android.vocalizer.VocalizerEngine
import com.nuance.android.vocalizer.VocalizerVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        private const val ENGINE_ID = HostProtocol.ENGINE_VOCALIZER

        /** مهلة تهيئة المحرك على خيط المحرك. */
        private const val INIT_TIMEOUT_MS = 30_000L

        /** مهلة تحرير المحرك عند الإغلاق. */
        private const val SHUTDOWN_TIMEOUT_MS = 10_000L

        /** الهرتزات في الكيلوهرتز الواحد. */
        private const val HZ_PER_KILO = 1000

        /** أعلى معدل صوت معقول يعود **بالكيلوهرتز** (22 لصوت 22.05kHz). */
        private const val MAX_VOICE_RATE_KHZ = 192

        /** أدنى معدل هرتز يقبله AudioTrack. */
        private const val MIN_SAMPLE_RATE_HZ = 8000

        /** أعلى معدل هرتز يقبله AudioTrack. */
        private const val MAX_SAMPLE_RATE_HZ = 192_000

        /** معدل الصوت الافتراضي عند غياب قيمة صالحة. */
        internal const val DEFAULT_OUTPUT_SAMPLE_RATE_HZ = 22050

        /**
         * يحوّل معدل عينات الصوت العائد من المحرّك إلى **الهرتز**.
         *
         * المحرّك يعيده **بالكيلوهرتز** (22 لصوت 22.05kHz) كسلوك SDK
         * الأصلي (`getSampleRate() * 1000`)؛ وبناء AudioTrack بمعدّل
         * كيلوهرتزيّ يرفضه النظام («Invalid sample rate»). ويُحتاط
         * لقيمةٍ بالهرتز أصلاً (22050) فلا تُضاعف خطأً، والقيمة
         * الشاذّة (سالب/صفر/خارج مدى AudioTrack) تُستبدل بالافتراضي.
         */
        internal fun outputSampleRateHz(voiceSampleRate: Int): Int = when {
            voiceSampleRate in 1..MAX_VOICE_RATE_KHZ ->
                voiceSampleRate * HZ_PER_KILO
            voiceSampleRate in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ ->
                voiceSampleRate
            else -> DEFAULT_OUTPUT_SAMPLE_RATE_HZ
        }

        /**
         * مطابقة اسم الصوت المطلوب باسم صوت المحرك (ع٢٩-10 بند 3):
         * تُرفع بادئة `vocalizer_` عن الطرفين. **لا تنازل إلى أول
         * قائمة إن لم يُطابق** — اسم غير معروف يبقى بلا مطابقة
         * فيُعلَن خطأً صريحاً لا صوتٌ آخر يخفي الفشل.
         */
        internal fun matchesVoiceName(
            engineName: String?,
            requestedId: String
        ): Boolean {
            if (engineName.isNullOrBlank()) return false
            val engine = engineName.removePrefix("vocalizer_")
            val requested = requestedId.removePrefix("vocalizer_")
            return engine.equals(requested, ignoreCase = true)
        }
    }

    private val engineLock = Any()

    /**
     * خيط المحرك الواحد (ع٣٩-1): الإنشاء والتهيئة وتحميل الصوت والنطق
     * كلها عليه — ثبات خيط SDK شرطٌ لنجاح `loadVoice` بعد `initialize`.
     */
    private val engineThread = EngineThread(ENGINE_ID, "voc-engine")

    /** قفل توليد واحد: محرّك واحد لا يتولّى نطقين معاً. */
    private val speakLock = Any()

    @Volatile
    private var engine: VocalizerEngine? = null

    @Volatile
    private var stopRequested = false

    override fun init(dataDir: String): HostResult {
        synchronized(engineLock) {
            if (engine != null) {
                HostLog.d(ENGINE_ID, "المحرك مهيّأ مسبقاً")
                return HostProtocol.success()
            }
            if (dataDir.isBlank()) {
                HostLog.w(ENGINE_ID, "مسار مجلد البيانات فارغ")
                return HostResult.failure(
                    "NO_DATA_DIR",
                    "لا مجلد بيانات لمحرك Vocalizer"
                )
            }
            return try {
                engineThread.submit(INIT_TIMEOUT_MS, "init") {
                    initializeOnEngineThread(dataDir)
                }
            } catch (e: EngineTaskTimeoutException) {
                HostLog.e(
                    ENGINE_ID,
                    "انتهت مهلة التهيئة: ${e.message}"
                )
                HostResult.failure(
                    "INIT_TIMEOUT",
                    "انتهت مهلة تهيئة Vocalizer"
                )
            } catch (t: Throwable) {
                HostLog.e(
                    ENGINE_ID,
                    "استثناء أثناء التهيئة: ${t.message}",
                    t
                )
                HostResult.failure(
                    "INIT_FAILED",
                    "تعذّرت تهيئة Vocalizer: ${t.message}"
                )
            }
        }
    }

    /**
     * تهيئة Vocalizer **على خيط المحرك**: الإنشاء ثم فحص الكائن
     * الأصلي ثم قراءة الترويسات ثم `initialize` — كلها على الخيط
     * الذي سيحمّل الصوت وينطق لاحقاً.
     */
    private fun initializeOnEngineThread(dataDir: String): HostResult {
        val eng = VocalizerEngine(context)
        val createErr = eng.createError
        HostLog.logInitDetails(
            ENGINE_ID,
            dataDir,
            mapOf(
                "mNativeEnginePtr" to
                    "0x${eng.nativeEnginePtr.toString(16)}",
                "isNativeCreated" to eng.isNativeCreated(),
                "createError" to createErr?.let {
                    "${it.javaClass.simpleName}: ${it.message}"
                },
                "library" to "libNuanceVocalizer.so"
            )
        )
        if (!eng.isNativeCreated()) {
            val err = eng.createError
            val detail = if (err != null) {
                val msg = err.message
                if (!msg.isNullOrBlank()) {
                    "${err.javaClass.simpleName}: $msg"
                } else {
                    err.javaClass.simpleName
                }
            } else {
                "mNativeEnginePtr == 0"
            }
            HostLog.e(
                ENGINE_ID,
                "فشل إنشاء كائن Vocalizer الأصلي: $detail",
                err
            )
            return HostResult.failure(
                "NATIVE_CREATE_FAILED",
                "تعذّر إنشاء محرك Vocalizer الأصلي ($detail)"
            )
        }

        val scan = DataFolderScanner.scan(File(dataDir))
        if (scan.headersText.isBlank()) {
            runCatching { eng.release() }
            HostLog.e(
                ENGINE_ID,
                "لا ترويسات .hdr في $dataDir " +
                    "(hdr=${scan.hdrFiles.size}, " +
                    "dat=${scan.datFiles.size})"
            )
            return HostResult.failure(
                "NO_PIPELINE_HEADERS",
                "لا ملفات ترويسات .hdr في مجلد $dataDir"
            )
        }

        val provider = VocalizerDataProvider(
            scan.datFiles,
            File(context.cacheDir, "vocalizer_plain")
        )
        eng.setDataProvider(provider)
        HostLog.i(
            ENGINE_ID,
            "ترويسات .hdr: ${scan.hdrFiles.size} ملف " +
                "(${scan.headersText.length} حرف)، " +
                ".dat: ${scan.datFiles.size} ملف"
        )

        val initialized = try {
            eng.initialize(scan.headersText)
        } catch (t: Throwable) {
            HostLog.e(
                ENGINE_ID,
                "استثناء أثناء التهيئة: ${t.message}",
                t
            )
            false
        }
        if (!initialized) {
            runCatching { eng.release() }
            HostLog.e(
                ENGINE_ID,
                "فشلت تهيئة VocalizerNative على $dataDir: " +
                    "${eng.lastError}"
            )
            return HostResult.failure(
                "INIT_FAILED",
                "تعذّرت تهيئة Vocalizer: ${eng.lastError}"
            )
        }

        engine = eng
        runCatching {
            val names = eng.voiceList
                ?.mapNotNull { it.voiceName }
                ?.joinToString()
            HostLog.i(ENGINE_ID, "أصوات المحرك المتاحة: $names")
        }
        HostLog.i(
            ENGINE_ID,
            "Vocalizer جاهز بترويسات .hdr " +
                "(${scan.headersText.length} حرف)"
        )
        return HostProtocol.success()
    }

    /** أصوات المحرك من المكتبة (يستلزم [init] ناجحاً أولاً). */
    fun listVoices(): List<HostVoice> {
        val eng = engine ?: return emptyList()
        val voices = try {
            eng.voiceList
        } catch (t: Throwable) {
            HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
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
            return try {
                engineThread.submit(
                    SynthesisBudget.requestTimeoutMs(req.text.length),
                    "speak"
                ) {
                    speakOnEngineThread(eng, req, sink, cancelled)
                }
            } catch (e: EngineTaskTimeoutException) {
                HostLog.e(
                    ENGINE_ID,
                    "انتهت مهلة النطق: ${e.message}"
                )
                HostResult.failure(
                    "SPEAK_TIMEOUT",
                    "انتهت مهلة نطق Vocalizer"
                )
            } catch (t: Throwable) {
                HostLog.e(
                    ENGINE_ID,
                    "استثناء أثناء النطق: ${t.message}",
                    t
                )
                HostResult.failure(
                    "ENGINE_ERROR",
                    t.message ?: "خطأ غير متوقّع"
                )
            }
        }
    }

    /**
     * نطقٌ كامل **على خيط المحرك**: تحميل الصوت ثم إعداداته ثم
     * `speak` — على الخيط نفسه الذي أنشأ المحرك ويهيّئه.
     */
    private fun speakOnEngineThread(
        eng: VocalizerEngine,
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val voices: Array<VocalizerVoice> = try {
            eng.voiceList ?: emptyArray()
        } catch (t: Throwable) {
            HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
            return HostResult.failure(
                "VOICE_LIST_FAILED",
                "تعذّرت قراءة أصوات المحرك"
            )
        }

        // ع٢٩-10 (3): مطابقة صارمة — لا سقوط على voices.firstOrNull()
        // الذي كان يخفي الخطأ ويقرأ بصوت آخر.
        val matched = voices.firstOrNull {
            matchesVoiceName(it.voiceName, req.voiceId)
        } ?: return HostResult.failure(
            "VOICE_NOT_FOUND",
            "صوت Vocalizer غير موجود: ${req.voiceId}"
        )
        if (!eng.loadVoice(matched)) {
            HostLog.e(
                ENGINE_ID,
                "loadVoice فشل: name='${matched.voiceName}' " +
                    "lang='${matched.language}' " +
                    "requested='${req.voiceId}' " +
                    "lastError='${eng.lastError}'"
            )
            return HostResult.failure(
                "LOAD_VOICE_FAILED",
                "تعذّر تحميل الصوت المطلوب: ${req.voiceId}"
            )
        }
        HostLog.i(
            ENGINE_ID,
            "حُمّل الصوت: ${matched.voiceName} " +
                "lang=${matched.language} " +
                "rate=${matched.sampleRate}"
        )

        val rateInt = (req.rate * 100).toInt().coerceIn(20, 400)
        val pitchInt = (req.pitch * 100).toInt().coerceIn(50, 200)
        val volumeInt = (req.volume * 100).toInt().coerceIn(0, 100)
        eng.setSpeechRate(rateInt)
        eng.setSpeechPitch(pitchInt)
        eng.setSpeechVolume(volumeInt)

        sink.writeHeader(outputSampleRateHz(matched.sampleRate), 1)

        val pipeBroken = AtomicBoolean(false)
        val cbCount = AtomicInteger(0)
        val cbBytes = AtomicLong(0)
        val settingsSeen = AtomicInteger(-1)
        eng.setAudioOutputListener(
            object : VocalizerAudioOutputListener {
                override fun onAudioData(
                    settings: VocalizerAudioSettings?,
                    audioData: ShortArray?
                ) {
                    cbCount.incrementAndGet()
                    if (settingsSeen.get() < 0 && settings != null) {
                        settingsSeen.set(settings.mRate)
                    }
                    if (audioData != null) {
                        cbBytes.addAndGet(audioData.size.toLong() * 2)
                    }
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

        HostLog.w(
            ENGINE_ID,
            "speak يبدأ: voice=${matched.voiceName} " +
                "rateRaw=${matched.sampleRate} " +
                "out=${outputSampleRateHz(matched.sampleRate)}Hz " +
                "textLen=${req.text.length}"
        )
        val speakStart = SystemClock.uptimeMillis()
        try {
            // ع٢٩-10 (3): speak متزامن — أُثبت ذلك من ثنائية
            // libNuanceVocalizer.so: ردّاها إلى Java لا يتجاوزان
            // audioSamplesReceived/speechMarksReceived (لا استدعاء
            // «اكتمال النطق» ولا حقل isSpeaking في StatusInfo)،
            // فلا يعود speakNative إلا بعد تسليم كل الدفعات
            // للمستمع؛ ولهذا يليه eng.stop() في finally عبثاً
            // آمناً لا يقطع صوتاً.
            eng.speak(req.text)
        } finally {
            HostLog.w(
                ENGINE_ID,
                "speak عاد: ms=${SystemClock.uptimeMillis() - speakStart} " +
                    "دفعات=${cbCount.get()} بايت=${cbBytes.get()} " +
                    "معدل=${settingsSeen.get()} " +
                    "stop=${stopRequested} cancel=${cancelled.get()} " +
                    "break=${pipeBroken.get()} lastError=${eng.lastError}"
            )
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

    override fun stop() {
        // إشارة الإلغاء أولاً: تُبتلع الدفعات التالية في callbacks
        // فتنتهي عودة speak سريعاً، ثم stop() أصليٌّ يوقف التوليد.
        // بلا قفل speakLock عَمداً: قفلُه هنا كان ينتظر نطقاً
        // طويلاً كاملاً فيبطل الإلغاء.
        stopRequested = true
        val eng = engine ?: return
        runCatching { eng.stop() }
    }

    override fun shutdown() {
        synchronized(engineLock) {
            val eng = engine
            engine = null
            if (eng != null) {
                runCatching {
                    engineThread.submit(SHUTDOWN_TIMEOUT_MS, "release") {
                        eng.release()
                    }
                }
            }
            engineThread.shutdown()
        }
    }

    private fun toLePcm(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        return buffer.array()
    }
}
