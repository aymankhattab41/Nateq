package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.util.Log
import com.acapelagroup.android.tts.AcattsandroidService
import com.acapelagroup.android.tts.acattsandroid
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.providers.AcapelaLicense
import com.aymankhattab.nateq.core.audio.providers.AcapelaMetadataCodec
import com.aymankhattab.nateq.core.audio.providers.AcapelaParameters
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
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
         * مرشح بيانات تسجيل واحد.
         * - [letter]: حرف داخلي للحفظ والمقارنة.
         * - [label]: اسم واضح يُكتب في السجل (بلا قيمة حقيقية).
         * - [value]: القيمة الفعلية — لا تُسجَّل إطلاقاً.
         */
        internal data class RegistrationCandidate(
            val letter: Char,
            val label: String,
            val value: String
        )

        internal fun calculateSpeedPercent(rate: Float): Int =
            (rate * 100f).roundToInt().coerceIn(30, 400)

        internal fun calculatePitchPercent(pitch: Float): Int =
            (pitch * 100f).roundToInt().coerceIn(50, 200)

        /**
         * مرشحات بيانات التسجيل بالترتيب المقرر:
         *
         * **مرشحات config.yaml (الأولوية العليا):**
         * يقرأ سطر `param` من `config.yaml` في مجلد الصوت، يُقسَّم
         * على فاصلة إلى: [voiceCode, email, hexCode].
         * - `f` / email_code: email,hexCode
         * - `g` / code_email: hexCode,email
         * - `h` / code_only: hexCode وحده
         * - `i` / email_only: email وحده
         * - `j` / full_param: سطر param كاملاً
         * - `k` / voice_code: voiceCode وحده
         *
         * **مرشحات ملف 0x... (احتياطي — فقط إن غاب config.yaml
         * أو فشلت كل مرشحاته):**
         * - `a` / hex_full: المحتوى كاملاً كما هو.
         * - `b` / hex_oneline: المحتوى والأسطر مسافة.
         * - `c` / hex_first_unquoted: السطر الأول بلا اقتباس.
         * - `d` / hex_first_line: السطر الأول مع الاقتباس.
         * - `e` / hex_current: الرمز الحالي من بيانات الصوت.
         *
         * **لا يُسجَّل محتوى أي مرشح** — label والطول وأكواد العودة.
         */
        internal fun registrationCandidates(
            dir: File,
            current: String
        ): List<RegistrationCandidate> {
            val result = mutableListOf<RegistrationCandidate>()

            // 1. مرشحات config.yaml تأتي أولاً (f..k)
            val rawParam =
                AcapelaVoiceManifest.readParamFromConfigYaml(dir)
            if (!rawParam.isNullOrBlank()) {
                val parsed = AcapelaVoiceManifest
                    .splitConfigYamlParam(rawParam)
                if (parsed != null) {
                    val (voiceCode, email, hexCode) = parsed
                    val rawHex = hexCode.trim()
                    val cleanHex = rawHex
                        .removePrefix("0x").removePrefix("0X")
                    val prefixedHex = if (rawHex.startsWith("0x", true)) {
                        rawHex
                    } else {
                        "0x$rawHex"
                    }
                    listOf(
                        Triple('x', "email_code_nocomma", "$email$cleanHex"),
                        Triple(
                            'y',
                            "email_0xcode_nocomma",
                            "$email$prefixedHex"
                        ),
                        Triple('f', "email_code", "$email,$hexCode"),
                        Triple('g', "code_email", "$hexCode,$email"),
                        Triple('h', "code_only", hexCode),
                        Triple('i', "email_only", email),
                        Triple('j', "full_param", rawParam.trim()),
                        Triple('k', "voice_code", voiceCode)
                    ).filter { it.third.isNotBlank() }
                        .distinctBy { it.third }
                        .forEach { (letter, label, value) ->
                            result.add(
                                RegistrationCandidate(
                                    letter, label, value
                                )
                            )
                        }
                } else {
                    val direct = AcapelaVoiceManifest
                        .extractRegistrationFromParam(rawParam)
                    result.add(
                        RegistrationCandidate(
                            'x', "email_code_nocomma", direct
                        )
                    )
                }
            }

            // 2. مرشحات ملف 0x... (احتياطي ثانوي فقط — a..e)
            val hexFile = AcapelaVoiceManifest.findHexRegistrationFile(dir)
            val raw = hexFile?.let { file ->
                runCatching {
                    file.readText(StandardCharsets.UTF_8)
                }.getOrNull()
            }.orEmpty().removePrefix("\uFEFF")
            val full = raw.trim()
            val oneLine = full.replace(
                Regex("\\r\\n|[\\r\\n]"),
                " "
            ).trim()
            val firstLine = full.lineSequence().firstOrNull()
                ?.trim().orEmpty()
            val firstUnquoted = firstLine
                .removeSurrounding("\"").trim()
            listOf(
                Triple('a', "hex_full", full),
                Triple('b', "hex_oneline", oneLine),
                Triple('c', "hex_first_unquoted", firstUnquoted),
                Triple('d', "hex_first_line", firstLine),
                Triple('e', "hex_current", current.trim())
            ).filter { it.third.isNotEmpty() }
                .distinctBy { it.third }
                .forEach { (letter, label, value) ->
                    result.add(
                        RegistrationCandidate(letter, label, value)
                    )
                }

            return result.distinctBy { it.value }
        }

        private fun withTrailingSlash(path: String): String =
            if (path.endsWith(File.separator)) path else path + File.separator

        /**
         * مسارات البحث عن الأصوات، مع تجهيز المجلدات بما تحتاجه
         * المكتبة: تدقيق التهيئة المصطنعة وتوليد `.ini` الغائب
         * (انظر [AcapelaVoiceManifest.ensureVoiceConfigs]).
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
            // `ensureVoiceConfigs` قد تولّد ملف `.ini` غائباً، فأي لقطة
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
            val isSalmaMatch = query.contains("salma", ignoreCase = true) &&
                (v.rawSpeaker?.contains("salma", ignoreCase = true) == true ||
                    v.voiceName.contains("salma", ignoreCase = true) ||
                    v.id.contains("salma", ignoreCase = true))
            return v.rawSpeaker.equals(query, ignoreCase = true) ||
                v.rawSpeaker.equals(clean, ignoreCase = true) ||
                v.voiceName.equals(query, ignoreCase = true) ||
                v.voiceName.equals(clean, ignoreCase = true) ||
                v.id.equals(query, ignoreCase = true) ||
                v.dir.name.equals(query, ignoreCase = true) ||
                v.displayName.startsWith(query, ignoreCase = true) ||
                v.displayName.startsWith(clean, ignoreCase = true) ||
                isSalmaMatch
        }
    }

    private var acatts: acattsandroid? = null
    private var extendedService: AcattsandroidService? = null

    /** مفتاح الحزمة المحمّلة في [extendedService] (مجلد + تسجيل). */
    @Volatile
    private var extendedKey: String? = null

    /**
     * مفتاح التجربة التي فشلت في هذه الجلسة (مجلد + تسجيل).
     *
     * يمنع إعادة تجربة Acapela الكاملة (نسخ الجذر + nInit + nSpeak) عند كل
     * معاينة فاشلة. يُصفَّر عند [releaseEngine] (تهيئة جديدة) وعند نجاح
     * التجهيز وعند [forgetRegistration] (طلب فحص صريح).
     */
    @Volatile
    private var failedTrialKey: String? = null

    /**
     * المرشح الناجح لكل صوت بعد فحصه مرة واحدة (مفتاح: مجلد + متحدث).
     * يُسقَط عند فشل النطق فيُعاد الفحص، وإلا تكرّر الفحص كل طلب.
     */
    private val chosenRegistrations = ConcurrentHashMap<String, Char>()

    private val lastTrialSucceeded = AtomicBoolean(false)

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

        // فحص أصوات القرص أولاً قبل تحميل أي مكتبة ثنائية
        val extraDirs = EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        val voices = manifestVoices(dir, extraDirs)
        if (voices.isEmpty()) {
            return HostResult.failure(
                "NO_VOICES",
                "لا يوجد صوت Acapela صالح في ${dir.absolutePath}"
            )
        }

        val hasRegistered = voices.any { it.registration.isNotEmpty() }
        releaseEngine()

        if (hasRegistered) {
            // تحميل حصري للمحرك الممتد فقط (ttsacattsandroidextended)
            // ولا يتم إطلاقاً تحميل acattsandroid لمنع تضارب الرموز الأصلية.
            try {
                System.loadLibrary("ttsacattsandroidextended")
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "فشل تحميل libttsacattsandroidextended.so: ${t.message}"
                )
                return HostResult.failure(
                    "NATIVE_LOAD_ERROR",
                    "تعذر تحميل مكتبة Acapela الممتدة: ${t.message}"
                )
            }
            acatts = null
            currentVoiceId = null
            currentVoiceKey = null
            cachedVoices = EmbeddedStorage.lastBumpTime to voices
            Log.i(
                TAG,
                "تهيئة Acapela الممتد (أصوات مسجلة): " +
                    "أصوات القرص=${voices.size}"
            )
            return HostResult(ok = true)
        }

        // في حال كانت كافة الأصوات كلاسيكية غير مسجلة:
        // تحميل حصري لمكتبة acattsandroid فقط
        try {
            System.loadLibrary("acattsandroid")
        } catch (e: Throwable) {
            Log.e(TAG, "فشل تحميل libacattsandroid.so: ${e.message}")
            return HostResult.failure(
                "NATIVE_LOAD_ERROR",
                "تعذر تحميل مكتبة Acapela الكلاسيكية: ${e.message}"
            )
        }

        var pending: acattsandroid? = null
        val result = try {
            val inst = acattsandroid()
            pending = inst
            inst.setAudioListener(this)
            val callbacksRes = inst.nInitCallbacks()
            if (callbacksRes != 0) {
                Log.w(TAG, "nInitCallbacks returned $callbacksRes")
            }
            inst.nSetLog(diagnosticsEnabled())
            val licRes = loadRuntimeLicense(inst)
            if (licRes != 0) {
                Log.w(TAG, "nSetLicense returned $licRes")
            }

            val rawPaths = collectVoiceSearchPaths(dir, extraDirs)
            val paths = rawPaths.flatMap { p ->
                val slashed = withTrailingSlash(p)
                if (slashed == p) listOf(p) else listOf(p, slashed)
            }.distinct().toTypedArray()
            val listed = inst.nGetVoiceList(paths)
            acatts = inst
            pending = null
            currentVoiceId = null
            currentVoiceKey = null
            cachedVoices = EmbeddedStorage.lastBumpTime to voices
            Log.i(
                TAG,
                "تهيئة Acapela الكلاسيكي: nGetVoiceList=$listed, " +
                    "أصوات القرص=${voices.size}"
            )
            HostResult(ok = true)
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء تهيئة محرك Acapela الكلاسيكي", t)
            HostResult.failure(
                "INIT_EXCEPTION",
                t.message ?: "خطأ أثناء تهيئة Acapela الكلاسيكي"
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
        lastTrialSucceeded.set(false)
    }

    /** تحرير المحركات الحالية وتصفير حالتها (قبل إعادة التهيئة). */
    private fun releaseEngine() {
        acatts?.let { shutdownQuietly(it) }
        acatts = null
        closeExtended()
        failedTrialKey = null
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
                "id=${voice.id}, raw=${voice.rawSpeaker}, " +
                "dir=${voice.dir.name}"
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
                if (!extended.ok) {
                    logVoiceDiagnostics(voice, extended.code ?: "UNKNOWN")
                }
                return extended
            }

            if (isHdPackageWithoutRegistration(voice)) {
                val hdMsg = "حزمة الصوت عالية الجودة (HD) تفتقر إلى ملف " +
                    "config.yaml، يرجى إعادة استيراد الحزمة كاملة"
                val hdLog = "فشل النطق: CONFIG_YAML_MISSING - " +
                    "path=classic, registration.length=0, id=${voice.id}"
                Log.w(TAG, hdLog)
                HostLog.w(HostProtocol.ENGINE_ACAPELA, hdLog)
                return HostResult.failure(
                    "CONFIG_YAML_MISSING",
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
        val regLen = srv.idfunction()?.length ?: 0
        for (chunk in chunks) {
            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }
            sink.enterNativeCall(nativeCapMs)
            val synthCallback = AcapelaSynthesisCallback { bArr, len ->
                handleRawAudioBytes(bArr, len)
            }
            val speakRes = try {
                srv.nSpeak(chunk, synthCallback)
            } finally {
                sink.exitNativeCall()
            }
            val speakLog = "Acapela Extended: nSpeak=$speakRes, " +
                "regLength=$regLen, chunkChars=${chunk.length}"
            Log.i(TAG, speakLog)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, speakLog)
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
        val hexFile = AcapelaVoiceManifest.findHexRegistrationFile(voice.dir)
        if (hexFile == null || hexFile.length() <= 0L) {
            return HostResult.failure(
                "REGISTRATION_FILE_MISSING",
                "ملف الترخيص 0x... مفقود أو فارغ في ${voice.dir.name}"
            )
        }
        if (voice.sampleRate != 22050) {
            return HostResult.failure(
                "INVALID_SAMPLE_RATE",
                "محرك Acapela الممتد يتطلب معدل عينات 22050 هرتز"
            )
        }

        val voiceKey = registrationKey(voice)
        val rawParam =
            AcapelaVoiceManifest.readParamFromConfigYaml(voice.dir)
        val registration = if (!rawParam.isNullOrBlank()) {
            AcapelaVoiceManifest.extractRegistrationFromParam(
                rawParam,
                context
            )
        } else {
            voice.registration
        }
        val regLen = registration.length
        if (regLen == 0) {
            return HostResult.failure(
                "CONFIG_YAML_MISSING",
                "بيانات التسجيل مفقودة في ${voice.dir.name}"
            )
        }

        if (extendedKey == "$voiceKey|$registration" &&
            extendedService != null &&
            lastTrialSucceeded.get()
        ) {
            return null
        }
        val trialKey = "$voiceKey|$registration"
        if (failedTrialKey == trialKey) {
            return HostResult.failure(
                "VOICE_TRIAL_ALREADY_FAILED",
                "فشلت تجربة هذا الصوت/التسجيل في هذه الجلسة؛ " +
                    "أعد تهيئة المحرك (init) لتجربة جديدة"
            )
        }
        closeExtended()

        val iso3 = iso3Of(voice.locale) ?: return HostResult.failure(
            "VOICE_LOCALE_UNKNOWN",
            "لغة/دولة الصوت غير معروفة: ${voice.locale.toLanguageTag()}" +
                " (${voice.configFile?.name ?: voice.dir.name})"
        )

        AcapelaVoiceManifest.normalizeExtensionsCase(voice.dir)
        File(voice.dir, "userdicos").mkdirs()

        // تشخيص ملفات الصوت مرة واحدة عند بداية الفحص
        AcapelaVoiceManifest.logVoiceFilesDiagnostics(voice.dir)

        val pkgName = context.packageName
        Log.i(TAG, "[AcapelaPackage] اسم حزمة التطبيق: $pkgName")
        HostLog.i(
            HostProtocol.ENGINE_ACAPELA,
            "[AcapelaPackage] اسم حزمة التطبيق: $pkgName"
        )
        val rawSig = AcapelaVoiceDiagnostics.getAppSignatureSha256(context)
        val maskedSig = AcapelaVoiceDiagnostics.maskMiddle(rawSig, 8, 8)
        val rawDevId = AcapelaVoiceDiagnostics.getDeviceId(context)
        val maskedDevId = AcapelaVoiceDiagnostics.maskMiddle(rawDevId, 4, 4)
        val archInfo = AcapelaVoiceDiagnostics.getArchInfo()
        val dualLibs = AcapelaVoiceDiagnostics.hasDualArchLibs(context)

        val secLog = "[AcapelaSecurity] بصمة توقيع التطبيق (SHA-256): " +
            "$maskedSig | معرف الجهاز: $maskedDevId"
        Log.i(TAG, secLog)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, secLog)

        val archLog = "[AcapelaArch] المعمارية الحالية: $archInfo | $dualLibs"
        Log.i(TAG, archLog)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, archLog)

        val singleMsg = "[AcapelaDiag] اختبار تسجيل وحيد: " +
            "email+hexCode (طول=$regLen)"
        Log.i(TAG, singleMsg)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, singleMsg)

        val failure = configureExtendedTriple(
            voice = voice,
            iso3 = iso3,
            registration = registration,
            rawParam = rawParam,
            voiceKey = voiceKey
        )
        if (failure != null) {
            failedTrialKey = trialKey
            return failure
        }
        failedTrialKey = null
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
        failedTrialKey = null
        runCatching {
            File(voice.dir, REGISTRATION_CHOICE_FILE).delete()
        }
        extendedKey = null
    }

    private class TrialAudio(
        private val onSamples: (ByteArray?, Int) -> Unit
    ) : AcattsandroidService.Audio {
        var probeOnly = true
        val samples = AtomicLong(0L)
        val pcmBytes = ByteArrayOutputStream()

        override fun samples(bArr: ByteArray?, i: Int) {
            if (i > 0) {
                samples.addAndGet((i / 2).toLong())
                if (bArr != null) {
                    synchronized(pcmBytes) {
                        pcmBytes.write(bArr, 0, i)
                    }
                }
            }
            if (!probeOnly) {
                onSamples(bArr, i)
            }
        }
    }

    internal class AcapelaSynthesisCallback(
        private val onAudioChunk: ((ByteArray, Int) -> Unit)? = null
    ) : SynthesisCallback {
        val samples = AtomicLong(0L)
        val bytes = AtomicLong(0L)
        val pcmStream = ByteArrayOutputStream()
        private val started = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)

        override fun getMaxBufferSize(): Int = 8192

        override fun start(
            sampleRateInHz: Int,
            audioFormat: Int,
            channelCount: Int
        ): Int {
            started.set(true)
            return 0
        }

        override fun audioAvailable(
            buffer: ByteArray?,
            offset: Int,
            length: Int
        ): Int {
            if (buffer != null && length > 0) {
                val safeOffset = offset.coerceAtLeast(0)
                val safeLen = length.coerceAtMost(buffer.size - safeOffset)
                if (safeLen > 0) {
                    samples.addAndGet((safeLen / 2).toLong())
                    bytes.addAndGet(safeLen.toLong())
                    synchronized(pcmStream) {
                        pcmStream.write(buffer, safeOffset, safeLen)
                    }
                    onAudioChunk?.invoke(buffer, safeLen)
                }
            }
            return 0
        }

        override fun done(): Int {
            finished.set(true)
            return 0
        }

        override fun error() {
            finished.set(true)
        }

        override fun error(errorCode: Int) {
            finished.set(true)
        }

        override fun hasStarted(): Boolean = started.get()

        override fun hasFinished(): Boolean = finished.get()
    }

    internal enum class AcapelaSequenceOrder {
        UTF8_LANG_VOICE,
        LANG_VOICE_UTF8,
        LANG_UTF8_VOICE,
        VOICE_LANG_UTF8,
        VOICE_UTF8_LANG,
        NO_UTF8,
        NO_UTF8_VOICE_LANG
    }

    private data class VoiceLoadTrialResult(
        val method: String,
        val path: String,
        val initCode: Int,
        val modeCode: Int,
        val utf8Code: Int? = null,
        val langCode: Int,
        val voiceCode: Int,
        val speakCode: Int,
        val samples: Long,
        val service: AcattsandroidService?,
        val audioHandler: TrialAudio,
        val synthCallback: AcapelaSynthesisCallback,
        val details: String = "",
        val nlpErrorCode: Int? = null,
        val ebErrorCode: Int? = null,
        val nativeLines: List<String> = emptyList(),
        val sNlpCode: Int? = null,
        val openFiles: List<String> = emptyList(),
        val logFileContent: String = "",
        val firstErrorLine: String? = null,
        val precedingLine: String? = null,
        val getLanguageResult: String? = null,
        val closestVoiceResult: String? = null,
        val isLangAvailResult: Int? = null
    ) {
        val isSuccess: Boolean
            get() = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = speakCode,
                samples = samples,
                nativeLines = nativeLines
            )
    }

    private fun extractVoiceNameFromConfigYaml(dir: File): String? {
        val rawParam = AcapelaVoiceManifest.readParamFromConfigYaml(dir)
        if (!rawParam.isNullOrBlank()) {
            val parsed = AcapelaVoiceManifest.splitConfigYamlParam(rawParam)
            if (parsed != null && parsed.first.isNotBlank()) {
                return parsed.first.trim()
            }
        }
        val yamlFile = File(dir, "config.yaml")
            .takeIf { it.isFile }
            ?: dir.parentFile?.let { File(it, "config.yaml") }
                ?.takeIf { it.isFile }
        if (yamlFile != null) {
            val content = runCatching {
                yamlFile.readText(StandardCharsets.UTF_8)
            }.getOrNull().orEmpty()
            val speakerMatch = Regex(
                "(?m)^speaker\\s*:\\s*[\"']?([^\"'\\r\\n]+)[\"']?"
            ).find(content)?.groupValues?.get(1)?.trim()
            if (!speakerMatch.isNullOrBlank()) return speakerMatch
            val nameMatch = Regex(
                "(?m)^name\\s*:\\s*[\"']?([^\"'\\r\\n]+)[\"']?"
            ).find(content)?.groupValues?.get(1)?.trim()
            if (!nameMatch.isNullOrBlank()) return nameMatch
        }
        return null
    }

    private fun findBvcuFile(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): File? {
        val roots = listOfNotNull(
            voice.dir,
            voice.dir.parentFile,
            File(context.filesDir, "Acapela"),
            File(context.filesDir, "acapela"),
            context.filesDir,
            dataDirectory,
            context.cacheDir
        ).distinctBy { it.absolutePath }

        for (root in roots) {
            if (!root.exists()) continue
            val found = root.walkTopDown().firstOrNull { f ->
                f.isFile &&
                    f.extension.equals("bvcu", ignoreCase = true) &&
                    f.length() > 50_000L
            }
            if (found != null) return found
        }
        return null
    }

    private fun buildNestedFileTree(
        root: File,
        label: String,
        maxDepth: Int = 6
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== شجرة مجلد $label: ${root.absolutePath} ===")
        if (!root.exists()) {
            sb.appendLine("  (المجلد غير موجود)")
            return sb.toString()
        }
        if (!root.isDirectory) {
            sb.appendLine("  - ${root.name} (${root.length()} بايت)")
            return sb.toString()
        }
        fun walk(dir: File, indent: String, depth: Int) {
            if (depth > maxDepth) {
                sb.appendLine("$indent... [تجاوز أقصى عمق]")
                return
            }
            val entries = dir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            ) ?: return
            if (entries.isEmpty()) {
                sb.appendLine("$indent(مجلد فارغ)")
                return
            }
            for (entry in entries) {
                if (entry.isDirectory) {
                    sb.appendLine("$indent[مجلد] ${entry.name}/")
                    walk(entry, "$indent  ", depth + 1)
                } else {
                    val len = entry.length()
                    val isBvcu = entry.extension
                        .equals("bvcu", ignoreCase = true)
                    val isBig = len >= 1_000_000L
                    val tag = when {
                        isBvcu && isBig ->
                            " *** [ملف BVCU كبير: $len بايت] ***"
                        isBvcu -> " * [ملف BVCU: $len بايت] *"
                        isBig -> " * [ملف كبير: $len بايت] *"
                        else -> ""
                    }
                    sb.appendLine("$indent- ${entry.name} ($len بايت)$tag")
                }
            }
        }
        walk(root, "  ", 1)
        return sb.toString()
    }

    private fun logDiagnosticTrees(
        voice: AcapelaVoiceManifest.VoiceInfo
    ) {
        val bvcuFile = findBvcuFile(voice)
        val filesAcapela = File(context.filesDir, "Acapela")
        val altAcapela = File(context.filesDir, "acapela")
        val cacheStage = File(context.cacheDir, "acapela_stage")
        val cacheDirect = File(context.cacheDir, "acapela_stage_direct")
        val cacheParent = File(context.cacheDir, "acapela_stage_parent")

        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("[تقرير جرد ملفات ومجلدات Acapela المتداخلة بالبايت]")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("[1] موقع ملف بيانات صوت Salma الكبير (.bvcu):")
        if (bvcuFile != null) {
            val parent = bvcuFile.parentFile
            val grandParent = parent?.parentFile
            val path = bvcuFile.absolutePath
            sb.appendLine("  * المسار الكامل: $path")
            sb.appendLine("  * اسم الملف: ${bvcuFile.name}")
            sb.appendLine("  * الحجم: ${bvcuFile.length()} بايت")
            sb.appendLine(
                "  * المجلد الحاوي المباشر: ${parent?.name ?: "غير معروف"}"
            )
            sb.appendLine(
                "  * المجلد الأب: ${grandParent?.name ?: "غير معروف"}"
            )
            sb.appendLine("  * داخل Salma: ${path.contains("Salma")}")
            sb.appendLine("  * داخل salma_lf: ${path.contains("salma_lf")}")
            sb.appendLine(
                "  * داخل acapela_ara-SAU-Salma: " +
                    path.contains("acapela_ara-SAU-Salma")
            )
        } else {
            sb.appendLine(
                "  * تنبيه: لم يُعثر على أي ملف .bvcu (>50KB) " +
                    "في files أو cache!"
            )
        }
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("[2] شجرة مجلد files/Acapela:")
        sb.append(buildNestedFileTree(filesAcapela, "files/Acapela"))
        if (altAcapela.exists() && altAcapela != filesAcapela) {
            sb.append(buildNestedFileTree(altAcapela, "files/acapela"))
        }
        if (voice.dir.exists() &&
            !voice.dir.absolutePath.startsWith(filesAcapela.absolutePath)
        ) {
            sb.append(
                buildNestedFileTree(
                    voice.dir,
                    "voice.dir (${voice.dir.name})"
                )
            )
        }
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("[3] شجرة مجلدات cache/acapela_stage:")
        val cacheDirs = listOf(cacheStage, cacheDirect, cacheParent)
            .filter { it.exists() }
        if (cacheDirs.isNotEmpty()) {
            cacheDirs.forEach { dir ->
                sb.append(buildNestedFileTree(dir, "cache/${dir.name}"))
            }
        } else {
            sb.appendLine("  (لا توجد مجلدات مرحلية في cache)")
        }
        sb.appendLine("==================================================")

        val fullText = sb.toString().trimEnd()
        Log.i(TAG, fullText)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, fullText)
    }

    private fun checkAudioFilesIntegrity(
        voiceDir: File
    ): Boolean {
        val files = voiceDir.listFiles() ?: emptyArray()
        val bvcuFiles = files.filter {
            it.isFile && it.extension.equals("bvcu", ignoreCase = true)
        }
        val datFiles = files.filter {
            it.isFile && (
                it.extension.equals("dat", ignoreCase = true) ||
                it.extension.equals("bin", ignoreCase = true)
            )
        }
        val zeroFiles = files.filter { it.isFile && it.length() == 0L }

        val sb = StringBuilder()
        sb.appendLine("[فحص سلامة ملفات الصوت]")
        if (bvcuFiles.isNotEmpty()) {
            sb.appendLine(
                "  ملف الصوت الأساسي (.bvcu): " +
                    bvcuFiles.joinToString { "${it.name}(${it.length()}B)" }
            )
        } else {
            sb.appendLine("  ملف الصوت الأساسي (.bvcu): غير موجود!")
        }
        if (datFiles.isNotEmpty()) {
            sb.appendLine(
                "  ملفات .dat/.bin المرتبطة: " +
                    datFiles.joinToString { "${it.name}(${it.length()}B)" }
            )
        } else {
            sb.appendLine("  ملفات .dat/.bin: لا توجد ملفات بهذا الامتداد")
        }
        if (zeroFiles.isNotEmpty()) {
            zeroFiles.forEach {
                sb.appendLine("  ملف ناقص أو حجمه صفر: ${it.name} (0 بايت)")
            }
        } else {
            sb.appendLine("  لا توجد ملفات ناقصة أو بحجم صفر")
        }
        val text = sb.toString().trimEnd()
        Log.i(TAG, text)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, text)
        return bvcuFiles.isNotEmpty() && zeroFiles.isEmpty()
    }

    private fun recordAcapelaBreadcrumb(call: String) {
        val ts = SimpleDateFormat(
            "HH:mm:ss.SSS",
            Locale.US
        ).format(Date())
        val line = "[$ts] $call"
        runCatching {
            File(context.cacheDir, "last_acapela_call.txt")
                .writeText("$line\n", StandardCharsets.UTF_8)
        }
        // [بناء 10729] كل خطوة أصلية تُدفَّع فوراً للملف المستقر.
        appendAcapelaFullDiagnostic(line)
    }

    private fun recordTrialProgress(trial: VoiceLoadTrialResult) {
        runCatching {
            val f = File(context.cacheDir, "acapela_trials_progress.txt")
            val line = "${trial.method} => كود nSpeak: ${trial.speakCode} " +
                "| عينات PCM: ${trial.samples}\n"
            f.appendText(line, StandardCharsets.UTF_8)
        }
    }

    /**
     * [بناء 10729] بدء الملف المستقر: يُفرَّغ مرة واحدة عند كل اختبار،
     * ثم يُكتب إليه تدريجياً. الفصل بين البدء والإلحاق يمنع محو ما
     * كُتب من خطوات سابقة عند كل إلحاق.
     */
    private fun beginAcapelaFullDiagnostic() {
        runCatching {
            val file = File(
                context.cacheDir,
                AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
            )
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
        }
        appendAcapelaFullDiagnostic(
            "=== بدء تشخيص Acapela الكامل (بناء 10729) ==="
        )
    }

    /**
     * [بناء 10729] إلحاق قسم بالملف المستقر مع تدفيع فوري بعد كل خطوة
     * (`flush` ثم `fd.sync`) كي ينجو الجزء المكتوب من انهيار
     * عملية `:engine_host` في منتصف الاختبار.
     */
    private fun appendAcapelaFullDiagnostic(text: String) {
        runCatching {
            val file = File(
                context.cacheDir,
                AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
            )
            FileOutputStream(file, true).use { out ->
                out.write(text.toByteArray(StandardCharsets.UTF_8))
                if (!text.endsWith("\n")) out.write('\n'.code)
                out.flush()
                out.fd.sync()
            }
        }
    }

    /**
     * تجربة تحميل صوت واحدة على المحرك الممتد فقط.
     *
     * **[بناء 10728] لا تُحمَّل `libacattsandroid.so` هنا إطلاقاً:** كل
     * استدعاء كلاسيكي (`nSetLog` و`nSetLicense` و`nGetVoiceList` و
     * `nGetVersion` و`nGetLastError`) كان يحمّل المكتبة الكلاسيكية مع
     * الممتدة في العملية نفسها، وهو ما تخالفُه قاعدة المستودع ويُفسد
     * حالة`nInit`. الرخصة تأتي من بيانات التسجيل الممرَّرة للخدمة.
     */
    private suspend fun executeVoiceLoadTrial(
        label: String,
        trialDir: File,
        speaker: String,
        iso3: Iso3,
        registration: String,
        voiceMode: String = VOICE_MODE_HD,
        extraProperties: Map<String, String> = emptyMap(),
        probeText: String = REGISTRATION_PROBE_TEXT,
        trailingSlash: Boolean = true,
        order: AcapelaSequenceOrder = AcapelaSequenceOrder.UTF8_LANG_VOICE,
        useRealCallback: Boolean = true,
        forceCallVoice: Boolean = true,
        forceVoiceEvenIfInitFailed: Boolean = false,
        allowRetry: Boolean = true,
        probeFiles: Boolean = false
    ): VoiceLoadTrialResult {
        val rawPath = trialDir.absolutePath
        val stagePath = if (trailingSlash) {
            directoryArgument(trialDir)
        } else {
            rawPath.trimEnd(File.separatorChar, '/')
        }

        // حذف ملف logfile.txt القديم من مجلد التجربة قبل التشغيل (البند 2)
        val trialLogFile = File(trialDir, "logfile.txt")
        if (trialLogFile.exists()) {
            runCatching { trialLogFile.delete() }
        }

        val audioHandler = TrialAudio { bytes, len ->
            handleRawAudioBytes(bytes, len)
        }
        audioHandler.probeOnly = true
        val synthCallback = AcapelaSynthesisCallback { bytes, len ->
            handleRawAudioBytes(bytes, len)
        }

        val srv = AcattsandroidService(
            audioHandler,
            registration
        )

        val idFuncVal = runCatching {
            srv.idfunction()
        }.getOrNull().orEmpty()
        val maskedReg = if (idFuncVal.length > 8) {
            idFuncVal.take(4) + "*".repeat(idFuncVal.length - 8) +
                idFuncVal.takeLast(4)
        } else if (idFuncVal.isNotEmpty()) {
            "****"
        } else {
            "(فارغ)"
        }

        val cleanLabel = label.filter { it.isLetterOrDigit() }
        val startMarker = "=== START_TRIAL_$cleanLabel ==="
        val endMarker = "=== END_TRIAL_$cleanLabel ==="
        Log.i("acattsandroid-extended-native", startMarker)

        recordAcapelaBreadcrumb("[$label] nInit($stagePath)")
        // رصد الملفات التي تفتحها المكتبة فعلاً (بناء 102): نراقب مجلد
        // nInit وحده ونجمع واصفات /proc/self/fd أثناء nInit تحديداً.
        val fileProbe = if (probeFiles) {
            NativeFileProbe.startSession(
                context = context,
                engineId = HostProtocol.ENGINE_ACAPELA,
                extraDir = trialDir,
                onlyExtraDir = true
            )
        } else {
            null
        }
        val fdsBefore = AcapelaVoiceDiagnostics.getOpenFileDescriptors()
        var probeReport = ""
        var initRes = -1
        try {
            initRes = srv.nInit(stagePath)
        } finally {
            probeReport = runCatching { fileProbe?.stopAndLog() }
                .getOrNull().orEmpty()
        }
        // [بناء 10728] prep_hd يُضبط قبل تحميل الصوت حتى لو أعاد nInit
        // كوداً غير صفري: المسار الوحيد المعتمد يعتمد على بيانات التسجيل
        // ويمرّر الصوت رغم فشل nInit، وبدون نمط prep_hd يبقى المحرك بلا
        // نطق (nSpeak = -2). تُسجَّل نتيجة nSetVoiceMode صريحةً.
        val modeRes = if (initRes == 0 || forceVoiceEvenIfInitFailed) {
            recordAcapelaBreadcrumb("[$label] nSetVoiceMode($voiceMode)")
            val code = runCatching { srv.nSetVoiceMode(voiceMode) }
                .getOrElse { -99 }
            recordAcapelaBreadcrumb(
                "[$label] nSetVoiceMode($voiceMode) => $code"
            )
            Log.i(TAG, "[$label] nSetVoiceMode($voiceMode) => $code")
            code
        } else -1
        val modeApplied = modeRes == 0

        val extraResMap = mutableMapOf<String, Int>()
        if (initRes == 0) {
            for ((propKey, propVal) in extraProperties) {
                val res = setExtendedProperty(srv, propKey, propVal)
                extraResMap[propKey] = res
            }
        }

        // قراءة السجل الأولي لفحص نجاح تحميل الصوت
        // بواسطة tts_function_load_voice
        val earlyLogs = AcapelaVoiceDiagnostics.readNativeLogcatLines(100)
        val earlyLoadLine = earlyLogs.lastOrNull {
            it.contains("tts_function_load_voice")
        }.orEmpty()
        val earlyEbMatch = Regex("eb\\s+(-?\\d+)").find(earlyLoadLine)
        val earlyEbVal = earlyEbMatch?.groupValues?.get(1)?.toIntOrNull()
        val isVoiceLoaded = earlyLoadLine.contains("- done") &&
            (earlyEbVal == null || earlyEbVal == 0)

        var utf8Res: Int? = null
        var langRes = -1
        var voiceRes = -1

        // إذا كان forceCallVoice مفعلاً (10709) أو كان الصوت محملاً
        // (10726) أو طُلب نداء الصوت رغم فشل nInit كما في MultiTTS
        if ((initRes == 0 && (forceCallVoice || isVoiceLoaded)) ||
            forceVoiceEvenIfInitFailed
        ) {
            when (order) {
                AcapelaSequenceOrder.UTF8_LANG_VOICE -> {
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    voiceRes = srv.nSetCurrentVoice(speaker)
                }
                AcapelaSequenceOrder.LANG_VOICE_UTF8 -> {
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                }
                AcapelaSequenceOrder.LANG_UTF8_VOICE -> {
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                    voiceRes = srv.nSetCurrentVoice(speaker)
                }
                AcapelaSequenceOrder.VOICE_LANG_UTF8 -> {
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                }
                AcapelaSequenceOrder.VOICE_UTF8_LANG -> {
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                }
                AcapelaSequenceOrder.NO_UTF8 -> {
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    voiceRes = srv.nSetCurrentVoice(speaker)
                }
                AcapelaSequenceOrder.NO_UTF8_VOICE_LANG -> {
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                }
            }
        } else if (initRes == 0) {
            // نضبط وضع UTF-8 واللغة فقط دون لمس الصوت
            if (order != AcapelaSequenceOrder.NO_UTF8 &&
                order != AcapelaSequenceOrder.NO_UTF8_VOICE_LANG
            ) {
                utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
            }
            langRes = srv.nSetLanguage(iso3.language, iso3.country, "")
            recordAcapelaBreadcrumb(
                "[$label] تم تخطي nSetCurrentVoice لعدم نجاح تحميل الصوت"
            )
        }

        var triedAltVoice: String? = null
        var altVoiceRes: Int? = null

        if (initRes == 0 && isVoiceLoaded && voiceRes != 0 &&
            speaker != trialDir.name
        ) {
            val alt = trialDir.name
            triedAltVoice = alt
            val res = srv.nSetCurrentVoice(alt)
            altVoiceRes = res
            if (res == 0) voiceRes = res
        }

        val pauseRes = if (initRes == 0) {
            setExtendedProperty(
                srv,
                PUNCTUATION_PAUSE_KEY,
                PUNCTUATION_PAUSE_VALUE
            )
        } else null

        val prefRes = if (initRes == 0 && isVoiceLoaded) {
            runCatching {
                srv.nSetPreferedVoice(iso3.language, iso3.country, speaker)
            }.getOrElse { -99 }
        } else null

        // لا نستدعي nRefrehEnumeration أبداً لأنها
        // تنادي unload_voices وتسبب انهياراً
        val enumRes: Int? = null

        val loadLangRes = if (initRes == 0 && isVoiceLoaded) {
            runCatching {
                srv.nloadLanguage(iso3.language, iso3.country, "")
            }.getOrElse { -99 }
        } else null

        // استدعاءات فحص توفر وحصر الأصوات واللغة (البند 4 لبناء 10724/10725)
        val getLangRes = if (initRes == 0) {
            runCatching {
                srv.nGetLanguage()?.joinToString(", ")
            }.onFailure { t ->
                Log.w(TAG, "[$label] فشل nGetLanguage", t)
            }.getOrNull()
        } else null
        val closestVoiceRes = if (initRes == 0) {
            runCatching {
                srv.nGetClosestVoice(iso3.language, iso3.country, "")
            }.getOrNull()
        } else null
        val isLangAvailRes = if (initRes == 0) {
            runCatching {
                srv.nIsLanguageAvailable(iso3.language, iso3.country, "")
            }.getOrNull()
        } else null

        val cbToPass = if (useRealCallback) synthCallback else null
        val cbName = if (cbToPass != null) {
            "AcapelaSynthesisCallback"
        } else {
            "null"
        }
        recordAcapelaBreadcrumb(
            "[$label] nSpeak(text='$probeText', cb=$cbName)"
        )
        val canSpeak = initRes == 0 || forceVoiceEvenIfInitFailed
        val speakRes = if (canSpeak) {
            runCatching {
                srv.nSpeak(probeText, cbToPass)
            }.getOrElse { t ->
                Log.w(TAG, "[$label] استثناء أثناء nSpeak", t)
                -2
            }
        } else -1
        Log.i("acattsandroid-extended-native", endMarker)
        recordAcapelaBreadcrumb(
            "[$label] انتهى nSpeak: كود=$speakRes"
        )

        var retryVoiceRes: Int? = null
        var retrySpeakRes: Int? = null
        var retrySamples: Long? = null
        if (allowRetry && initRes == 0 && speakRes == -2 &&
            isVoiceLoaded
        ) {
            retryVoiceRes = runCatching {
                srv.nSetCurrentVoice(speaker)
            }.getOrElse { -99 }
            val retryStart = "=== RETRY_NSPEAK_$cleanLabel ==="
            val retryEnd = "=== END_RETRY_NSPEAK_$cleanLabel ==="
            Log.i("acattsandroid-extended-native", retryStart)
            retrySpeakRes = runCatching {
                srv.nSpeak(probeText, cbToPass)
            }.getOrElse { -99 }
            Log.i("acattsandroid-extended-native", retryEnd)
            if (retrySpeakRes == 0) {
                val started = SystemClock.uptimeMillis()
                while (SystemClock.uptimeMillis() - started < 1500L &&
                    audioHandler.samples.get() == 0L &&
                    synthCallback.samples.get() == 0L
                ) {
                    delay(50L)
                }
            }
            retrySamples = maxOf(
                audioHandler.samples.get(),
                synthCallback.samples.get()
            )
        }

        if (speakRes == 0) {
            val started = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - started < 1500L &&
                audioHandler.samples.get() == 0L &&
                synthCallback.samples.get() == 0L
            ) {
                delay(50L)
            }
        }

        val totalSamples = maxOf(
            maxOf(audioHandler.samples.get(), synthCallback.samples.get()),
            retrySamples ?: 0L
        )
        val finalSpeakCode = if (retrySpeakRes == 0) 0 else speakRes
        if (synthCallback.pcmStream.size() > 0) {
            val synthBytes = synthCallback.pcmStream.toByteArray()
            synchronized(audioHandler.pcmBytes) {
                if (audioHandler.pcmBytes.size() == 0) {
                    audioHandler.pcmBytes.write(synthBytes)
                }
            }
        }

        val nativeTail = AcapelaVoiceDiagnostics.readLogcatBetweenMarkers(
            startMarker,
            endMarker,
            1000
        )
        // يُفضّل سجل جذر التجربة؛ وإن كتبته المكتبة في مستوى أعمق يُقرأ
        // أحدث سجل غير فارغ داخل الشجرة (البند 2 من 10724/10726)
        val effectiveLogFile = trialLogFile.takeIf {
            it.exists() && it.length() > 0L
        } ?: trialDir.walkTopDown()
            .filter {
                it.isFile && it.length() > 0L &&
                    it.name.equals(AcapelaVoiceDiagnostics.LOG_FILE_NAME, true)
            }
            .maxByOrNull { it.lastModified() }
        val logFileContent = if (effectiveLogFile != null) {
            runCatching {
                effectiveLogFile.readText(StandardCharsets.ISO_8859_1)
            }.getOrDefault("(تعذر قراءة logfile.txt)")
        } else {
            "(لم يُنشأ ملف logfile.txt أو فارغ)"
        }
        val firstErr = AcapelaVoiceDiagnostics
            .findFirstErrorAndPreceding(nativeTail)
        val fdsAfter = AcapelaVoiceDiagnostics.getOpenFileDescriptors()
        val newFds = (fdsAfter - fdsBefore.toSet()).distinct()
        val logFiles = AcapelaVoiceDiagnostics
            .extractFilesFromLogLines(nativeTail)
        val openedFiles = (newFds + logFiles).distinct()
        val ebCode = nativeTail.firstNotNullOfOrNull { line ->
            if (line.contains("eb", ignoreCase = true)) {
                val match = Regex("eb\\s*[:=]?\\s*(-?\\d+)").find(line)
                match?.groupValues?.get(1)?.toIntOrNull()
            } else null
        } ?: if (!forceCallVoice) earlyEbVal else null
        val nlpCode = nativeTail.firstNotNullOfOrNull { line ->
            if (line.contains("nlpInitError", ignoreCase = true)) {
                val match = Regex(
                    "nlpInitError\\s*[:=]?\\s*(-?\\d+)"
                ).find(line)
                match?.groupValues?.get(1)?.toIntOrNull()
            } else null
        }
        val sNlpCode = nativeTail.firstNotNullOfOrNull { line ->
            if (line.contains("sNLPConfig", ignoreCase = true)) {
                val match = Regex(
                    "sNLPConfig\\s*[:=]?\\s*(-?\\d+)"
                ).find(line)
                match?.groupValues?.get(1)?.toIntOrNull()
            } else null
        } ?: 0

        val loadVoiceLine = nativeTail.firstOrNull {
            it.contains("tts_function_load_voice")
        } ?: earlyLoadLine

        val cbDesc = if (useRealCallback) "SynthesisCallback" else "null"
        val sb = StringBuilder()
        sb.appendLine("[$label - وسائط النداءات وأكواد الإرجاع الحرفية]")
        sb.appendLine(
            "  === [البند هـ] قيم معاملات nInit التي مُرِّرت فعلياً ==="
        )
        sb.appendLine("  - path = \"$stagePath\"")
        sb.appendLine("  - trailingSlash = $trailingSlash")
        sb.appendLine("  - speaker = \"$speaker\"")
        sb.appendLine(
            "  - lang = \"${iso3.language}\", country = \"${iso3.country}\""
        )
        sb.appendLine("  - voiceMode = \"$voiceMode\"")
        sb.appendLine("  - order = $order")
        sb.appendLine(
            "  - registration.length = ${registration.length} (القيمة مخفية)"
        )
        sb.appendLine("  - nInit => كود: $initRes")
        sb.appendLine(
            "  - nSetVoiceMode(mode = \"$voiceMode\") => كود: $modeRes " +
                "(طُبِّق قبل تحميل الصوت: $modeApplied)"
        )
        for ((k, v) in extraResMap) {
            sb.appendLine("  - nSetProperty(name = \"$k\") => كود: $v")
        }
        if (utf8Res != null) {
            sb.appendLine(
                "  - nUtf8Mode(lang = \"${iso3.language}\", " +
                    "country = \"${iso3.country}\") => كود: $utf8Res"
            )
        }
        sb.appendLine(
            "  - nSetLanguage(lang = \"${iso3.language}\", " +
                "country = \"${iso3.country}\", " +
                "variant = \"\") => كود: $langRes"
        )
        if (forceCallVoice || isVoiceLoaded || forceVoiceEvenIfInitFailed) {
            sb.appendLine(
                "  - nSetCurrentVoice(speaker = \"$speaker\") => " +
                    "كود: $voiceRes"
            )
        } else {
            sb.appendLine(
                "  - nSetCurrentVoice: تم تخطيه صراحة لعدم نجاح تحميل " +
                    "الصوت (البند 4)"
            )
        }
        if (altVoiceRes != null) {
            sb.appendLine(
                "  - nSetCurrentVoice(بديل = \"$triedAltVoice\") => " +
                    "كود: $altVoiceRes"
            )
        }
        if (prefRes != null) {
            sb.appendLine(
                "  - nSetPreferedVoice(lang = \"${iso3.language}\", " +
                    "country = \"${iso3.country}\", " +
                    "voice = \"$speaker\") => كود: $prefRes"
            )
        }
        if (enumRes != null) {
            sb.appendLine(
                "  - nRefrehEnumeration() => كود: $enumRes"
            )
        }
        if (loadLangRes != null) {
            sb.appendLine(
                "  - nloadLanguage(lang = \"${iso3.language}\", " +
                    "country = \"${iso3.country}\", " +
                    "variant = \"\") => كود: $loadLangRes"
            )
        }
        if (pauseRes != null) {
            sb.appendLine(
                "  - nSetProperty(name = \"$PUNCTUATION_PAUSE_KEY\", " +
                    "value = \"$PUNCTUATION_PAUSE_VALUE\") => كود: $pauseRes"
            )
        }
        sb.appendLine(
            "  - ترخيص الخدمة (idfunction): \"$maskedReg\" " +
                "(طول=${idFuncVal.length})"
        )
        sb.appendLine(
            "  - nSpeak(text = \"$probeText\", " +
                "callback = $cbDesc) => كود: $speakRes"
        )
        sb.appendLine("  - كود الإرجاع الحرفي لـ nSpeak: $speakRes")
        sb.appendLine(
            "  - هل بقي nSpeak يعيد -2: " +
                if (speakRes == -2) "نعم (-2)" else "لا ($speakRes)"
        )
        sb.appendLine("  - عدد عينات PCM: $totalSamples")
        if (retrySpeakRes != null) {
            sb.appendLine(
                "  - [إعادة بعد دوال التحميل] nSetCurrentVoice: " +
                    "$retryVoiceRes"
            )
            sb.appendLine(
                "  - [إعادة بعد دوال التحميل] nSpeak: $retrySpeakRes | " +
                    "عينات: $retrySamples"
            )
        }
        sb.appendLine(
            "  - سطر tts_function_load_voice: " +
                loadVoiceLine.ifBlank { "(لم يظهر في السجل)" }
        )
        val ebName = ebCode?.let {
            AcapelaVoiceDiagnostics.babileErrorName(it)
        }.orEmpty()
        val nlpName = nlpCode?.let {
            AcapelaVoiceDiagnostics.babileErrorName(it)
        }.orEmpty()

        sb.appendLine(
            "  - كود tts_function_load_voice (eb): " +
                "${ebCode ?: "(لم يظهر في السجل)"}" +
                if (ebName.isNotEmpty()) " ($ebName)" else ""
        )
        sb.appendLine(
            "  - قيمة nlpInitError: " +
                "${nlpCode ?: "(لم تظهر في السجل)"}" +
                if (nlpName.isNotEmpty()) " ($nlpName)" else ""
        )
        sb.appendLine("  - قيمة babParam.sNLPConfig: $sNlpCode")

        // فلترة أسطر babParam.uid.userId و babParam.license
        val babParamUidLines = nativeTail.filter {
            it.contains("babParam.uid.userId", ignoreCase = true) ||
                it.contains("babParam.uid.passwd", ignoreCase = true)
        }
        val babParamLicLines = nativeTail.filter {
            it.contains("babParam.license", ignoreCase = true)
        }
        val generalLicLines = nativeTail.filter {
            it.contains("error license", ignoreCase = true) ||
                it.contains("license", ignoreCase = true)
        }
        sb.appendLine(
            "  - أسطر babParam.uid.userId / passwd " +
                "(${babParamUidLines.size} سطر):"
        )
        if (babParamUidLines.isEmpty()) {
            sb.appendLine("    (لم يظهر أي سطر يطابق babParam.uid.userId)")
        } else {
            babParamUidLines.forEach { sb.appendLine("    $it") }
        }
        sb.appendLine(
            "  - أسطر babParam.license (${babParamLicLines.size} سطر):"
        )
        if (babParamLicLines.isEmpty()) {
            sb.appendLine("    (لم يظهر أي سطر يطابق babParam.license)")
        } else {
            babParamLicLines.forEach { sb.appendLine("    $it") }
        }
        sb.appendLine(
            "  - أسطر license / error license (${generalLicLines.size} سطر):"
        )
        if (generalLicLines.isEmpty()) {
            sb.appendLine("    (لم يظهر أي سطر يطابق license)")
        } else {
            generalLicLines.forEach { sb.appendLine("    $it") }
        }

        sb.appendLine(
            "  - [رصد الملفات] أحداث فتح/إغلاق مجلد nInit " +
                "وواصفات /proc/self/fd:"
        )
        if (probeReport.isBlank()) {
            sb.appendLine("    (لم يُفعَّل الرصد في هذه المحاولة)")
        } else {
            probeReport.lineSequence().forEach { line ->
                sb.appendLine("    $line")
            }
        }

        sb.appendLine("  - قائمة الملفات المفتوحة فعلياً قبل الخطأ:")
        if (openedFiles.isEmpty()) {
            sb.appendLine(
                "    (لم يُرصد فتح ملفات عبر /proc/self/fd أو السجل)"
            )
        } else {
            openedFiles.forEach { sb.appendLine("    $it") }
        }

        sb.appendLine(
            "  - سجل المكتبة الأصلية لهذه التوليفة (${nativeTail.size} سطر):"
        )
        nativeTail.forEach { sb.appendLine("    $it") }

        val logOutput = sb.toString().trimEnd()
        Log.i(TAG, logOutput)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, logOutput)

        return VoiceLoadTrialResult(
            method = label,
            path = stagePath,
            initCode = initRes,
            modeCode = modeRes,
            utf8Code = utf8Res,
            langCode = langRes,
            voiceCode = voiceRes,
            speakCode = finalSpeakCode,
            samples = totalSamples,
            service = srv,
            audioHandler = audioHandler,
            synthCallback = synthCallback,
            details = logOutput,
            nlpErrorCode = nlpCode,
            ebErrorCode = ebCode,
            nativeLines = nativeTail,
            sNlpCode = sNlpCode,
            openFiles = openedFiles,
            logFileContent = logFileContent,
            firstErrorLine = firstErr?.first,
            precedingLine = firstErr?.second,
            getLanguageResult = getLangRes,
            closestVoiceResult = closestVoiceRes,
            isLangAvailResult = isLangAvailRes
        )
    }

    /**
     * نتيجة محاولة لم تُنفَّذ: فشل التحقق قبل `nInit`.
     *
     * تُبنى بنفس شكل [VoiceLoadTrialResult] لتدخل تقرير المسارات، لكنها
     * تُبلِغ صراحةً أن `nInit` لم يُستدعَ (`initCode = -1`،
     * `speakCode = -1`) فلا يبدو الفشل انقطاعاً صامتاً.
     */
    private fun failedTrialBeforeInit(
        label: String,
        path: String,
        report: String
    ): VoiceLoadTrialResult {
        val audioHandler = TrialAudio { _, _ -> }
        audioHandler.probeOnly = true
        val synthCallback = AcapelaSynthesisCallback { _, _ -> }
        val text = buildString {
            appendLine("[$label - لم تُنفَّذ المحاولة]")
            appendLine("  - المعامل الحرفي لـ nInit: \"$path\"")
            appendLine("  - nInit: لم يُستدعَ (فشل التحقق من ملفات ini)")
            appendLine("  - nSpeak: لم يُستدعَ")
            appendLine("  - عدد عينات PCM: 0")
            appendLine("")
            appendLine(report)
        }.trimEnd()
        Log.e(TAG, text)
        HostLog.e(HostProtocol.ENGINE_ACAPELA, text)
        return VoiceLoadTrialResult(
            method = label,
            path = path,
            initCode = -1,
            modeCode = -1,
            langCode = -1,
            voiceCode = -1,
            speakCode = -1,
            samples = 0L,
            service = null,
            audioHandler = audioHandler,
            synthCallback = synthCallback,
            details = text
        )
    }

    private suspend fun configureExtendedTriple(
        voice: AcapelaVoiceManifest.VoiceInfo,
        iso3: Iso3,
        registration: String,
        rawParam: String?,
        voiceKey: String
    ): HostResult? {
        try {
            val regLen = registration.length

            // [بناء 10729] ملف واحد مستقر يُكتب تدريجياً منذ هذه اللحظة.
            beginAcapelaFullDiagnostic()

            // الخطوة 1: تقرير الجرد المتداخل وموقع ملف Salma الكبير
            logDiagnosticTrees(voice)

            // الخطوة 3: فحص سلامة ملفات الصوت
            checkAudioFilesIntegrity(voice.dir)

            // تجهيز مجلد الجذر الحرفي وفحص الملف الأصلي 109 بايت
            val origIniDump =
                AcapelaVoiceDiagnostics.getOriginalIniDump(context)
            Log.i(TAG, "[البند 1] فحص الملف الأصلي:\n$origIniDump")
            HostLog.i(
                HostProtocol.ENGINE_ACAPELA,
                "[البند 1] فحص الملف الأصلي:\n$origIniDump"
            )
            // [بناء 10729] يُدفَع فوراً كي يبقى إن انهار :engine_host.
            appendAcapelaFullDiagnostic(
                "=== [البند 1] فحص الملف الأصلي 109 بايت ===\n$origIniDump"
            )

            // [البند 3] مقارنة ملفات NLP مع ملفات التهيئة وكشف المفقود
            val compareNlp =
                AcapelaVoiceDiagnostics.compareNlpFiles(context)
            Log.i(TAG, compareNlp)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, compareNlp)

            // مقارنة اسم الصوت وتمرير اسم config.yaml إن اختلف
            val yamlVoiceName = extractVoiceNameFromConfigYaml(voice.dir)
            val passedVoiceName = voice.rawSpeaker ?: voice.voiceName
            val dirName = voice.dir.name
            val matchesYaml = yamlVoiceName != null &&
                passedVoiceName.equals(yamlVoiceName, ignoreCase = false)
            val matchesDir =
                passedVoiceName.equals(dirName, ignoreCase = false)

            val compareLog = "[مقارنة اسم الصوت] الممرر: " +
                "'$passedVoiceName' | في config.yaml: '$yamlVoiceName' " +
                "(تطابق=$matchesYaml) | اسم المجلد: '$dirName' " +
                "(تطابق=$matchesDir)"
            Log.i(TAG, compareLog)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, compareLog)

            val effectiveSpeaker = if (!yamlVoiceName.isNullOrBlank() &&
                passedVoiceName != yamlVoiceName
            ) {
                Log.i(
                    TAG,
                    "تمرير اسم الصوت كما في config.yaml: " +
                        "'$yamlVoiceName' (بدلاً من '$passedVoiceName')"
                )
                yamlVoiceName
            } else {
                passedVoiceName
            }

            // [بناء 10728] مسار واحد فقط: جذر Salma الحرفي. أُزيلت النسخ
            // الثلاث الأخرى (stage_v2 وstageFull و10709Exact) لأنها كانت
            // تكرّر ~69MB لكل محاولة وتنتج 15 تقريراً و16 ملف WAV بلا فائدة.
            runCatching {
                File(context.cacheDir, "last_acapela_call.txt").delete()
                File(context.cacheDir, "acapela_trials_progress.txt").delete()
            }

            val effectiveRawParam = rawParam ?: AcapelaVoiceManifest
                .readParamFromConfigYaml(voice.dir).orEmpty()
            val decodedReg = AcapelaMetadataCodec.resolveAcapelaParam(
                rawParam = effectiveRawParam,
                context = context
            )
            val emailLen = decodedReg.emailLength
            val hexCodeLen = decodedReg.hexCodeLength
            val regToUse = if (decodedReg.registration.isNotEmpty()) {
                decodedReg.registration
            } else {
                registration
            }
            val effectiveRegLen = regToUse.length

            val hexFileOnDisk = AcapelaVoiceManifest
                .findHexRegistrationFile(voice.dir)
            val hexExists = hexFileOnDisk?.isFile == true
            val hexSize = if (hexExists) hexFileOnDisk.length() else 0L
            val hexHasComm = runCatching {
                hexFileOnDisk?.takeIf { it.isFile }
                    ?.readText(StandardCharsets.ISO_8859_1)
                    ?.contains("COMMERCIAL") == true
            }.getOrDefault(false)

            val sha1ComparisonMsg = if (decodedReg.sha1Matches7e88) {
                "مطابق للقيمة المسجلة (القيمة مخفية)"
            } else {
                "مختلف عن القيمة المسجلة (القيمة مخفية)"
            }

            val keyDiskStatus = "[فحص ملف الترخيص 10719] 0x00169729: " +
                "موجود=$hexExists, حجم=$hexSize بايت, " +
                "يحوي COMMERCIAL=$hexHasComm\n" +
                "[فك وتحليل param - البند 2 من 10719]:\n" +
                "- هل param كان مشفراً: " +
                "${if (decodedReg.isEncrypted) "نعم" else "لا"}\n" +
                "- عدد الأجزاء بعد التقسيم: ${decodedReg.partsCount}\n" +
                "- طول email: $emailLen\n" +
                "- طول hexCode: $hexCodeLen\n" +
                "- طول registration: $effectiveRegLen\n" +
                "- بصمة SHA1 مخفية؛ الحكم: $sha1ComparisonMsg"
            Log.i(TAG, keyDiskStatus)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, keyDiskStatus)
            // [بناء 10729] فحص الرخصة يُدفَع فوراً أيضاً.
            appendAcapelaFullDiagnostic(
                "=== [فحص الترخيص] ===\n$keyDiskStatus"
            )

            val origSalmaDir = AcapelaVoiceDiagnostics
                .findOriginalSalmaDir(context)
                ?: voice.dir.parentFile?.takeIf {
                    it.name.equals("Salma", ignoreCase = true)
                }
                ?: voice.dir

            // ── المسار الوحيد: Path_Original_Root_109 ──────────────────────
            // نسخة حرفية لجذر Salma/ بلا استبدال، ثم حاجز تحقق من ملف
            // ini قبل nInit، ثم محاولة واحدة فقط مع رصد الملفات المفتوحة.
            // [بناء 10729] لا يُمسح سجل logcat قبل الاختبار: ماضيه هو
            // الدليل الوحيد على ما جرى قبل الانهيار.
            val root109Dir = AcapelaVoiceDiagnostics.stageOriginalRoot109(
                context = context,
                sourceDir = origSalmaDir
            )
            val preInitCheck = AcapelaVoiceDiagnostics
                .verifyRoot109IniBeforeInit(root109Dir)
            Log.i(TAG, preInitCheck.report)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, preInitCheck.report)
            // [بناء 10729] حاجز ما قبل nInit يُدفَع قبل أي نداء أصلي.
            appendAcapelaFullDiagnostic(
                "=== [حاجز التحقق قبل nInit] ===\n${preInitCheck.report}"
            )
            runCatching {
                val checkText = preInitCheck.report
                File(
                    context.cacheDir,
                    "acapela_stage_verify_10728.txt"
                ).writeText(checkText, StandardCharsets.UTF_8)
            }
            val trialRoot109 = if (!preInitCheck.passed) {
                // فشل التجهيز: لا nInit ولا nSpeak، ويُسجَّل السبب صراحةً
                // كي لا يبدو الفشل انقطاعاً صامتاً.
                failedTrialBeforeInit(
                    label = "Path_Original_Root_109 (فشل التحقق قبل nInit)",
                    path = directoryArgument(root109Dir),
                    report = preInitCheck.report
                )
            } else {
                executeVoiceLoadTrial(
                    label = "Path_Original_Root_109 (نسخة حرفية لجذر Salma)",
                    trialDir = root109Dir,
                    speaker = effectiveSpeaker,
                    iso3 = iso3,
                    registration = regToUse,
                    voiceMode = "MODE=prep_hd",
                    probeText = "مرحبا",
                    order = AcapelaSequenceOrder.UTF8_LANG_VOICE,
                    useRealCallback = false,
                    trailingSlash = true,
                    forceCallVoice = true,
                    forceVoiceEvenIfInitFailed = true,
                    allowRetry = false,
                    probeFiles = true
                )
            }
            val trialsList = mutableListOf(trialRoot109)
            recordTrialProgress(trialRoot109)
            // [بناء 10729] تفاصيل المحاولة (وسائط nInit وtrailingSlash
            // والمتحدث وأكواد nSetVoiceMode/nSpeak وeb/nlpErr/sNLPConfig
            // وعينات PCM وأسطر المكتبة) تُدفَع فور انتهائها.
            if (trialRoot109.details.isNotBlank()) {
                appendAcapelaFullDiagnostic(trialRoot109.details)
            }

            // إغلاق خدمة التجربة الفاشلة فوراً: لا تُترك مهيّأةً بلا نطق
            // فتتراكم خدمات nInit مفتوحة (nStop ثم nShutdown). خدمة
            // التجربة الناجحة تبقى للنطق وتُغلق عبر closeExtended.
            if (!trialRoot109.isSuccess) {
                trialRoot109.service?.let { srv ->
                    runCatching { srv.nStop() }
                    runCatching { srv.nShutdown() }
                }
            }

            // فحص النجاح وحفظ ملف WAV واحد للنطق التجريبي "مرحبا".
            val winningTrial = trialsList.firstOrNull { it.isSuccess }
            lastTrialSucceeded.set(winningTrial != null)
            if (winningTrial != null) {
                val pcmData =
                    winningTrial.audioHandler.pcmBytes.toByteArray()
                if (pcmData.isNotEmpty()) {
                    val wavFile = File(
                        context.cacheDir,
                        "acapela_marhaba_10728.wav"
                    )
                    AcapelaVoiceDiagnostics.savePcmAsWav(wavFile, pcmData)
                    Log.i(
                        TAG,
                        "تم حفظ ملف نطق مرحبا: ${wavFile.absolutePath} " +
                            "(${wavFile.length()} بايت)"
                    )
                }
            }

            val chosenTrial = winningTrial

            val literalResults = buildString {
                appendLine("=== [نتائج مسار نطق Acapela (بناء 10728)] ===")
                trialsList.forEach { t ->
                    val ebStr = t.ebErrorCode?.let {
                        "$it (${AcapelaVoiceDiagnostics.babileErrorName(it)})"
                    } ?: "0"
                    val nlpStr = t.nlpErrorCode?.let {
                        "$it (${AcapelaVoiceDiagnostics.babileErrorName(it)})"
                    } ?: "0"
                    val sNlpStr = t.sNlpCode?.toString() ?: "0"
                    val trialDirObj = File(t.path.trimEnd('/', '\\'))
                    val fileInventory = AcapelaVoiceDiagnostics
                        .getDetailedDirectoryInventory(trialDirObj)
                    appendLine("────────────────────────────────────────")
                    appendLine("المسار: ${t.method}")
                    appendLine("  - المعامل الحرفي لـ nInit: \"${t.path}\"")
                    appendLine("")
                    appendLine("=== [البند 2] محتوى logfile.txt كاملاً ===")
                    if (t.logFileContent.isBlank()) {
                        appendLine("  (ملف logfile.txt فارغ أو لم يُنشأ)")
                    } else {
                        appendLine(t.logFileContent)
                    }
                    appendLine("")
                    appendLine(
                        "=== [البند 2] أسطر logcat لـ " +
                            "acattsandroid-extended-native من nInit إلى " +
                            "nSpeak (${t.nativeLines.size} سطر) ==="
                    )
                    if (t.nativeLines.isEmpty()) {
                        appendLine("  (سجل المكتبة فارغ في logcat)")
                    } else {
                        t.nativeLines.forEach { line ->
                            appendLine("  $line")
                        }
                    }
                    appendLine("")
                    appendLine(
                        "=== [البند 3] أول سطر خطأ في سجل المكتبة " +
                            "والسطر الذي قبله ==="
                    )
                    if (t.firstErrorLine != null) {
                        appendLine("  - أول سطر خطأ: \"${t.firstErrorLine}\"")
                        appendLine(
                            "  - السطر الذي قبله مباشرة: " +
                                "\"${t.precedingLine ?: "(لا يوجد)"}\""
                        )
                    } else {
                        appendLine(
                            "  - أول سطر خطأ: (لم يظهر أي سطر خطأ يحوي " +
                                "Error أو error أو not loaded أو load error)"
                        )
                        appendLine("  - السطر الذي قبله مباشرة: —")
                    }
                    appendLine("")
                    appendLine(
                        "=== [البند 4] أكواد الرجوع واستدعاءات حصر " +
                            "الأصوات واللغة ==="
                    )
                    appendLine("  - رمز إرجاع nInit: ${t.initCode}")
                    appendLine("  - رمز إرجاع nSetVoiceMode: ${t.modeCode}")
                    appendLine(
                        "  - رمز إرجاع nUtf8Mode: ${t.utf8Code ?: "تم تخطيه"}"
                    )
                    appendLine(
                        "  - رمز إرجاع cSetLanguage / nSetLanguage: " +
                            "${t.langCode}"
                    )
                    appendLine(
                        "  - رمز إرجاع nSetCurrentVoice: ${t.voiceCode}"
                    )
                    appendLine("  - رمز إرجاع nSpeak: ${t.speakCode}")
                    appendLine(
                        "  - استدعاء nGetLanguage: " +
                            "\"${t.getLanguageResult ?: "(null)"}\""
                    )
                    appendLine(
                        "  - استدعاء nGetClosestVoice: " +
                            "\"${t.closestVoiceResult ?: "(null)"}\""
                    )
                    appendLine(
                        "  - استدعاء nIsLanguageAvailable: " +
                            "${t.isLangAvailResult ?: "لم يُستدعَ"}"
                    )
                    appendLine(
                        "  - قيمتا eb و nlpErr: eb=$ebStr | " +
                            "nlpErr=$nlpStr | sNLPConfig: $sNlpStr"
                    )
                    appendLine(
                        "  - قيمة nSpeak وعدد عينات PCM: " +
                            "nSpeak=${t.speakCode} | PCM=${t.samples}"
                    )
                    appendLine(
                        "  - هل تحقق معيار النجاح (بناء 10724): " +
                            if (t.isSuccess) "نعم (ناجح)" else "لا (فاشل)"
                    )
                    appendLine("")
                    appendLine(
                        "=== [البند 5] قائمة الملفات المفتوحة للمكتبة ==="
                    )
                    if (t.openFiles.isEmpty()) {
                        appendLine(
                            "  (لم يُرصد فتح ملفات عبر /proc/self/fd أو السجل)"
                        )
                    } else {
                        t.openFiles.forEach { f ->
                            appendLine("  * $f")
                        }
                    }
                    appendLine("")
                    appendLine(
                        "=== [البند 2] قائمة ملفات المجلد بالأحجام " +
                            "الدقيقة بالبايت (${fileInventory.size} ملف) ==="
                    )
                    fileInventory.forEach { line ->
                        appendLine("  * $line")
                    }
                }
            }.trimEnd()
            Log.i(TAG, literalResults)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, literalResults)

            val trialSummary = trialsList.joinToString(" | ") { t ->
                val ebStr = t.ebErrorCode?.toString() ?: "0"
                val nlpStr = t.nlpErrorCode?.toString() ?: "0"
                val sNlpStr = t.sNlpCode?.toString() ?: "0"
                "${t.method} (eb=$ebStr, nlpErr=$nlpStr, sNLP=$sNlpStr, " +
                    "pcm=${t.samples}, speak=${t.speakCode})"
            }
            val outcome = if (winningTrial != null) {
                "نجاح واعتماد (${winningTrial.method}) - تم حفظ WAV"
            } else {
                "فشل كافة مسارات نطق Acapela حسب معيار 10724"
            }
            val topSummary = "[تشخيص مسارات Acapela 10727] " +
                "$trialSummary | النتيجة: $outcome"
            Log.i(TAG, topSummary)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, topSummary)

            runCatching {
                val fullTrialsText = buildString {
                    appendLine(topSummary)
                    appendLine("")
                    appendLine(literalResults)
                    appendLine("")
                    appendLine(keyDiskStatus)
                    appendLine("")
                    appendLine("=== [البند 1] فحص الملف الأصلي 109 بايت ===")
                    appendLine(origIniDump)
                    appendLine("")
                    appendLine(
                        AcapelaVoiceDiagnostics
                            .getOriginalRoot109Verification(context)
                    )
                    appendLine("")
                    trialsList.forEach { t ->
                        if (t.details.isNotBlank()) {
                            appendLine(t.details)
                            appendLine("")
                        }
                    }
                }.trimEnd()
                // [بناء 10729] التقرير الكامل يُلحَق بالملف المستقر بعد
                // الخطوات التدريجية، فلا يُفقد إن انهار :engine_host.
                appendAcapelaFullDiagnostic(fullTrialsText)
            }

            // [بناء 10728] النجاح يُحكم بمعيار 10724 (PCM>0 وnSpeak≠-2
            // وبلا خطأ لغة) لا باشتراط voiceCode==0، كي لا يُرفض نطق
            // ناجح بسبب كود صوت شكلي.
            if (chosenTrial == null || !chosenTrial.isSuccess) {
                logVoiceDiagnostics(
                    voice,
                    "فشل المحاولات: $trialSummary"
                )
                return HostResult.failure(
                    "VOICE_LOAD_FAILED",
                    "فشل nSetCurrentVoice/nSpeak: $trialSummary"
                )
            }

            chosenTrial.audioHandler.probeOnly = false
            val srv = chosenTrial.service ?: return HostResult.failure(
                "NOT_INITIALIZED",
                "خدمة Acapela المعتمدة غير متوفرة"
            )
            extendedService = srv
            extendedKey = "$voiceKey|$registration"

            Log.i(
                TAG,
                "حزمة Acapela ممتدة جاهزة [${chosenTrial.method}]: " +
                    "$effectiveSpeaker (${iso3.language}-${iso3.country})، " +
                    "طول التسجيل=$regLen"
            )
            return null
        } catch (t: Throwable) {
            lastTrialSucceeded.set(false)
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val errText =
                "=== [استثناء يمنع تشغيل مسار Acapela 10729] ===\n" +
                    "نوع الخطأ: ${t.javaClass.name}\n" +
                    "الرسالة: ${t.message ?: "بدون رسالة"}\n" +
                    "تتبع المكدس:\n$sw"
            Log.e(TAG, errText, t)
            HostLog.e(HostProtocol.ENGINE_ACAPELA, errText)
            // [بناء 10729] الاستثناء يُلحَق بالملف المستقر لا بملف مرقم.
            appendAcapelaFullDiagnostic(errText)
            return HostResult.failure(
                "CONFIGURE_EXCEPTION",
                t.message ?: "استثناء أثناء اختبار التوليفات"
            )
        }
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
                registrationLength = voice.registration.length,
                packageName = context.packageName,
                context = context
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
        val msg = "Acapela Extended: nSetProperty($name, $value)=$res"
        Log.i(TAG, msg)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, msg)
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
