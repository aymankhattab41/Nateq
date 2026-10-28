package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.engine.VoiceResolutionPolicy
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

        /** أعلى حجم لمخزن الصوت الأصلي (بالبايت)، مطابق SDK. */
        internal const val MAX_AUDIO_BUFFER_SIZE = 65536

        /**
         * مضاعِف المخزن الداخلي في {@code SynthThread.run} الأصلي
         * ({@code mInternalAudioBufferPayload = 10.0f}).
         */
        private const val INTERNAL_AUDIO_BUFFER_PAYLOAD = 10.0f

        /**
         * أدنى حجم مخزن يكفي دفعة المحرك الواحدة (5600 عيّنة × 2 بايت).
         */
        private const val MIN_AUDIO_BUFFER_BYTES = 11200

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
         * حجم مخزن الصوت الأصلي بالبايت، مطابقاً لـ
         * {@code SynthThread.run} في SDK:
         * {@code min(65536, 10 × getMinBufferSize)} مقرّباً لزوجي،
         * مع ضمان حدٍّ أدنى يكفي دفعة المحرك (11200 بايت).
         *
         * <p>مطلوبٌ قبل {@code speakNative} وإلا بقي مخزن المحرك
         * NULL فعاد النطق بلا عينات.
         */
        internal fun audioBufferSizeFor(outputRateHz: Int): Int {
            val hwMin = runCatching {
                AudioTrack.getMinBufferSize(
                    outputRateHz,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
            }.getOrDefault(0)
            val payload = if (hwMin > 0) {
                (INTERNAL_AUDIO_BUFFER_PAYLOAD * hwMin).toInt()
            } else {
                MAX_AUDIO_BUFFER_SIZE
            }
            val capped = minOf(MAX_AUDIO_BUFFER_SIZE, payload)
                .coerceAtLeast(MIN_AUDIO_BUFFER_BYTES)
            return (capped / 2) * 2
        }

        /**
         * مطابقة اسم الصوت المطلوب باسم صوت المحرك (ع٢٩-10):
         * تُرفع بادئة vocalizer_ عن الطرفين وتُدعم
         * معرّفات الكتالوج.
         */
        internal fun matchesVoiceName(
            engineName: String?,
            requestedId: String
        ): Boolean {
            if (engineName.isNullOrBlank()) return false
            val engine = engineName.removePrefix("vocalizer_")
                .removePrefix("vocalizer-").trim()
            val requested = requestedId.removePrefix("vocalizer_")
                .removePrefix("vocalizer-").trim()
            if (engine.equals(requested, ignoreCase = true)) return true
            val normEngine = engine.lowercase()
                .replace("leila", "laila")
                .replace("tariq", "tarik")
                .replace("majed", "maged")
                .replace("maryam", "mariam")
            val normReq = requested.lowercase()
                .replace("leila", "laila")
                .replace("tariq", "tarik")
                .replace("majed", "maged")
                .replace("maryam", "mariam")
            if (normEngine == normReq) return true
            val tokens = normReq.split('-', '_', ' ')
            if (tokens.any { it.equals(normEngine, ignoreCase = true) }) {
                return true
            }
            return false
        }

        /**
         * هل يجب إعادة بناء المحرّك الأصلي؟ يُعاد البناء إن لم يوجد
         * محرّك، أو تغيّر مسار البيانات، أو تغيّر محتوى الترويسات.
         * الترويساتُ تُقرأ مرة واحدة عند الإنشاء، فبلا إعادة البناء
         * لا تظهر الأصوات المضافة (تنزيل صوتٍ جديد) ولا يختفي المحذوف.
         */
        internal fun shouldRebuildEngine(
            enginePresent: Boolean,
            dataDir: String,
            loadedDataDir: String?,
            headersText: String,
            loadedHeadersText: String?
        ): Boolean = !enginePresent ||
            dataDir != loadedDataDir ||
            headersText != loadedHeadersText

        internal fun matchesLanguage(
            voice: VocalizerVoice,
            requestedId: String
        ): Boolean {
            val req = requestedId.trim().lowercase(java.util.Locale.ROOT)
            val lang = voice.language
                ?.lowercase(java.util.Locale.ROOT).orEmpty()
            val tlw = voice.languageTLW
                ?.lowercase(java.util.Locale.ROOT).orEmpty()
            val isArabic = req.startsWith("ar") || req == "ara"
            val isEnglish = req.startsWith("en") || req == "eng"
            return when {
                isArabic -> lang.startsWith("ar") || tlw.startsWith("ar")
                isEnglish -> lang.startsWith("en") || tlw.startsWith("en")
                else -> lang.contains(req) || tlw.contains(req)
            }
        }

        /** الحد الأقصى لطول مقطع النطق لـ Vocalizer (ع٤٢-7). */
        internal const val VOCALIZER_MAX_CHUNK_CHARS = 300

        /** حدود جمل وفواصل آمنة لتقسيم النص لـ Vocalizer. */
        internal val VOCALIZER_SENTENCE_BOUNDARIES = charArrayOf(
            '.', '!', '?', '،', ',', '؛', ';', ':', '؟', '۔', '।', '\n'
        )

        /**
         * يقسّم النص الطويل إلى جمل ومقاطع (≤ 300 حرف) عند حدود
         * الجمل والترقيم والمسافات (ع٤٢-7 بند 3).
         */
        internal fun splitIntoVocalizerChunks(
            text: String,
            maxChars: Int = VOCALIZER_MAX_CHUNK_CHARS
        ): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            if (trimmed.length <= maxChars) return listOf(trimmed)

            val chunks = ArrayList<String>()
            var rest = trimmed
            while (rest.length > maxChars) {
                val cut = findVocalizerChunkCut(rest, maxChars)
                val chunk = rest.substring(0, cut).trim()
                if (chunk.isNotEmpty()) {
                    chunks.add(chunk)
                }
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) {
                chunks.add(rest)
            }
            return chunks
        }

        private fun findVocalizerChunkCut(
            text: String,
            maxChars: Int
        ): Int {
            val limit = minOf(maxChars, text.length)
            var i = limit - 1
            while (i >= 0) {
                if (text[i] in VOCALIZER_SENTENCE_BOUNDARIES) {
                    return adjustSurrogate(text, i + 1)
                }
                i--
            }
            i = limit - 1
            while (i >= 0) {
                if (text[i].isWhitespace()) {
                    return adjustSurrogate(text, i + 1)
                }
                i--
            }
            return adjustSurrogate(text, limit)
        }

        private fun adjustSurrogate(text: String, cut: Int): Int =
            if (cut in 1 until text.length &&
                Character.isHighSurrogate(text[cut - 1]) &&
                Character.isLowSurrogate(text[cut])
            ) {
                cut - 1
            } else {
                cut
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

    /** اسم الصوت المحمَّل حالياً لتفادي إعادة loadVoice في كل مقطع (ع٤٢-7). */
    @Volatile
    private var loadedVoiceName: String? = null

    @Volatile
    private var stopRequested = false

    /**
     * مسار البيانات ومحتوى الترويسات الذي بُني عليه المحرّك الحالي:
     * أي تغيّر (تنزيل صوت جديد أو حذفه) يوجب إعادة بناء المحرّك لأن
     * الأصوات تُقرأ مرة واحدة عند الإنشاء.
     */
    @Volatile
    private var loadedDataDir: String? = null

    @Volatile
    private var loadedHeadersText: String? = null

    override fun init(dataDir: String): HostResult {
        synchronized(engineLock) {
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
    /**
     * يهدم محرّكاً سابقاً قبل إعادة البناء على خيط المحرك نفسه
     * (الخيط الذي استخدمه) فلا يتعارض التحرير مع نطق جارٍ.
     */
    private fun releaseEngineBeforeRebuild() {
        val old = engine
        engine = null
        loadedVoiceName = null
        loadedDataDir = null
        loadedHeadersText = null
        if (old != null) {
            runCatching { old.release() }
        }
    }

    private fun initializeOnEngineThread(dataDir: String): HostResult {
        val scan = DataFolderScanner.scan(File(dataDir))
        // إعادة استخدام المحرّك الحالي فقط إن كان بنفس المسار ونفس
        // محتوى الترويسات؛ تغيّرُ المحتوى (تنزيل صوت أو حذفه) يوجب
        // إعادة البناء وإلا بقيت قائمة الأصوات قديمة في المحرّك الأصلي.
        val rebuild = shouldRebuildEngine(
            enginePresent = engine != null,
            dataDir = dataDir,
            loadedDataDir = loadedDataDir,
            headersText = scan.headersText,
            loadedHeadersText = loadedHeadersText
        )
        if (!rebuild) {
            HostLog.d(ENGINE_ID, "المحرك مهيّأ مسبقاً بنفس الترويسات")
            return HostProtocol.success()
        }
        releaseEngineBeforeRebuild()

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
        loadedDataDir = dataDir
        loadedHeadersText = scan.headersText
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
        return try {
            engineThread.submit(INIT_TIMEOUT_MS, "listVoices") {
                val voices = eng.voiceList
                voices?.mapNotNull { v ->
                    val name = v.voiceName ?: return@mapNotNull null
                    HostVoice(
                        id = "vocalizer_${name.lowercase()}",
                        name = name,
                        langTag = v.language ?: ""
                    )
                } ?: emptyList()
            }
        } catch (t: Throwable) {
            HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
            emptyList()
        }
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
                    SynthesisBudget.ABSOLUTE_REQUEST_TIMEOUT_MS,
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
     * نطقٌ كامل **على خيط المحرك**: تحميل الصوت (مع كاش) ثم إعداداته ثم
     * `speak` على مقاطع (≤ 300 حرف) — على الخيط نفسه الذي أنشأ المحرك ويهيّئه.
     */
    private fun speakOnEngineThread(
        eng: VocalizerEngine,
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val chunks = splitIntoVocalizerChunks(req.text)
        if (chunks.isEmpty()) {
            return HostProtocol.success()
        }

        val voices: Array<VocalizerVoice> = try {
            eng.voiceList ?: emptyArray()
        } catch (t: Throwable) {
            HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
            return HostResult.failure(
                "VOICE_LIST_FAILED",
                "تعذّرت قراءة أصوات المحرك"
            )
        }

        val matched = voices.firstOrNull {
            matchesVoiceName(it.voiceName, req.voiceId)
        } ?: voices.firstOrNull { voice ->
            matchesLanguage(voice, req.voiceId)
        } ?: voices.firstOrNull { voice ->
            val lang = VoiceResolutionPolicy.languageOfVoiceId(req.voiceId)
            if (!lang.isNullOrBlank()) {
                matchesLanguage(voice, lang)
            } else {
                false
            }
        } ?: if (req.voiceId.isBlank() ||
            req.voiceId.equals("Device Default", ignoreCase = true)
        ) {
            voices.firstOrNull()
        } else {
            // تراجع آمن لأي صوت مثبت في المحرك بدل إسقاط النطق
            voices.firstOrNull()
        } ?: return HostResult.failure(
            "VOICE_NOT_FOUND",
            "صوت Vocalizer غير موجود: ${req.voiceId}"
        )
        val targetVoiceName = matched.voiceName
        if (loadedVoiceName != targetVoiceName) {
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
            loadedVoiceName = targetVoiceName
            HostLog.i(
                ENGINE_ID,
                "حُمّل الصوت: ${matched.voiceName} " +
                    "lang=${matched.language} " +
                    "rate=${matched.sampleRate}"
            )
        }

        val rateInt = (req.rate * 100).toInt().coerceIn(20, 400)
        val pitchInt = (req.pitch * 100).toInt().coerceIn(50, 200)
        val volumeInt = (req.volume * 100).toInt().coerceIn(0, 100)
        eng.setSpeechRate(rateInt)
        eng.setSpeechPitch(pitchInt)
        eng.setSpeechVolume(volumeInt)

        val outRateHz = outputSampleRateHz(matched.sampleRate)
        if (!eng.configureAudioBuffer(audioBufferSizeFor(outRateHz))) {
            HostLog.e(
                ENGINE_ID,
                "تعذّر تخصيص مخزن صوت Vocalizer الأصلي"
            )
            return HostResult.failure(
                "AUDIO_BUFFER_FAILED",
                "تعذّر تخصيص مخزن صوت Vocalizer"
            )
        }

        sink.writeHeader(outRateHz, 1)

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
                "textLen=${req.text.length} chunks=${chunks.size}"
        )
        val speakStart = SystemClock.uptimeMillis()
        try {
            for (chunk in chunks) {
                if (cancelled.get() || stopRequested || pipeBroken.get()) {
                    break
                }
                eng.speak(chunk)
            }
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
            loadedVoiceName = null
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
