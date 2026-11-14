package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.acapelagroup.android.tts.AcattsandroidService
import com.acapelagroup.android.tts.acattsandroid
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.providers.AcapelaLicense
import com.aymankhattab.nateq.core.audio.providers.AcapelaParameters
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.delay
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * جلسة محرك Acapela داخل عملية :engine_host المعزولة.
 *
 * **تسلسل MultiTTS المُحاكى حرفياً** — وهو مرجع التشغيل المؤكد:
 *
 * 1. تهيئة كلاسيكية: `nInitCallbacks` ← `nSetLicense` ←
 *    `nGetVoiceList(المجلدات)`.
 * 2. كلاسيكي (بلا تسجيل): `nLoadVoice(speaker)` يعيد معدل العينات
 *    (> 0 نجاح) ← `nSetSpeechRate` ← `nSetShape` (إن نجحت السرعة)
 *    ← `nSpeak(نص خام)` لكل قطعة 200 حرف.
 * 3. ممتد (حزمة مسجلة): `nInit(المجلد + فاصل)` ←
 *    `nSetVoiceMode("MODE=prep_hd")` ← `nUtf8Mode` إن وُجد قاموس
 *    UTF-8 ← `nSetLanguage(ISO3 لغة, ISO3 دولة, "")` ←
 *    `nSetCurrentVoice(speaker)` ←
 *    `nSetProperty("punctuation_pause", "0")` ← ثم النطق
 *    `nSpeak(text, null)` مع `pitch`/`volume`/`rate`.
 *
 * **لا تحقّق نطقيّ في [init]:** تجربة أصوات المكتبة عند التهيئة كانت
 * تُفشل التهيئة كلها إذا صمت صوتٌ واحد، فتُحجب الأصوات السليمة.
 * العطل يُكشف عند النطق برمزٍ معنون يسمّي الخطوة الفاشلة.
 *
 * **قاعدة التقدّم:** الدوال الأصلية المتزامنة لا تبثّ إطارات تقدّم،
 * فتُفتح نافذة نداءٍ أصليّ ([PcmSink.enterNativeCall]) يُرسَل فيها
 * نبضة دورية، وإلا عدّ الحارسان المحرك خاملاً فقطعا نطقاً سليماً.
 */
