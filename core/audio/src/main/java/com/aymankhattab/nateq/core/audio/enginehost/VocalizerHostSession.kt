package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.aymankhattab.nateq.core.audio.engine.EnginePerformanceCounters
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.VoiceIdContract
import com.aymankhattab.nateq.core.audio.providers.VocalizerModelCatalog
import com.nuance.android.vocalizer.VocalizerAudioOutputListener
import com.nuance.android.vocalizer.VocalizerAudioSettings
import com.nuance.android.vocalizer.VocalizerEngine
import com.nuance.android.vocalizer.VocalizerVoice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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

        private fun stripVocalizerPrefix(id: String): String =
            id.removePrefix("vocalizer_")
                .removePrefix("vocalizer-")
                .trim()

        /**
         * مطابقة اسم الصوت المطلوب باسم صوت المحرك:
         * مطابقة صارمة (تساوٍ تام بعد رفع بادئة vocalizer) مع توحيد
         * الأسماء البديلة المعروفة (Tarek/Tariq/Tarik، Leila/Layla)،
         * ودعم معرّفات الكتالوج المركّبة عبر مطابقة رمز الصوت في
         * الطلب لا عبر تطابق جزئي حرّ (يحفظ عقد rejectsPartialNames).
         */
        internal fun matchesVoiceName(
            engineName: String?,
            requestedId: String
        ): Boolean {
            if (engineName.isNullOrBlank() ||
                requestedId.isBlank()
            ) return false
            val engine = stripVocalizerPrefix(engineName).trim()
            val requested = stripVocalizerPrefix(requestedId).trim()
            if (engine.isEmpty() || requested.isEmpty()) return false
            if (engine.equals(requested, ignoreCase = true)) return true

            // توحيد بدائل تهجئة الاسم الكامل (بدون تقليص أجزاء الاسم).
            val canonEngine = canonicalVoiceAlias(engine)
            val canonReq = canonicalVoiceAlias(requested)
            if (canonEngine.isNotEmpty() && canonReq.isNotEmpty() &&
                canonEngine.equals(canonReq, ignoreCase = true)
            ) return true

            // معرّفات كتالوج مركّبة (vocalizer-ara-sau-laila-compact):
            // طابق اسم المحرك (أو بديله القانوني) برمز/رموز الطلب فقط.
            val tokens = requested.split('-', '_')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (tokens.size <= 1) return false
            return tokens.any { token ->
                val ct = canonicalVoiceAlias(token)
                token.equals(engine, ignoreCase = true) ||
                    token.equals(canonEngine, ignoreCase = true) ||
                    (ct.isNotEmpty() &&
                        ct.equals(canonEngine, ignoreCase = true))
            }
        }

        /** بديل تهجئة معتمد للاسم كاملاً (دون اقتطاع لاحقات العرض). */
        private fun canonicalVoiceAlias(name: String): String {
            val n = name.trim()
            if (n.isEmpty()) return ""
            // جرّب الاسم كاملاً ثم الأساس قبل قوس العرض مثل "Tarik (High)".
            val baseParen = n.substringBefore('(').trim().ifEmpty { n }
            val cFull = VoiceIdContract.canonicalVoiceName(n)
            val cBase = VoiceIdContract.canonicalVoiceName(baseParen)
            return when {
                !cFull.equals(n, ignoreCase = true) -> cFull
                !cBase.equals(baseParen, ignoreCase = true) -> cBase
                else -> cFull.ifEmpty { n }
            }
        }

        /**
         * هل المعرّف لغة/محلي فقط (مثل ar-EG/ara/ara-SAU) وليس اسم صوت محدد؟
         * بند 2.
         */
        internal fun isLanguageOnlyVoiceId(id: String?): Boolean {
            val core = stripVocalizerPrefix(id.orEmpty())
            if (core.isBlank()) return false
            val tag = LocaleUtils.toBcp47Tag(core, false) ?: return false
            val parts = core.replace('_', '-').split('-')
                .filter { it.isNotBlank() }
            if (parts.isEmpty() || parts.size > 3) return false
            // أسماء أصوات قصيرة معروفة (Tom/Ava) لا تُعدّ وسماً لغوياً.
            val canon = VoiceIdContract.canonicalVoiceName(core)
            if (canon.equals("Tom", true) ||
                canon.equals("Ava", true)
            ) return false
            // وسم لغوي/محلي: 1-2 (وأحياناً 3) أجزاء رمزية قصيرة.
            val langLike = parts.all { p ->
                p.length in 2..3 && p.all { it.isLetterOrDigit() }
            }
            if (!langLike) return false
            // إن كان يطابق صوتاً معرّفاً بالاسم (اسم شخص) فلا نعدّه لغة.
            return LocaleUtils.normalizeLanguageCode(tag).isNotBlank()
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

        private fun normalizedVoiceLang(raw: String?): String? {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty()) return null
            val tag = LocaleUtils.toBcp47Tag(t, false) ?: t
            val code = LocaleUtils.normalizeLanguageCode(tag)
                .lowercase(Locale.ROOT)
            return code.takeIf { it.isNotBlank() }
        }

        /**
         * مطابقة رمز اللغة بعد التطبيع (بند 8) — بلا contains مفتوحة.
         */
        internal fun matchesLanguage(
            voice: VocalizerVoice,
            requestedId: String
        ): Boolean {
            val reqCore = stripVocalizerPrefix(requestedId)
            if (reqCore.isBlank()) return false
            val reqLang = normalizedVoiceLang(reqCore)
                ?: return false
            val candidates = listOfNotNull(
                normalizedVoiceLang(voice.language),
                normalizedVoiceLang(voice.languageTLW),
                normalizedVoiceLang(
                    LocaleUtils.toBcp47Tag(voice.language, false)
                ),
                normalizedVoiceLang(
                    LocaleUtils.toBcp47Tag(voice.languageTLW, false)
                )
            ).distinct()
            return candidates.any { it == reqLang }
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
    private val engineThread = EngineThread(
        ENGINE_ID, "voc-engine", context.filesDir
    )

    /** قفل توليد واحد: محرّك واحد لا يتولّى نطقين معاً. */
    private val speakLock = Any()

    @Volatile
    private var engine: VocalizerEngine? = null

    /**
     * مفتاح الصوت المحمَّل حالياً (هوية متغيّر/هيدر، بند 4) لتفادي
     * إعادة loadVoice في كل مقطع (ع٤٢-7). قد يختلف عن اسم صوت المحرك
     * عند تمييز نسختي Tarik بالجودة/الهيدر.
     */
    @Volatile
    private var loadedVoiceKey: String? = null

    /** اسم صوت المحرك المحمَّل فعلياً (لـ loadVoice). */
    @Volatile
    private var loadedEngineVoiceName: String? = null

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
        loadedVoiceKey = null
        loadedEngineVoiceName = null
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

        EnginePerformanceCounters.vocalizer.engineInitCount.incrementAndGet()
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

    /** نتيجة حلّ طلب صوت: الصوت الفعلي في المحرك + مفتاح الهوية (بند 4). */
    private data class ResolvedEngineVoice(
        val engineVoice: VocalizerVoice,
        val variantKey: String,
        val engineVoiceName: String
    )

    /**
     * يبني قائمة أصوات المضيف مع تمييز المتغيّرات (جودة/هيدر، بند 4).
     * تُرجع إدخالاً لكل متغيّر كتالوج مثبّت فعلياً في مجلد البيانات وله
     * صوتٌ مطابق في المحرك؛ وإن تعذّر الربط بالكتالوج تُعاد أصوات المحرك
     * كما هي. الهوية (`id`) تشمل مفتاح المتغيّر (معرّف الكتالوج/الهيدر)
     * والاسم يميّز الجودة للعرض.
     */
    fun listVoices(): List<HostVoice> {
        val eng = engine ?: return emptyList()
        val dataDirPath = loadedDataDir
        return try {
            engineThread.submit(INIT_TIMEOUT_MS, "listVoices") {
                val engineVoices = eng.voiceList?.toList().orEmpty()
                buildVariantVoices(engineVoices, dataDirPath)
            }
        } catch (t: Throwable) {
            HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
            emptyList()
        }
    }

    private fun buildVariantVoices(
        engineVoices: List<VocalizerVoice>,
        dataDirPath: String?
    ): List<HostVoice> {
        val dataDir = dataDirPath?.let { File(it) }
        val result = mutableListOf<HostVoice>()
        val coveredEngineNames = mutableSetOf<String>()

        // بند 4/6: لكل صوت محرك، أدرج متغيّرات الكتالوج المثبتة فعلياً
        // (تمييز Tarik Compact/High بالهيدر/الجودة) مع تسجيل اللغة.
        for (ev in engineVoices) {
            val eName = ev.voiceName ?: continue
            val eCanon = VoiceIdContract.canonicalVoiceName(eName)
            val rawLangTag = LocaleUtils.toBcp47Tag(ev.language)
                ?: LocaleUtils.toBcp47Tag(ev.languageTLW)
                ?: ev.language.orEmpty()
            val langTag = if (rawLangTag.isBlank() &&
                eName.contains("mariam", ignoreCase = true)
            ) {
                "ar"
            } else {
                rawLangTag
            }
            val installedVariants = if (dataDir != null) {
                VocalizerModelCatalog.ITEMS.filter { item ->
                    VoiceIdContract.sameVoiceName(
                        item.voiceName, eName
                    ) || VoiceIdContract.sameVoiceName(
                        item.voiceName, eCanon
                    ) || item.voiceName.equals(eCanon, true)
                }.filter { item ->
                    VocalizerModelCatalog.installedHeaderFile(
                        dataDir, item
                    ) != null
                }
            } else {
                emptyList()
            }
            if (installedVariants.isNotEmpty()) {
                for (item in installedVariants) {
                    val variantLang = LocaleUtils.toBcp47Tag(
                        item.locale.toLanguageTag()
                    ) ?: langTag
                    val hv = HostVoice(
                        id = item.id,
                        name = "${item.voiceName} ${item.quality}",
                        langTag = variantLang
                    )
                    result.add(hv)
                    HostLog.d(
                        ENGINE_ID,
                        "listVoices: name=${hv.name} rawLang=$langTag " +
                            "id=${hv.id} hdr=${item.headerFileName}"
                    )
                }
                coveredEngineNames.add(eName.lowercase(Locale.ROOT))
            }
        }

        // أصوات المحرك غير المغطاة بمتغيّرات كتالوج مثبتة (احتياط).
        for (ev in engineVoices) {
            val eName = ev.voiceName ?: continue
            if (eName.lowercase(Locale.ROOT) in coveredEngineNames) continue
            val rawLangTag = LocaleUtils.toBcp47Tag(ev.language)
                ?: LocaleUtils.toBcp47Tag(ev.languageTLW)
                ?: ev.language.orEmpty()
            val langTag = if (rawLangTag.isBlank() &&
                eName.contains("mariam", ignoreCase = true)
            ) {
                "ar"
            } else {
                rawLangTag
            }
            val hv = HostVoice(
                id = "vocalizer_${eName.lowercase(Locale.ROOT)}",
                name = eName,
                langTag = langTag
            )
            result.add(hv)
            HostLog.d(
                ENGINE_ID,
                "listVoices: name=${hv.name} rawLang=${ev.language} " +
                    "id=${hv.id}"
            )
        }

        if (result.isEmpty()) {
            // احتياط أخير: لو لم نتمكن من البناء، أعد أسماء المحرك كما هي.
            for (ev in engineVoices) {
                val eName = ev.voiceName ?: continue
                result.add(
                    HostVoice(
                        id = "vocalizer_${eName.lowercase(Locale.ROOT)}",
                        name = eName,
                        langTag = ev.language ?: ""
                    )
                )
            }
        }
        return result.distinctBy { it.id }
    }

    private fun findCatalogItemForRequest(
        requestedId: String
    ): com.aymankhattab.nateq.core.audio.providers.VocalizerCatalogItem? {
        val req = requestedId.trim()
        if (req.isEmpty()) return null
        return VocalizerModelCatalog.ITEMS.firstOrNull {
            it.id.equals(req, ignoreCase = true)
        } ?: VocalizerModelCatalog.ITEMS.firstOrNull {
            it.headerFileName.equals(req, ignoreCase = true)
        }
    }

    private fun resolveVoiceRequest(
        voices: List<VocalizerVoice>,
        requestedId: String
    ): ResolvedEngineVoice? {
        val dataDir = loadedDataDir?.let { File(it) }
        val catalogItem = findCatalogItemForRequest(requestedId)

        // 1) طلب متغيّر كتالوج محدد (بند 4): يجب أن يكون مثبتاً فعلياً.
        if (catalogItem != null) {
            val installed = dataDir != null &&
                VocalizerModelCatalog.installedHeaderFile(
                    dataDir, catalogItem
                ) != null
            val ev = voices.firstOrNull { v ->
                matchesVoiceName(v.voiceName, catalogItem.voiceName) ||
                    VoiceIdContract.sameVoiceName(
                        v.voiceName, catalogItem.voiceName
                    )
            }
            if (installed && ev != null) {
                return ResolvedEngineVoice(
                    ev,
                    catalogItem.id,
                    ev.voiceName ?: catalogItem.voiceName
                )
            }
            // متغيّر كتالوج مطلوب لكنه غير مثبت/غير متاح -> غياب صريح.
            return null
        }

        // 2) مطابقة مباشرة باسم/معرّف الصوت.
        val direct = voices.firstOrNull {
            matchesVoiceName(it.voiceName, requestedId)
        }
        if (direct != null) {
            val key = variantKeyForEngineVoice(direct, dataDir, requestedId)
            return ResolvedEngineVoice(
                direct, key, direct.voiceName ?: requestedId
            )
        }

        // 3) معرّف لغة فقط (بند 2): لا اختيار صامت.
        if (isLanguageOnlyVoiceId(requestedId)) {
            return null
        }

        // 4) افتراضي الجهاز.
        if (requestedId.isBlank() ||
            requestedId.equals("Device Default", ignoreCase = true)
        ) {
            val first = voices.firstOrNull() ?: return null
            val key = variantKeyForEngineVoice(first, dataDir, requestedId)
            return ResolvedEngineVoice(
                first, key, first.voiceName ?: ""
            )
        }

        return null
    }

    /**
     * مفتاح متغيّر لصوت محرك: إن وُجد متغيّر كتالوج مثبت وحيد يطابقه
     * استُخدم معرّف الكتالوج (تمييز الهيدر/الجودة)، وإلا اسم المحرك.
     */
    private fun variantKeyForEngineVoice(
        voice: VocalizerVoice,
        dataDir: File?,
        requestedId: String
    ): String {
        val eName = voice.voiceName.orEmpty()
        val req = requestedId.trim()
        // إن كان الطلب يحمل تمييز جودة/هيدر صريحاً طابقه.
        val reqItem = findCatalogItemForRequest(req)
        if (reqItem != null) return reqItem.id
        if (dataDir == null) return req.ifBlank { eName }
        val installedMatches = VocalizerModelCatalog.ITEMS.filter { item ->
            (VoiceIdContract.sameVoiceName(item.voiceName, eName) ||
                matchesVoiceName(eName, item.voiceName)) &&
                VocalizerModelCatalog.installedHeaderFile(dataDir, item) != null
        }
        return when {
            installedMatches.size == 1 -> installedMatches.first().id
            // متغيّران مثبتان بنفس الاسم: لا نختار بصمتاً إن لم يحدد الطلب.
            installedMatches.size > 1 -> {
                // حاول مطابقة الجودة من الطلب إن ذُكرت.
                val byQuality = installedMatches.firstOrNull { item ->
                    req.contains(item.quality, ignoreCase = true)
                }
                byQuality?.id ?: eName
            }
            else -> req.ifBlank { eName }
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
        val sanitizedText = req.text.replace(
            Regex(
                """(?<![\p{IsArabic}\p{InArabic}\p{M}])ج(?!\.م)""" +
                    """(?![\p{IsArabic}\p{InArabic}\p{M}])"""
            ),
            "جيم"
        )
        val chunks = splitIntoVocalizerChunks(sanitizedText)
        if (chunks.isEmpty()) {
            return HostProtocol.success()
        }

        val voices: List<VocalizerVoice> = try {
            eng.voiceList?.toList().orEmpty()
        } catch (t: Throwable) {
            HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
            return HostResult.failure(
                "VOICE_LIST_FAILED",
                "تعذّرت قراءة أصوات المحرك"
            )
        }

        val resolved = resolveVoiceRequest(voices, req.voiceId)
            ?: return HostResult.failure(
                "VOICE_NOT_FOUND",
                if (isLanguageOnlyVoiceId(req.voiceId)) {
                    "معرّف لغة فقط بلا صوت محدد: ${req.voiceId}"
                } else if (findCatalogItemForRequest(req.voiceId) != null) {
                    "الصوت المحدد غير مثبت/غير متاح: ${req.voiceId}"
                } else {
                    "صوت Vocalizer غير موجود: ${req.voiceId}"
                }
            )
        val matched = resolved.engineVoice
        val targetVoiceName = resolved.engineVoiceName
        val targetVoiceKey = resolved.variantKey
        if (loadedVoiceKey != targetVoiceKey ||
            loadedEngineVoiceName != targetVoiceName
        ) {
            EnginePerformanceCounters.vocalizer.loadVoiceMisses
                .incrementAndGet()
            if (!eng.loadVoice(matched)) {
                HostLog.e(
                    ENGINE_ID,
                    "loadVoice فشل: name='${matched.voiceName}' " +
                        "lang='${matched.language}' " +
                        "requested='${req.voiceId}' " +
                        "variant='$targetVoiceKey' " +
                        "lastError='${eng.lastError}'"
                )
                return HostResult.failure(
                    "LOAD_VOICE_FAILED",
                    "تعذّر تحميل الصوت المطلوب: ${req.voiceId}"
                )
            }
            loadedVoiceKey = targetVoiceKey
            loadedEngineVoiceName = targetVoiceName
            HostLog.i(
                ENGINE_ID,
                "حُمّل الصوت: ${matched.voiceName} " +
                    "variant=$targetVoiceKey " +
                    "lang=${matched.language} " +
                    "rate=${matched.sampleRate}"
            )
        } else {
            EnginePerformanceCounters.vocalizer.loadVoiceHits
                .incrementAndGet()
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
        var nativeSpeakFailed = false
        try {
            for (chunk in chunks) {
                if (cancelled.get() || stopRequested || pipeBroken.get()) {
                    break
                }
                // البند ٨: خطأ speakNative يصل إلينا بدل أن يُبتلع.
                if (!eng.speak(chunk)) {
                    nativeSpeakFailed = true
                    break
                }
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
            nativeSpeakFailed -> HostResult.failure(
                "SPEAK_NATIVE_FAILED",
                "فشل نطق Vocalizer: ${eng.lastError}"
            )
            // البند ٨: صفر عينات PCM يعني فشلاً، لا نجاحاً صامتاً
            // يُعلَن للمتلقّي كأن النطق تمّ.
            cbBytes.get() <= 0L -> HostResult.failure(
                "NO_PCM_SAMPLES",
                "لم يُنتج محرك Vocalizer أي عينات صوت"
            )
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
            EnginePerformanceCounters.vocalizer.serviceCloseCount
                .incrementAndGet()
            loadedVoiceKey = null
            loadedEngineVoiceName = null
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

    fun trimResources() {
        synchronized(engineLock) {
            releaseEngineBeforeRebuild()
        }
    }

    internal fun isWarmVoiceLoaded(variantKey: String): Boolean =
        loadedVoiceKey == variantKey

    private fun toLePcm(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        return buffer.array()
    }
}