internal class AcapelaHostSession(
    private val context: Context
) : HostEngineSession, acattsandroid.AudioListener {

    companion object {
        private const val TAG = "AcapelaHostSession"
        private const val DEFAULT_SAMPLE_RATE = 22050

        /** نمط الصوت الممتد الذي يفرضه MultiTTS قبل تحميل الحزمة. */
        private const val VOICE_MODE_HD = "MODE=prep_hd"

        /** وقفة علامات الترقيم: صفر كما في MultiTTS. */
        private const val PUNCTUATION_PAUSE_KEY = "punctuation_pause"
        private const val PUNCTUATION_PAUSE_VALUE = "0"

        /** فاصل انتظار حلقة اكتمال القطعة. */
        private const val WAIT_STEP_MS = 15L

        /** فاصل نبضات التقدّم أثناء انتظار اكتمال النطق. */
        private const val PING_INTERVAL_MS = 500L

        /** صمتٌ بعد آخر عيّنة يدل على انتهاء القطعة. */
        private const val TAIL_SILENCE_MS = 150L

        /** مهلة بدء الصوت قبل اعتبار المحرك عاجزاً عن النطق. */
        private const val START_SILENCE_MS = 2_500L

        /** عدد أحرف القطعة الواحدة — مطابق لتقسيم MultiTTS. */
        private const val CHUNK_CHARS = 200

        /** نصٌّ قصير لفحص قبول بيانات التسجيل قبل النطق الحقيقي. */
        private const val REGISTRATION_PROBE_TEXT = "مرحبا"

        /** ملف حفظ حرف المرشح الناجح بجانب الصوت (بقاء بعد إعادة التشغيل). */
        private const val REGISTRATION_CHOICE_FILE =
            ".nateq_acapela_registration"

        /**
         * مرشح بيانات تسجيل واحد: حرفه المعلن في السجل وقيمته الحقيقية.
         * القيمة لا تُسجَّل إطلاقاً — الطول والحرف فقط.
         */
        internal data class RegistrationCandidate(
            val letter: Char,
            val value: String
        )

        internal fun calculateSpeedPercent(rate: Float): Int =
            (rate * 100f).roundToInt().coerceIn(30, 400)

        internal fun calculatePitchPercent(pitch: Float): Int =
            (pitch * 100f).roundToInt().coerceIn(50, 200)

        /**
         * مرشحات بيانات التسجيل من ملف المفتاح `0x...` بالترتيب المقرر:
         *
         * - `a`: المحتوى كاملاً كما هو (بما فيه الاقتباس والمسافات و`#`
         *   والأسطر الجديدة).
         * - `b`: المحتوى نفسه والأسطر الجديدة مستبدلة بمسافة واحدة.
         * - `c`: السطر الأول وحده بعد إزالة علامتَي الاقتباس المحيطتين.
         * - `d`: السطر الأول وحده مع إبقاء الاقتباسين.
         * - `e`: الرمز الحالي المقروء من بيانات الصوت.
         *
         * **لا يُسجَّل محتوى أي مرشح** — الحرف والطول وكود العودة فقط.
         */
        internal fun registrationCandidates(
            dir: File,
            current: String
        ): List<RegistrationCandidate> {
            val raw = AcapelaVoiceManifest.findHexRegistrationFile(dir)
                ?.let { file ->
                    runCatching {
                        file.readText(StandardCharsets.UTF_8)
                    }.getOrNull()
                }
                .orEmpty()
                .removePrefix("\uFEFF")
            val full = raw.trim()
            val oneLine = full.replace(Regex("\\r\\n|[\\r\\n]"), " ").trim()
            val firstLine = full.lineSequence().firstOrNull()
                ?.trim().orEmpty()
            val firstUnquoted = firstLine
                .removeSurrounding("\"").trim()
            return listOf(
                'a' to full,
                'b' to oneLine,
                'c' to firstUnquoted,
                'd' to firstLine,
                'e' to current.trim()
            )
                .filter { it.second.isNotEmpty() }
                .distinctBy { it.second }
                .map { RegistrationCandidate(it.first, it.second) }
        }

        private fun withTrailingSlash(path: String): String =
            if (path.endsWith(File.separator)) path else path + File.separator

        /**
         * مسارات البحث عن الأصوات، مع تجهيز المجلدات بما تحتاجه
         * المكتبة (بلا توليد تهيئة مصطنعة — انظر
         * [AcapelaVoiceManifest.ensureVoiceConfigs]).
         */
        internal fun collectVoiceSearchPaths(
            baseDir: File,
            extraDirs: List<File> = emptyList()
        ): Array<String> {
            val paths = LinkedHashSet<String>()
            val allBases = (listOf(baseDir) + extraDirs)
                .filter { it.exists() && it.isDirectory }
                .distinctBy { it.canonicalPath }

            for (base in allBases) {
                AcapelaVoiceManifest.pruneSyntheticConfigs(base)
                AcapelaVoiceManifest.ensureVoiceConfigs(base)
                AcapelaVoiceManifest.completeVoiceDirs(base).forEach { vDir ->
                    paths.add(vDir.absolutePath)
                }
                base.walkTopDown().maxDepth(6).forEach { f ->
                    if (f.isDirectory) {
                        val hasVoice = f.listFiles { child ->
                            child.isFile && child.extension.lowercase(
                                Locale.ROOT
                            ) in setOf("inf", "ini", "bvcu", "dat")
                        }?.isNotEmpty() == true
                        if (hasVoice) {
                            paths.add(f.absolutePath)
                        }
                    }
                }
                val baseHasVoice = base.listFiles { child ->
                    child.isFile && child.extension.lowercase(
                        Locale.ROOT
                    ) in setOf("inf", "ini", "bvcu", "dat")
                }?.isNotEmpty() == true
                if (baseHasVoice || paths.isEmpty()) {
                    paths.add(base.absolutePath)
                }
            }
            // `ensureVoiceConfigs` قد تكتب ملفات `.inf` جديدة، فأي لقطة
            // تعداد سابقة صارت قديمة وتُبطَل قبل أول قراءة لاحقة.
            AcapelaVoiceManifest.invalidateVoicesCache()
            return paths.toTypedArray()
        }

        /**
         * تقسيم النص إلى قطع 200 حرف على حدود الكلمات (نهج MultiTTS).
         * الضمُّ يستعيد الأصل فلا تُبتَر كلمة.
         */
        internal fun splitAcapelaChunks(
            text: String,
            maxChars: Int = CHUNK_CHARS
        ): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val chunks = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                var end = (i + maxChars).coerceAtMost(text.length)
                if (end < text.length) {
                    if (Character.isHighSurrogate(text[end - 1])) {
                        end--
                    }
                    val lastSpace = text.lastIndexOf(' ', end - 1)
                    if (lastSpace > i + 80) {
                        end = lastSpace + 1
                    }
                }
                val chunk = text.substring(i, end).trim()
                if (chunk.isNotEmpty()) {
                    chunks.add(chunk)
                }
                i = end
            }
            return if (chunks.isEmpty()) listOf(text) else chunks
        }

        private fun matchesVoice(
            v: AcapelaVoiceManifest.VoiceInfo,
            query: String
        ): Boolean {
            val clean = AcapelaVoiceManifest.cleanAcapelaSpeaker(query)
            return v.rawSpeaker.equals(query, ignoreCase = true) ||
                v.rawSpeaker.equals(clean, ignoreCase = true) ||
                v.voiceName.equals(query, ignoreCase = true) ||
                v.voiceName.equals(clean, ignoreCase = true) ||
                v.id.equals(query, ignoreCase = true) ||
                v.dir.name.equals(query, ignoreCase = true) ||
                v.displayName.startsWith(query, ignoreCase = true) ||
                v.displayName.startsWith(clean, ignoreCase = true)
        }
    }

    private var acatts: acattsandroid? = null
    private var extendedService: AcattsandroidService? = null

    /** مفتاح الحزمة المحمّلة في [extendedService] (مجلد + تسجيل). */
    @Volatile
    private var extendedKey: String? = null

    /**
     * المرشح الناجح لكل صوت بعد فحصه مرة واحدة (مفتاح: مجلد + متحدث).
     * يُسقَط عند فشل النطق فيُعاد الفحص، وإلا تكرّر الفحص كل طلب.
     */
    private val chosenRegistrations = ConcurrentHashMap<String, Char>()

    private var dataDirectory: File? = null

    /** الاسم الذي نجح تحميله في [acatts] (الاسم الخام لا المطبّع). */
    @Volatile
    private var currentVoiceId: String? = null

    @Volatile
    private var currentVoiceKey: String? = null

    private var cachedVoices: Pair<
        Long,
        List<AcapelaVoiceManifest.VoiceInfo>
    >? = null

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
    private val totalAudioSamples = AtomicLong(0L)
    private val totalAudioBytes = AtomicLong(0L)
    private val lastAudioTime = AtomicLong(0L)

    override fun init(dataDir: String): HostResult {
        val dir = File(dataDir)
        dataDirectory = dir
        if (!dir.exists()) {
            return HostResult.failure(
                "DIR_NOT_FOUND",
                "مجلد بيانات Acapela غير موجود: ${dir.absolutePath}"
            )
        }

        try {
            System.loadLibrary("ttsacattsandroidextended")
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "libttsacattsandroidextended.so غير متوفرة: ${t.message}"
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

        // إعادة التهيئة بلا إغلاق السابقة تُبقي محركاً أصلياً حياً
        // وصوتاً محمّلاً من مسار قديم، فيُغلق السابق أولاً.
        releaseEngine()

        var pending: acattsandroid? = null
        val result = try {
            val inst = acattsandroid()
            pending = inst
            inst.setAudioListener(this)
            val callbacksRes = inst.nInitCallbacks()
            if (callbacksRes != 0) {
                Log.w(TAG, "nInitCallbacks returned $callbacksRes")
            }
            // سجلات المكتبة الأصلية مفيدة للتشخيص فقط؛ تُترك معطّلة
            // افتراضياً كي لا تُغرق Logcat في النطق العادي.
            inst.nSetLog(diagnosticsEnabled())
            val licRes = loadRuntimeLicense(inst)
            if (licRes != 0) {
                Log.w(TAG, "nSetLicense returned $licRes")
            }

            // تسجيل الأصوات قبل أي تحميل: `nGetVoiceList` هو ما يبني
            // فهرس المكتبة، وقيمته المرجعة 0 دائماً في هذا الإصدار
            // فلا تُستخدم دليل عدد.
            val extraDirs = EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            val rawPaths = collectVoiceSearchPaths(dir, extraDirs)
            val paths = rawPaths.flatMap { p ->
                val slashed = withTrailingSlash(p)
                if (slashed == p) listOf(p) else listOf(p, slashed)
            }.distinct().toTypedArray()
            val listed = inst.nGetVoiceList(paths)
            val voices = manifestVoices(dir, extraDirs)
            Log.i(
                TAG,
                "تهيئة Acapela: nGetVoiceList=$listed, " +
                    "أصوات القرص=${voices.size}, مسارات=${paths.size}"
            )
            if (voices.isEmpty()) {
                HostResult.failure(
                    "NO_VOICES",
                    "لا يوجد صوت Acapela صالح في ${dir.absolutePath}"
                )
            } else {
                acatts = inst
                pending = null
                currentVoiceId = null
                currentVoiceKey = null
                cachedVoices = EmbeddedStorage.lastBumpTime to voices
                HostResult(ok = true)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء تهيئة محرك Acapela", t)
            HostResult.failure(
                "INIT_EXCEPTION",
                t.message ?: "خطأ أثناء تهيئة Acapela"
            )
        }
        pending?.let { shutdownQuietly(it) }
        return result
    }

    private fun loadRuntimeLicense(inst: acattsandroid): Int {
        val res = try {
            context.assets.open("acapela/runtime.dat").use { stream ->
                val dataIn = DataInputStream(stream)
                val uid = dataIn.readLong()
                val pwd = dataIn.readLong()
                val len = dataIn.readInt()
                val buf = ByteArray(len)
                dataIn.readFully(buf)
                val lic = String(buf, StandardCharsets.UTF_8)
                inst.nSetLicense(uid, pwd, lic)
            }
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "قراءة runtime.dat فشلت، استخدام الثوابت: ${t.message}"
            )
            inst.nSetLicense(
                AcapelaLicense.USER_ID,
                AcapelaLicense.PASSWORD,
                AcapelaLicense.LICENSE
            )
        }
        Log.i(TAG, "كود عودة nSetLicense الكلاسيكي: $res")
        return res
    }

    /** بايتات PCM من المحرك الممتد (16 بت، قناة واحدة). */
    internal fun handleRawAudioBytes(bytes: ByteArray?, length: Int) {
        val sink = activeSink ?: return
        val cancelled = activeCancelled
        if (cancelled != null && cancelled.get()) {
            stop()
            return
        }
        if (bytes == null || length <= 0) return

        totalAudioBytes.addAndGet(length.toLong())
        totalAudioSamples.addAndGet((length / 2).toLong())

        if (!headerWritten.getAndSet(true)) {
            sink.writeHeader(currentSampleRate, 1)
        }
        sink.writePcm(bytes, length)
        audioReceived.set(true)
        lastAudioTime.set(SystemClock.uptimeMillis())
    }

    /** إغلاق محركٍ أصليّ بلا رمي: الفشل هنا لا يُفشل المسار الأصلي. */
    private fun shutdownQuietly(inst: acattsandroid) {
        runCatching { inst.nStop() }
        runCatching { inst.nShutdown() }
    }

    /** إغلاق المحرك الممتد الحالي (إن وُجد) وتصفير مفتاحه. */
    private fun closeExtended() {
        extendedService?.let { runCatching { it.nStop() } }
        extendedService?.let { runCatching { it.nShutdown() } }
        extendedService = null
        extendedKey = null
    }

    /** تحرير المحركات الحالية وتصفير حالتها (قبل إعادة التهيئة). */
    private fun releaseEngine() {
        acatts?.let { shutdownQuietly(it) }
        acatts = null
        closeExtended()
        currentVoiceId = null
        currentVoiceKey = null
        cachedVoices = null
    }

    /**
     * سرد الأصوات المتوفرة لـ Acapela من القرص (قراءة خالصة).
     */
    fun listVoices(): List<HostVoice> {
        val dir = dataDirectory ?: return emptyList()
        val manifestVoices = getCachedVoices(dir)
        return manifestVoices.map { v ->
            HostVoice(
                id = v.id,
                name = v.voiceName,
                langTag = v.locale.toLanguageTag()
            )
        }
    }

    private fun diagnosticsEnabled(): Boolean = runCatching {
        SettingsRepository.create(context).isLanguageDiagnosticsEnabled()
    }.getOrDefault(false)

    override suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        if (cancelled.get()) {
            return HostResult.failure("CANCELLED", "أُلغي الطلب قبل بدئه")
        }

        activeSink = sink
        activeCancelled = cancelled
        isSpeaking = true

        headerWritten.set(false)
        audioReceived.set(false)
        totalAudioSamples.set(0L)
        totalAudioBytes.set(0L)
        lastAudioTime.set(SystemClock.uptimeMillis())

        try {
            val dir = dataDirectory ?: return HostResult.failure(
                "DIR_NOT_FOUND",
                "مجلد بيانات Acapela لم يُضبط"
            )
            val voice = resolveVoice(req.voiceId, dir)
                ?: return HostResult.failure(
                    "VOICE_NOT_FOUND",
                    "لا يوجد صوت Acapela مطابق: ${req.voiceId}"
                )

            val chosenPath = if (voice.registration.isNotEmpty()) {
                "extended"
            } else {
                "classic"
            }
            val speakLog = "مسار النطق: path=$chosenPath, " +
                "registration.length=${voice.registration.length}, " +
                "id=${voice.id}, raw=${voice.rawSpeaker}, dir=${voice.dir.name}"
            Log.i(TAG, speakLog)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, speakLog)

            // معدل العينات من `.inf` (`freq`) هو ما يخرج به المحرك
            // الممتد؛ والكلاسيكي يصحّحه بناتج `nLoadVoice` قبل أول
            // شريحة، لأن الترويسة تُكتب عند أول صوت لا قبله.
            currentSampleRate = voice.sampleRate

            val chunks = splitAcapelaChunks(
                req.text,
                CHUNK_CHARS
            ).filter { it.isNotBlank() }
            if (chunks.isEmpty()) {
                return HostResult.failure(
                    "EMPTY_TEXT",
                    "النص المطلوب نطقه فارغ"
                )
            }

            if (voice.registration.isNotEmpty()) {
                val extended = speakExtended(
                    voice = voice,
                    chunks = chunks,
                    rate = req.rate,
                    pitch = req.pitch,
                    sink = sink,
                    cancelled = cancelled
                )
                if (extended.ok) return extended
                if (audioReceived.get()) return extended
                // فشل المسار الممتد دون أي صوت مسموع: يُجرَّب
                // المسار الكلاسيكي فوراً قبل إعلان العطل.
                Log.w(
                    TAG,
                    "فشل المسار الممتد (${extended.code}): " +
                        "${extended.message} — محاولة الكلاسيكي"
                )
                headerWritten.set(false)
                audioReceived.set(false)
                totalAudioSamples.set(0L)
                totalAudioBytes.set(0L)
                val native = speakNative(
                    voice = voice,
                    chunks = chunks,
                    rate = req.rate,
                    pitch = req.pitch,
                    sink = sink,
                    cancelled = cancelled
                )
                if (!native.ok) {
                    logVoiceDiagnostics(voice, native.code ?: "UNKNOWN")
                }
                return if (native.ok) native else extended
            }

            if (isHdPackageWithoutRegistration(voice)) {
                val hdMsg = "حزمة الصوت عالية الجودة (HD) تفتقر إلى بيانات " +
                    "التسجيل، يرجى إعادة استيراد الحزمة كاملة"
                val hdLog = "فشل النطق: HD_REGISTRATION_MISSING - " +
                    "path=classic, registration.length=0, id=${voice.id}"
                Log.w(TAG, hdLog)
                HostLog.w(HostProtocol.ENGINE_ACAPELA, hdLog)
                return HostResult.failure(
                    "HD_REGISTRATION_MISSING",
                    hdMsg
                )
            }

            val classic = speakNative(
                voice = voice,
                chunks = chunks,
                rate = req.rate,
                pitch = req.pitch,
                sink = sink,
                cancelled = cancelled
            )
            if (!classic.ok) {
                logVoiceDiagnostics(voice, classic.code ?: "UNKNOWN")
            }
            return classic
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء نطق Acapela", t)
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

    /** الصوت المطابق للطلب من كل مجلدات البحث. */
    internal fun resolveVoice(
        rawVoiceId: String,
        dir: File
    ): AcapelaVoiceManifest.VoiceInfo? {
        val cleanVoiceId = rawVoiceId
            .removePrefix("acapela_embedded:")
            .removePrefix("acapela:")
            .removePrefix("acapela_")
            .substringBefore('(')
            .trim()
        val cleanSpeaker = AcapelaVoiceManifest
            .cleanAcapelaSpeaker(cleanVoiceId)
        val voices = getCachedVoices(dir)
        if (voices.isEmpty()) return null

        if (cleanVoiceId.isNotBlank() &&
            !cleanVoiceId.equals("default", ignoreCase = true)
        ) {
            val matched = voices.firstOrNull { v ->
                matchesVoice(v, cleanVoiceId) || matchesVoice(v, cleanSpeaker)
            }
            if (matched != null) return matched

            val lang = cleanVoiceId.substringBefore('-')
                .lowercase(Locale.ROOT)
            val langMatch = voices.firstOrNull { v ->
                v.locale.language.equals(lang, ignoreCase = true)
            }
            if (langMatch != null) return langMatch
        }

        return voices.firstOrNull {
            it.locale.language.equals("ar", ignoreCase = true)
        } ?: voices.firstOrNull()
    }

    /**
     * المسار الكلاسيكي: تحميل الصوت ثم ضبط السرعة والنبرة ثم نطق
     * كل قطعة نداءً واحداً (كما يفعل MultiTTS).
     */
    private suspend fun speakNative(
        voice: AcapelaVoiceManifest.VoiceInfo,
        chunks: List<String>,
        rate: Float,
        pitch: Float,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val inst = acatts ?: return HostResult.failure(
            "NOT_INITIALIZED",
            "محرك Acapela الكلاسيكي غير مهيأ"
        )
        val nativeCapMs = SynthesisBudget.inactivityBudgetMs(
            chunks.sumOf { it.length }
        )

        if (currentVoiceKey != voice.id) {
            val loadFailure = loadVoice(inst, voice, sink, nativeCapMs)
            if (loadFailure != null) return loadFailure
        }
        applyClassicParameters(inst, rate, pitch)

        for (chunk in chunks) {
            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }

            sink.enterNativeCall(nativeCapMs)
            val speakRes = try {
                inst.nSpeak(chunk)
            } finally {
                sink.exitNativeCall()
            }
            Log.i(TAG, "كود عودة nSpeak الكلاسيكي: $speakRes")
            if (speakRes != 0) {
                Log.e(
                    TAG,
                    "فشل nSpeak: $speakRes" +
                        " voice=${voice.rawSpeaker}" +
                        " lastError=${lastErrorOf(inst)}"
                )
                return HostResult.failure(
                    "SPEAK_ERROR",
                    "فشل استدعاء nSpeak: $speakRes"
                )
            }
            awaitChunkEnd(inst, chunk.length, sink, cancelled)
        }

        val samples = totalAudioSamples.get()
        val bytes = totalAudioBytes.get()
        Log.i(
            TAG,
            "بعد nSpeak الكلاسيكي: عينات=$samples، بايتات=$bytes"
        )

        if (!audioReceived.get() || bytes == 0L) {
            Log.e(
                TAG,
                "SPEAK_NO_AUDIO: path=classic, " +
                    "registration.length=${voice.registration.length}, " +
                    "samples=$samples, bytes=$bytes, " +
                    "voice=${voice.rawSpeaker}, " +
                    "lang=${lastLanguageOf(inst)}, " +
                    "lastError=${lastErrorOf(inst)}"
            )
            return HostResult.failure(
                "SPEAK_NO_AUDIO",
                "لم يصدر المحرك أي صوت للصوت ${voice.voiceName} " +
                    "(path=classic, reg.len=${voice.registration.length})"
            )
        }
        return HostResult(ok = true)
    }

    /**
     * تحميل الصوت داخل نافذة نداءٍ أصليّ. نجاح `nLoadVoice` هو كود
     * **أكبر من صفر** (= معدل العينات)، والصفر والفشل ليسا نجاحاً.
     */
    private fun loadVoice(
        inst: acattsandroid,
        voice: AcapelaVoiceManifest.VoiceInfo,
        sink: PcmSink,
        nativeCapMs: Long
    ): HostResult? {
        val candidates = candidateNames(voice)
        var loadRes = -1
        var loadedName: String? = null
        sink.enterNativeCall(nativeCapMs)
        try {
            for (cand in candidates) {
                loadRes = inst.nLoadVoice(cand)
                Log.i(TAG, "كود عودة nLoadVoice($cand): $loadRes")
                if (loadRes > 0) {
                    loadedName = cand
                    break
                }
            }
            if (loadedName == null) {
                val voicePaths = arrayOf(
                    withTrailingSlash(voice.dir.canonicalPath),
                    withTrailingSlash(voice.dir.absolutePath)
                )
                runCatching { inst.nGetVoiceList(voicePaths) }
                for (cand in candidates) {
                    loadRes = inst.nLoadVoice(cand)
                    Log.i(
                        TAG,
                        "كود عودة nLoadVoice($cand) بعد " +
                            "nGetVoiceList: $loadRes"
                    )
                    if (loadRes > 0) {
                        loadedName = cand
                        break
                    }
                }
            }
        } finally {
            sink.exitNativeCall()
        }

        if (loadedName == null) {
            val dir = dataDirectory ?: voice.dir
            Log.e(
                TAG,
                "فشل تحميل صوت Acapela (${voice.id}): كود $loadRes, " +
                    "مرشحات=$candidates, lastError=${lastErrorOf(inst)}, " +
                    "lang=${lastLanguageOf(inst)}, ملفات=${dirFiles(dir)}"
            )
            return HostResult.failure(
                "VOICE_LOAD_FAILED",
                "تعذر تحميل الصوت: ${voice.rawSpeaker ?: voice.id}" +
                    " (كود $loadRes)"
            )
        }

        currentVoiceId = loadedName
        currentVoiceKey = voice.id
        currentSampleRate = loadRes
        Log.i(TAG, "تم تحميل صوت Acapela [$loadedName] بمعدل $loadRes")
        return null
    }

    /**
     * السرعة ثم النبرة — ترتيب MultiTTS: لا تُضبط النبرة إن فشلت
     * السرعة (فالفشل هنا يعني أن المكتبة لم تقبل الجلسة بعد).
     */
    private fun applyClassicParameters(
        inst: acattsandroid,
        rate: Float,
        pitch: Float
    ) {
        val speed = AcapelaParameters.sdkRate(calculateSpeedPercent(rate))
        val shape = AcapelaParameters.sdkPitch(calculatePitchPercent(pitch))
        val rateCode = runCatching {
            inst.nSetSpeechRate(speed.toFloat())
        }.getOrDefault(-1)
        if (rateCode != 0) {
            val err = lastErrorOf(inst)
            Log.w(
                TAG,
                "تحذير غير قاتل: nSetSpeechRate($speed) فشل: $rateCode " +
                    "(nGetLastError=$err)"
            )
        }
        val shapeCode = runCatching { inst.nSetShape(shape.toFloat()) }
            .getOrDefault(-1)
        if (shapeCode != 0) {
            val err = lastErrorOf(inst)
            Log.w(
                TAG,
                "تحذير غير قاتل: nSetShape($shape) فشل: $shapeCode " +
                    "(nGetLastError=$err)"
            )
        }
        if (diagnosticsEnabled()) {
            Log.d(
                TAG,
                "معاملات Acapela: speed=$speed shape=$shape"
            )
        }
    }

    /**
     * انتظار انتهاء القطعة: صمتٌ بعد آخر عيّنة، مع نبضات تقدّم حتى
     * لا يعدّ الحارس المحرك خاملاً. السقف ميزانية القطعة نفسها.
     */
    private suspend fun awaitChunkEnd(
        inst: acattsandroid,
        chars: Int,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ) {
        val started = SystemClock.uptimeMillis()
        val budget = SynthesisBudget.pieceTimeoutMs(chars)
        var lastPing = started
        while (!cancelled.get()) {
            val now = SystemClock.uptimeMillis()
            if (now - started >= budget) break
            val speaking = runCatching { inst.nIsSpeaking() }.getOrDefault(0)
            if (speaking == 0) {
                val silentFor = now - lastAudioTime.get()
                val quiet = if (audioReceived.get()) {
                    silentFor > TAIL_SILENCE_MS
                } else {
                    now - started > START_SILENCE_MS
                }
                if (quiet) break
            }
            if (now - lastPing >= PING_INTERVAL_MS) {
                runCatching { sink.ping() }
                lastPing = now
            }
            delay(WAIT_STEP_MS)
        }
    }

    /**
     * المسار الممتد: تهيئة الخدمة بحزمةٍ مسجّلة ثم نطق كل قطعة.
     */
    private suspend fun speakExtended(
        voice: AcapelaVoiceManifest.VoiceInfo,
        chunks: List<String>,
        rate: Float,
        pitch: Float,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val openResult = runCatching {
            openExtendedEngine(voice, sink, cancelled)
        }.getOrElse { t ->
            Log.w(TAG, "فشل تهيئة المحرك الممتد باستثناء", t)
            HostResult.failure(
                "EXTENDED_EXCEPTION",
                t.message ?: "استثناء أثناء فتح المحرك الممتد"
            )
        }
        if (openResult != null) return openResult
        val srv = extendedService ?: return HostResult.failure(
            "NOT_INITIALIZED",
            "خدمة Acapela الممتدة غير مهيأة"
        )

        val speed = calculateSpeedPercent(rate)
        val pitchPercent = calculatePitchPercent(pitch)
        val paramCode = AcapelaParameters.applyRegistered(
            speed,
            pitchPercent,
            AcapelaParameters.PropertyWriter { k, v ->
                setExtendedProperty(srv, k, v)
            }
        )
        if (paramCode != 0) {
            Log.w(
                TAG,
                "تحذير غير قاتل: تعذر ضبط بعض معاملات " +
                    "الحزمة الممتدة: $paramCode"
            )
        }

        val nativeCapMs = SynthesisBudget.inactivityBudgetMs(
            chunks.sumOf { it.length }
        )
        for (chunk in chunks) {
            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }
            sink.enterNativeCall(nativeCapMs)
            val speakRes = try {
                srv.nSpeak(chunk, null)
            } finally {
                sink.exitNativeCall()
            }
            Log.i(TAG, "كود عودة nSpeak الممتد: $speakRes")
            if (speakRes != 0) {
                Log.e(TAG, "فشل nSpeak في Acapela الممتد: $speakRes")
                forgetRegistration(voice)
                return HostResult.failure(
                    "SPEAK_ERROR",
                    "فشل استدعاء nSpeak الممتد: $speakRes"
                )
            }
            awaitExtendedChunkEnd(chunk.length, sink, cancelled)
        }

        val samples = totalAudioSamples.get()
        val bytes = totalAudioBytes.get()
        Log.i(
            TAG,
            "بعد nSpeak الممتد: عينات=$samples، بايتات=$bytes"
        )

        if (!audioReceived.get() || bytes == 0L) {
            Log.w(
                TAG,
                "SPEAK_NO_AUDIO: path=extended, " +
                    "registration.length=${voice.registration.length}, " +
                    "samples=$samples, bytes=$bytes"
            )
            forgetRegistration(voice)
            return HostResult.failure(
                "SPEAK_NO_AUDIO",
                "لم يصدر محرك Acapela الممتد أي صوت (path=extended, " +
                    "registration.length=${voice.registration.length})"
            )
        }
        return HostResult(ok = true)
    }

    /**
     * انتظار القطعة الممتدة: الخدمة لا تكشف `nIsSpeaking`، فيُعتمد
     * على الصمت بعد آخر عيّنة وبمهلة القطعة كسقف.
     */
    private suspend fun awaitExtendedChunkEnd(
        chars: Int,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ) {
        val started = SystemClock.uptimeMillis()
        val budget = SynthesisBudget.pieceTimeoutMs(chars)
        var lastPing = started
        while (!cancelled.get()) {
            val now = SystemClock.uptimeMillis()
            if (now - started >= budget) break
            val silentFor = now - lastAudioTime.get()
            val quiet = if (audioReceived.get()) {
                silentFor > TAIL_SILENCE_MS
            } else {
                now - started > START_SILENCE_MS
            }
            if (quiet) break
            if (now - lastPing >= PING_INTERVAL_MS) {
                runCatching { sink.ping() }
                lastPing = now
            }
            delay(WAIT_STEP_MS)
        }
    }

    /**
     * فتح خدمة الحزمة الممتدة مع اختيار بيانات تسجيل مقبولة.
     *
     * الفحص يقع مرة واحدة لكل صوت: يُجرَّب المرشح المحفوظ أولاً، فإن
     * فشل (أو لم يُحفظ شيء) تُجرَّب بقية المرشحات بالترتيب المقرر،
     * ويُحفظ أول مرشح ينتج عيّنات صوتية. وبعد النجاح تُفتح خدمة نطق
     * حقيقية بنفس البيانات لأن ردّ نداء الصوت مربوط عند البناء.
     *
     * يعود `null` عند الجاهزية أو فشلاً معنوناً يسمّي الخطوة.
     */
    private suspend fun openExtendedEngine(
        voice: AcapelaVoiceManifest.VoiceInfo,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult? {
        val voiceKey = registrationKey(voice)
        val candidates = registrationCandidates(
            voice.dir,
            voice.registration
        )
        if (candidates.isEmpty()) {
            return HostResult.failure(
                "REGISTRATION_MISSING",
                "لا توجد بيانات تسجيل في ${voice.dir.name}"
            )
        }

        val memoLetter = chosenRegistrations[voiceKey]
            ?: storedRegistrationLetter(voice)
        val memo = candidates.firstOrNull { it.letter == memoLetter }
        val effective = memo?.value ?: voice.registration
        if (extendedKey == "$voiceKey|$effective" &&
            extendedService != null
        ) {
            return null
        }
        closeExtended()

        val iso3 = iso3Of(voice.locale) ?: return HostResult.failure(
            "VOICE_LOCALE_UNKNOWN",
            "لغة/دولة الصوت غير معروفة: ${voice.locale.toLanguageTag()}" +
                " (${voice.configFile?.name ?: voice.dir.name})"
        )

        AcapelaVoiceManifest.normalizeExtensionsCase(voice.dir)
        File(voice.dir, "userdicos").mkdirs()

        Log.i(
            TAG,
            "بدء تهيئة المحرك الممتد: ${candidates.size} مرشحاً للتسجيل"
        )

        // المرشح المحفوظ أولاً، ثم الباقي بترتيبه المقرر. التوقّف عند
        // أول ناجح يجعل الفحص اللاحق نداءً واحداً فقط.
        val ordered = if (memoLetter == null) {
            candidates
        } else {
            candidates.sortedBy { if (it.letter == memoLetter) 0 else 1 }
        }
        val outcomes = mutableListOf<ProbeOutcome>()
        var accepted: RegistrationCandidate? = null
        var exhausted = false
        // ميزانيةٌ واحدة للفحص كلّه لا لكل مرشح: مجموعُ (فحص + نطق)
        // يجب أن يبقى داخل مهلة الطلب/المعاينة، وإلا انتهت المهلة
        // قبل ظهور أول صوت فبدا الفشل بلا سبب. والمرشحُ المرفوض
        // يعود فوراً (كود nSpeak غير صفري) فلا يستهلك من الميزانية.
        val deadline = SystemClock.uptimeMillis() +
            SynthesisBudget.registrationProbeBudgetMs()
        for (candidate in ordered) {
            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) {
                exhausted = true
                break
            }
            Log.i(
                TAG,
                "تجربة تسجيل: letter=${candidate.letter}, " +
                    "len=${candidate.value.length}, متبقٍ=${remaining}ms"
            )
            val outcome = probeRegistration(
                voice = voice,
                iso3 = iso3,
                candidate = candidate,
                sink = sink,
                cancelled = cancelled,
                waitMs = remaining
            )
            outcomes.add(outcome)
            if (outcome.accepted) {
                accepted = candidate
                break
            }
        }
        if (accepted == null) {
            logAllCandidatesFailed(outcomes, exhausted)
            return HostResult.failure(
                "REGISTRATION_REJECTED",
                "رُفضت كل بيانات التسجيل المجرَّبة: " +
                    outcomes.joinToString(",") {
                        it.candidate.letter.toString()
                    }
            )
        }

        chosenRegistrations[voiceKey] = accepted.letter
        storeRegistrationLetter(voice, accepted.letter)
        val srv = AcattsandroidService(
            { bytes, len -> handleRawAudioBytes(bytes, len) },
            accepted.value
        )
        val codes = ProbeCodes()
        val failure = configureExtended(srv, voice, iso3, codes)
        logProbe(voice, accepted, codes)
        if (failure != null) {
            runCatching { srv.nShutdown() }
            forgetRegistration(voice)
            return failure
        }
        extendedService = srv
        extendedKey = "$voiceKey|${accepted.value}"
        val speaker = voice.rawSpeaker ?: voice.voiceName
        Log.i(
            TAG,
            "حزمة Acapela ممتدة جاهزة: $speaker " +
                "(${iso3.language}-${iso3.country})، " +
                "مرشح التسجيل=${accepted.letter}"
        )
        return null
    }

    /** مفتاح حفظ اختيار التسجيل لكل صوت (مجلد + متحدث). */
    private fun registrationKey(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): String = "${voice.dir.absolutePath}|" +
        (voice.rawSpeaker ?: voice.voiceName)

    /** إسقاط الاختيار المحفوظ فيُعاد الفحص عند الطلب التالي. */
    private fun forgetRegistration(
        voice: AcapelaVoiceManifest.VoiceInfo
    ) {
        chosenRegistrations.remove(registrationKey(voice))
        runCatching {
            File(voice.dir, REGISTRATION_CHOICE_FILE).delete()
        }
    }

    /** حرف المرشح المحفوظ بجانب الصوت (إن وُجد وكان صالحاً). */
    private fun storedRegistrationLetter(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): Char? {
        val stored = runCatching {
            File(voice.dir, REGISTRATION_CHOICE_FILE)
                .takeIf { it.isFile }
                ?.readText(StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return stored.trim().firstOrNull()?.takeIf { it in 'a'..'e' }
    }

    /** حفظ حرف المرشح الناجح بجانب الصوت؛ فشل الكتابة غير قاتل. */
    private fun storeRegistrationLetter(
        voice: AcapelaVoiceManifest.VoiceInfo,
        letter: Char
    ) {
        runCatching {
            File(voice.dir, REGISTRATION_CHOICE_FILE)
                .writeText(letter.toString(), StandardCharsets.UTF_8)
        }.onFailure {
            Log.w(TAG, "تعذر حفظ اختيار التسجيل بجانب الصوت", it)
        }
    }

    /** أكواد نداءات مرشح واحد؛ الحقول غير المنفَّذة تبقى `null`. */
    private class ProbeCodes {
        var init: Int? = null
        var mode: Int? = null
        var utf8: Int? = null
        var language: Int? = null
        var voice: Int? = null
        var pause: Int? = null
        var speak: Int? = null
        var samples: Long? = null

        fun format(): String = listOfNotNull(
            init?.let { "nInit=$it" },
            mode?.let { "nSetVoiceMode=$it" },
            utf8?.let { "nUtf8Mode=$it" },
            language?.let { "nSetLanguage=$it" },
            voice?.let { "nSetCurrentVoice=$it" },
            pause?.let { "nSetProperty=$it" },
            speak?.let { "nSpeak=$it" },
            samples?.let { "samples=$it" }
        ).joinToString(", ")
    }

    private class ProbeOutcome(
        val candidate: RegistrationCandidate,
        val codes: ProbeCodes,
        val accepted: Boolean
    )

    /**
     * تسلسل التهيئة نفسه لكل مرشح، مع تسجيل كود كل نداء. يعود `null`
     * عند نجاح التسلسل، أو فشلاً معنوناً يسمّي الخطوة.
     */
    private fun configureExtended(
        srv: AcattsandroidService,
        voice: AcapelaVoiceManifest.VoiceInfo,
        iso3: Iso3,
        codes: ProbeCodes
    ): HostResult? {
        codes.init = srv.nInit(directoryArgument(voice.dir))
        if (codes.init != 0) {
            return HostResult.failure(
                "ENGINE_INIT_FAILED",
                "nInit فشل بالكود ${codes.init} في ${voice.dir.name}"
            )
        }

        codes.mode = srv.nSetVoiceMode(VOICE_MODE_HD)
        if (codes.mode != 0) {
            return HostResult.failure(
                "VOICE_MODE_FAILED",
                "nSetVoiceMode($VOICE_MODE_HD) فشل: ${codes.mode}"
            )
        }

        if (AcapelaVoiceManifest.hasUtf8Userdico(voice.dir)) {
            val utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
            codes.utf8 = utf8Res
            if (utf8Res < 0) {
                return HostResult.failure(
                    "UTF8_MODE_FAILED",
                    "nUtf8Mode فشل: $utf8Res"
                )
            }
        }

        codes.language = srv.nSetLanguage(iso3.language, iso3.country, "")
        if (codes.language != 0) {
            return HostResult.failure(
                "LANGUAGE_FAILED",
                "nSetLanguage(${iso3.language}, ${iso3.country})" +
                    " فشل: ${codes.language}"
            )
        }

        val speaker = voice.rawSpeaker ?: voice.voiceName
        codes.voice = srv.nSetCurrentVoice(speaker)
        if (codes.voice != 0) {
            return HostResult.failure(
                "VOICE_LOAD_FAILED",
                "nSetCurrentVoice($speaker) فشل: ${codes.voice}"
            )
        }

        // فشل هذا النداء تحذيري غير قاتل: لا يحجب المرشح.
        codes.pause = setExtendedProperty(
            srv,
            PUNCTUATION_PAUSE_KEY,
            PUNCTUATION_PAUSE_VALUE
        )
        return null
    }

    /**
     * تجربة مرشح واحد على خدمةٍ منفصلة بعدّاد عيّنات لا يكتب في
     * [PcmSink]، فلا يتسرّب صوت التجربة إلى نطق المستخدم.
     */
    private suspend fun probeRegistration(
        voice: AcapelaVoiceManifest.VoiceInfo,
        iso3: Iso3,
        candidate: RegistrationCandidate,
        sink: PcmSink,
        cancelled: AtomicBoolean,
        waitMs: Long
    ): ProbeOutcome {
        val samples = AtomicLong(0L)
        val codes = ProbeCodes()
        val srv = AcattsandroidService(
            { _, len ->
                if (len > 0) samples.addAndGet((len / 2).toLong())
            },
            candidate.value
        )
        var windowOpen = false
        try {
            val failure = configureExtended(srv, voice, iso3, codes)
            if (failure == null) {
                sink.enterNativeCall(waitMs)
                windowOpen = true
                val speakRes = runCatching {
                    srv.nSpeak(REGISTRATION_PROBE_TEXT, null)
                }.getOrElse { t ->
                    Log.w(TAG, "استثناء أثناء nSpeak التجريبي", t)
                    -1
                }
                codes.speak = speakRes
                // كودٌ غير صفري = رفضٌ فوري، فلا معنى لانتظار عيّناتٍ
                // لن تأتي. انتظارُه الكامل لكل مرشح هو ما أطال الفحص
                // حتى انتهت مهلة المعاينة قبل ظهور أي نتيجة.
                if (speakRes == 0) {
                    val started = SystemClock.uptimeMillis()
                    var lastPing = started
                    while (!cancelled.get() && samples.get() == 0L) {
                        val now = SystemClock.uptimeMillis()
                        if (now - started >= waitMs) break
                        if (now - lastPing >= PING_INTERVAL_MS) {
                            runCatching { sink.ping() }
                            lastPing = now
                        }
                        delay(WAIT_STEP_MS)
                    }
                }
                codes.samples = samples.get()
            }
            logProbe(voice, candidate, codes)
            if (failure != null) {
                Log.w(
                    TAG,
                    "مرشح التسجيل ${candidate.letter} مرفوض: " +
                        "${failure.code} — ${failure.message}"
                )
            }
            val accepted = failure == null &&
                codes.speak == 0 &&
                (codes.samples ?: 0L) > 0L
            return ProbeOutcome(candidate, codes, accepted)
        } finally {
            if (windowOpen) runCatching { sink.exitNativeCall() }
            runCatching { srv.nStop() }
            runCatching { srv.nShutdown() }
        }
    }

    /** سطر تشخيص واحد لكل مرشح: الحرف والطول وأكواد النداءات فقط. */
    private fun logProbe(
        voice: AcapelaVoiceManifest.VoiceInfo,
        candidate: RegistrationCandidate,
        codes: ProbeCodes
    ) {
        val speaker = voice.rawSpeaker ?: voice.voiceName
        val line = "فحص تسجيل: letter=${candidate.letter}, " +
            "len=${candidate.value.length}, " +
            "codes=[${codes.format()}], voice=$speaker"
        Log.i(TAG, line)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, line)
    }

    /** سطر واحد واضح عند رفض كل المرشحات: الحروف وأكواد العودة. */
    private fun logAllCandidatesFailed(
        outcomes: List<ProbeOutcome>,
        exhausted: Boolean
    ) {
        val spent = if (exhausted) " — نفدت ميزانية الفحص قبل تجربة الباقي"
        else ""
        val tried = if (outcomes.isEmpty()) {
            "لم يُجرَّب أي مرشح"
        } else {
            outcomes.joinToString(" | ") {
                "${it.candidate.letter}(" +
                    "len=${it.candidate.value.length}, " +
                    "${it.codes.format()})"
            }
        }
        val line = "فشل كل مرشحات التسجيل: $tried$spent"
        Log.e(TAG, line)
        HostLog.e(HostProtocol.ENGINE_ACAPELA, line)
    }

    /**
     * إخراج تشخيص القرص عند فشل النطق: جردُ مجلد الصوت، ومسارُ الرخصة
     * المتوقع، وذيلُ `logfile.txt` الذي تكتبه المكتبة الأصلية. يدخل في
     * زر «نسخ التشخيصات» عبر [HostLog]، ويُطبع كاملاً في Logcat.
     */
    private fun logVoiceDiagnostics(
        voice: AcapelaVoiceManifest.VoiceInfo,
        reason: String
    ) {
        val report = runCatching {
            AcapelaVoiceDiagnostics.report(
                rootDir = dataDirectory,
                voiceDir = voice.dir,
                configFile = voice.configFile,
                registrationLength = voice.registration.length
            )
        }.getOrElse { t ->
            "تعذر بناء التشخيص: ${t.javaClass.simpleName}"
        }
        Log.e(TAG, "تشخيص فشل الصوت ($reason):\n$report")
        HostLog.e(
            HostProtocol.ENGINE_ACAPELA,
            "تشخيص فشل الصوت ($reason):"
        )
        report.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                HostLog.e(HostProtocol.ENGINE_ACAPELA, "  $line")
            }
    }

    private fun setExtendedProperty(
        srv: AcattsandroidService,
        name: String,
        value: String
    ): Int {
        val res = runCatching { srv.nSetProperty(name, value) }
            .getOrDefault(-1)
        if (res != 0) {
            val lastErrText = acatts?.let {
                "nGetLastError=${lastErrorOf(it)}"
            } ?: "nGetLastError=غير متاح"
            Log.w(
                TAG,
                "تحذير غير قاتل: nSetProperty(name=$name, value=$value) " +
                    "فشل بالكود: $res ($lastErrText)"
            )
        }
        return res
    }

    /** وسيط `nInit`: مسار المجلد منتهياً بفاصل، كما يفعل MultiTTS. */
    private fun directoryArgument(dir: File): String {
        val path = dir.absolutePath
        return if (path.endsWith(File.separator)) path
        else path + File.separator
    }

    private data class Iso3(val language: String, val country: String)

    /**
     * رمز ISO3 للغة وللدولة معاً. الدولة ليست ترفاً: المكتبة ترفض
     * `nSetLanguage` إن غابت، وMultiTTS يشترط `ll-CC` في `.inf`.
     */
    private fun iso3Of(locale: Locale): Iso3? {
        val lang = runCatching { locale.isO3Language }
            .getOrDefault("")
        var country = runCatching { locale.isO3Country }
            .getOrDefault("")
        if (country.length != 3) {
            val defCountry = AcapelaVoiceManifest.defaultCountryForLanguage(
                locale.language
            ) ?: "SA"
            val fallbackLocale = Locale.Builder()
                .setLanguage(locale.language)
                .setRegion(defCountry)
                .build()
            country = runCatching { fallbackLocale.isO3Country }
                .getOrDefault("")
        }
        val effLang = if (lang.length == 3) lang else {
            val fallbackLocale = Locale.Builder()
                .setLanguage(locale.language.ifBlank { "ar" })
                .setRegion("SA")
                .build()
            runCatching { fallbackLocale.isO3Language }.getOrDefault("ara")
        }
        if (effLang.length != 3 || country.length != 3) return null
        return Iso3(effLang, country)
    }

    override fun onAudioAvailable(samples: ShortArray, length: Int) {
        val sink = activeSink ?: return
        val cancelled = activeCancelled
        if (cancelled != null && cancelled.get()) {
            stop()
            return
        }
        if (length <= 0) return

        totalAudioSamples.addAndGet(length.toLong())
        totalAudioBytes.addAndGet((length * 2).toLong())

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
        // أحداث المكتبة ليست موثوقة في هذا الإصدار (تعود 0 دائماً في
        // MultiTTS)، فالاكتمال يُقاس بالصمت لا بالحدث.
        if (eventId == 2L || eventId == 5L) {
            lastAudioTime.set(SystemClock.uptimeMillis())
        }
        return 0L
    }

    override fun stop() {
        runCatching { acatts?.nStop() }
        runCatching { extendedService?.nStop() }
    }

    override fun shutdown() {
        isSpeaking = false
        activeSink = null
        activeCancelled = null
        releaseEngine()
        dataDirectory = null
    }

    @Synchronized
    private fun getCachedVoices(
        dir: File
    ): List<AcapelaVoiceManifest.VoiceInfo> {
        val bump = EmbeddedStorage.lastBumpTime
        val current = cachedVoices
        if (current != null && current.first == bump) {
            return current.second
        }
        val extraDirs = EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        val fresh = manifestVoices(dir, extraDirs)
        cachedVoices = bump to fresh
        return fresh
    }

    private fun isHdPackageWithoutRegistration(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): Boolean {
        if (voice.registration.isNotEmpty()) return false
        val hasNuul = voice.dir.listFiles()?.any {
            it.name.contains(".nuul", ignoreCase = true)
        } == true
        if (hasNuul) return true
        if (voice.id.contains("_hd_", ignoreCase = true)) return true
        if (voice.configFile?.name?.contains(
                "_hd_",
                ignoreCase = true
            ) == true
        ) return true
        return false
    }

    /** أصوات القرص من كل المجلدات (قراءة خالصة، بلا كتابة). */
    private fun manifestVoices(
        dir: File,
        extraDirs: List<File>
    ): List<AcapelaVoiceManifest.VoiceInfo> =
        (listOf(dir) + extraDirs)
            .distinctBy { it.absolutePath }
            .filter { it.exists() && it.isDirectory }
            .flatMap { AcapelaVoiceManifest.listVoices(it) }
            .distinctBy {
                "${it.dir.absolutePath}|${it.id.lowercase(Locale.ROOT)}"
            }

    /**
     * أسماء مرشّحة للتحميل: الاسم الخام من `.inf` أولاً لأنه ما
     * تقارنه المكتبة داخل `nLoadVoice`، ثم أسماء التهيئة والملفات.
     */
    internal fun candidateNames(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): List<String> {
        val names = LinkedHashSet<String>()
        voice.rawSpeaker?.let(names::add)
        names.add(voice.voiceName)
        names.add(voice.id)
        names.add(voice.dir.name)
        voice.configFile?.nameWithoutExtension?.let(names::add)
        voice.dir.listFiles { f ->
            f.isFile && (f.extension.equals("inf", true) ||
                f.extension.equals("ini", true) ||
                f.extension.equals("bvcu", true) ||
                f.extension.equals("dat", true))
        }?.forEach { f ->
            names.add(f.nameWithoutExtension)
            names.add(
                AcapelaVoiceManifest.cleanAcapelaSpeaker(
                    f.nameWithoutExtension
                )
            )
        }
        val lowercased = names.map { it.lowercase(Locale.ROOT) }
        names.addAll(lowercased)
        return names.filter { it.isNotBlank() }.distinct()
    }

    private fun lastErrorOf(inst: acattsandroid): Int =
        runCatching { inst.nGetLastError() }.getOrDefault(-1)

    private fun lastLanguageOf(inst: acattsandroid): String =
        runCatching { inst.nGetLanguage() }.getOrDefault("")

    private fun dirFiles(dir: File): List<String> =
        dir.walkTopDown().maxDepth(2)
            .filter { it.isFile }
            .map { "${it.name}:${it.length()}" }
            .toList()
}
