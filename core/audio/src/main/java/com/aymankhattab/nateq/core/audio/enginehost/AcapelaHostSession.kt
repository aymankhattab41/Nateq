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
import java.util.UUID
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
         * (اكتشاف/تحقق موحّد دون توليد أو حشو تهيئة).
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
            // إبطال لقطة التعداد السابقة قبل القراءة اللاحقة.
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

            val resolvedReg = AcapelaVoiceManifest.resolveRegistration(
                voice.dir
            )
            if (AcapelaVoiceManifest.isRegistrationMetadata(resolvedReg)) {
                val registeredVoice = voice.copy(registration = resolvedReg)
                val extended = speakExtended(
                    voice = registeredVoice,
                    chunks = chunks,
                    rate = req.rate,
                    pitch = req.pitch,
                    sink = sink,
                    cancelled = cancelled
                )
                if (!extended.ok) {
                    logVoiceDiagnostics(
                        registeredVoice,
                        extended.code ?: "UNKNOWN"
                    )
                }
                return extended
            }

            if (isHdPackageWithoutRegistration(voice)) {
                val hasYamlParam = AcapelaVoiceManifest
                    .findConfigYamlWithParam(voice.dir) != null
                if (!hasYamlParam) {
                    val hdMsg =
                        "ملف config.yaml غير موجود في مجلد الصوت " +
                            "أو تفتقد قيمته إلى سطر param"
                    val hdLog = "فشل النطق: CONFIG_YAML_MISSING - " +
                        "path=classic, registration.length=0, " +
                        "id=${voice.id}"
                    Log.w(TAG, hdLog)
                    HostLog.w(HostProtocol.ENGINE_ACAPELA, hdLog)
                    return HostResult.failure(
                        "CONFIG_YAML_MISSING",
                        hdMsg
                    )
                }
                // يوجد config.yaml(param) لحزمة HD ممتدة: جرّب المسار
                // الممتد بدل تشخيص «config.yaml مفقود» الخاطئ.
                val extended = speakExtended(
                    voice = voice,
                    chunks = chunks,
                    rate = req.rate,
                    pitch = req.pitch,
                    sink = sink,
                    cancelled = cancelled
                )
                if (!extended.ok) {
                    logVoiceDiagnostics(
                        voice,
                        extended.code ?: "UNKNOWN"
                    )
                }
                return extended
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
        var registration = if (!rawParam.isNullOrBlank()) {
            AcapelaVoiceManifest.extractRegistrationFromParam(
                rawParam,
                context
            )
        } else {
            voice.registration
        }
        if (registration.isEmpty() ||
            !AcapelaVoiceManifest.isRegistrationMetadata(registration)
        ) {
            val resolved =
                AcapelaVoiceManifest.resolveRegistration(voice.dir)
            if (AcapelaVoiceManifest.isRegistrationMetadata(resolved)) {
                registration = resolved
            }
        }
        val regLen = registration.length
        if (regLen == 0) {
            return HostResult.failure(
                "CONFIG_YAML_MISSING",
                "بيانات التسجيل غير متوفرة في ${voice.dir.name} " +
                    "(ملف config.yaml غير موجود في مجلد الصوت أو الأب أو " +
                    "جذر files/Acapela)"
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
        val initInvoked: Boolean,
        val initCode: Int?,
        val modeInvoked: Boolean,
        val modeCode: Int?,
        val utf8Invoked: Boolean = false,
        val utf8Code: Int? = null,
        val langInvoked: Boolean,
        val langCode: Int?,
        val voiceInvoked: Boolean,
        val voiceCode: Int?,
        val speakInvoked: Boolean,
        val speakCode: Int?,
        val callbackConnected: Boolean = false,
        val pcmInvoked: Boolean,
        val samples: Long?,
        val pcmBytes: Long?,
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
        val isLangAvailResult: Int? = null,
        val runId: String = "",
        val startedAtMs: Long = 0L
    ) {
        val isSuccess: Boolean
            get() = AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                speakCode = speakCode,
                samples = samples,
                nativeLines = nativeLines,
                callbackConnected = callbackConnected,
                invoked = speakInvoked && pcmInvoked
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
        val rootConfig = File(
            AcapelaVoiceManifest.findAcapelaRootDir(dir),
            "config.yaml"
        )
        val yamlFile = File(dir, "config.yaml")
            .takeIf { it.isFile }
            ?: dir.parentFile?.let { File(it, "config.yaml") }
                ?.takeIf { it.isFile }
            ?: rootConfig.takeIf { it.isFile }
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
            val f = File(
                context.cacheDir, "acapela_trials_progress.txt"
            )
            val ts = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date(trial.startedAtMs))
            val speak = trial.speakCode?.toString() ?: "غير مقيس"
            val samples = trial.samples?.toString() ?: "غير مقيس"
            val invoked = if (trial.speakInvoked) "نعم" else "لا"
            val line = "[$ts] runId=${trial.runId} | ${trial.method} | " +
                "nSpeak مستدعى=$invoked | كود=$speak | " +
                "عينات PCM=$samples\n"
            f.appendText(line, StandardCharsets.UTF_8)
        }
    }

    /**
     * بدء سجل تشخيص مقطوع جديد لهذه المحاولة فقط (runId + وقت بداية جديد):
     * يُعاد إنشاء الملف بلا إلحاق/إرث قديم، ويُكتب عنوان ببيانات البناء
     * الفعلية. الكتابة أثناء المحاولة تسلسلية مع flush/fd.sync.
     */
    private fun beginAcapelaFullDiagnostic(
        runId: String,
        startedAtMs: Long
    ) {
        val started = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date(startedAtMs))
        val (versionName, versionCode) = runCatching {
            val pm = context.packageManager
            val pkg = context.packageName
            val info = pm.getPackageInfo(pkg, 0)
            val vn = info.versionName ?: "?"
            val vc = if (android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.P
            ) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            vn to vc
        }.getOrDefault("?" to -1L)
        val header = buildString {
            appendLine("=== تجربة Acapela (محاولة واحدة جديدة) ===")
            appendLine("runId: $runId")
            appendLine("وقت البدء: $started")
            appendLine("الحزمة: ${context.packageName}")
            appendLine("الإصدار: $versionName ($versionCode)")
            appendLine(
                "الجهاز: ${android.os.Build.MANUFACTURER} " +
                    "${android.os.Build.MODEL} " +
                    "(SDK ${android.os.Build.VERSION.SDK_INT})"
            )
            appendLine("---")
        }
        runCatching {
            File(context.cacheDir, "acapela_trials_progress.txt")
                .writeText("", StandardCharsets.UTF_8)
            File(context.cacheDir, "last_acapela_call.txt")
                .writeText("", StandardCharsets.UTF_8)
            val file = File(
                context.cacheDir,
                AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
            )
            FileOutputStream(file, false).use { out ->
                val safeHeader = AcapelaVoiceDiagnostics
                    .redactSensitiveText(header)
                out.write(safeHeader.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    /**
     * إلحاق قسم بملف تشخيص المحاولة الحالية مع تدفيع فوري بعد كل خطوة
     * (`flush` ثم `fd.sync`) كي ينجو ما كُتب عند انهيار العملية.
     */
    private fun appendAcapelaFullDiagnostic(text: String) {
        runCatching {
            val file = File(
                context.cacheDir,
                AcapelaVoiceDiagnostics.ACAPELA_FULL_DIAGNOSTIC_FILE
            )
            FileOutputStream(file, true).use { out ->
                val safe = AcapelaVoiceDiagnostics
                    .redactSensitiveText(text)
                out.write(safe.toByteArray(StandardCharsets.UTF_8))
                if (!safe.endsWith("\n")) out.write('\n'.code)
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
        runId: String,
        startedAtMs: Long,
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
        var initRes: Int? = null
        try {
            initRes = srv.nInit(stagePath)
        } catch (t: Throwable) {
            Log.e(TAG, "[$label] استثناء أثناء nInit", t)
            initRes = null
        } finally {
            probeReport = runCatching { fileProbe?.stopAndLog() }
                .getOrNull().orEmpty()
        }
        val initInvoked = true

        var modeInvoked = false
        var modeRes: Int? = null
        if (initRes == 0 || forceVoiceEvenIfInitFailed) {
            recordAcapelaBreadcrumb("[$label] nSetVoiceMode($voiceMode)")
            modeInvoked = true
            modeRes = runCatching { srv.nSetVoiceMode(voiceMode) }
                .getOrElse { -99 }
            recordAcapelaBreadcrumb(
                "[$label] nSetVoiceMode($voiceMode) => $modeRes"
            )
            Log.i(TAG, "[$label] nSetVoiceMode($voiceMode) => $modeRes")
        }

        val extraResMap = mutableMapOf<String, Int>()
        if (initRes == 0) {
            for ((propKey, propVal) in extraProperties) {
                val res = setExtendedProperty(srv, propKey, propVal)
                extraResMap[propKey] = res
            }
        }

        val earlyLogs = AcapelaVoiceDiagnostics.readNativeLogcatLines(100)
        val earlyLoadLine = earlyLogs.lastOrNull {
            it.contains("tts_function_load_voice")
        }.orEmpty()
        val earlyEbMatch = Regex("eb\\s+(-?\\d+)").find(earlyLoadLine)
        val earlyEbVal = earlyEbMatch?.groupValues?.get(1)?.toIntOrNull()
        val isVoiceLoaded = earlyLoadLine.contains("- done") &&
            (earlyEbVal == null || earlyEbVal == 0)

        var utf8Invoked = false
        var utf8Res: Int? = null
        var langInvoked = false
        var langRes: Int? = null
        var voiceInvoked = false
        var voiceRes: Int? = null

        if ((initRes == 0 && (forceCallVoice || isVoiceLoaded)) ||
            forceVoiceEvenIfInitFailed
        ) {
            when (order) {
                AcapelaSequenceOrder.UTF8_LANG_VOICE -> {
                    utf8Invoked = true
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                    langInvoked = true
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    voiceInvoked = true
                    voiceRes = srv.nSetCurrentVoice(speaker)
                }
                AcapelaSequenceOrder.LANG_VOICE_UTF8 -> {
                    langInvoked = true
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    voiceInvoked = true
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    utf8Invoked = true
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                }
                AcapelaSequenceOrder.LANG_UTF8_VOICE -> {
                    langInvoked = true
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    utf8Invoked = true
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                    voiceInvoked = true
                    voiceRes = srv.nSetCurrentVoice(speaker)
                }
                AcapelaSequenceOrder.VOICE_LANG_UTF8 -> {
                    voiceInvoked = true
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    langInvoked = true
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    utf8Invoked = true
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                }
                AcapelaSequenceOrder.VOICE_UTF8_LANG -> {
                    voiceInvoked = true
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    utf8Invoked = true
                    utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
                    langInvoked = true
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                }
                AcapelaSequenceOrder.NO_UTF8 -> {
                    langInvoked = true
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                    voiceInvoked = true
                    voiceRes = srv.nSetCurrentVoice(speaker)
                }
                AcapelaSequenceOrder.NO_UTF8_VOICE_LANG -> {
                    voiceInvoked = true
                    voiceRes = srv.nSetCurrentVoice(speaker)
                    langInvoked = true
                    langRes = srv.nSetLanguage(
                        iso3.language, iso3.country, ""
                    )
                }
            }
        } else if (initRes == 0) {
            if (order != AcapelaSequenceOrder.NO_UTF8 &&
                order != AcapelaSequenceOrder.NO_UTF8_VOICE_LANG
            ) {
                utf8Invoked = true
                utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
            }
            langInvoked = true
            langRes = srv.nSetLanguage(iso3.language, iso3.country, "")
            recordAcapelaBreadcrumb(
                "[$label] تم تخطي nSetCurrentVoice لعدم نجاح تحميل الصوت"
            )
        }

        var triedAltVoice: String? = null
        var altVoiceRes: Int? = null
        if (initRes == 0 && isVoiceLoaded && (voiceRes ?: -1) != 0 &&
            speaker != trialDir.name
        ) {
            val alt = trialDir.name
            triedAltVoice = alt
            voiceInvoked = true
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

        val enumRes: Int? = null

        val loadLangRes = if (initRes == 0 && isVoiceLoaded) {
            runCatching {
                srv.nloadLanguage(iso3.language, iso3.country, "")
            }.getOrElse { -99 }
        } else null

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
        var speakInvoked = false
        var speakRes: Int? = null
        if (canSpeak) {
            speakInvoked = true
            speakRes = runCatching {
                srv.nSpeak(probeText, cbToPass)
            }.getOrElse { t ->
                Log.w(TAG, "[$label] استثناء أثناء nSpeak", t)
                -2
            }
        }
        Log.i("acattsandroid-extended-native", endMarker)
        recordAcapelaBreadcrumb(
            "[$label] انتهى nSpeak: كود=${speakRes ?: "غير مقيس"}"
        )

        var retryVoiceRes: Int? = null
        var retrySpeakRes: Int? = null
        var retrySamples: Long? = null
        if (allowRetry && initRes == 0 && speakRes == -2 &&
            isVoiceLoaded && speakInvoked
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

        if (speakInvoked && speakRes == 0) {
            val started = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - started < 1500L &&
                audioHandler.samples.get() == 0L &&
                synthCallback.samples.get() == 0L
            ) {
                delay(50L)
            }
        }

        val pcmInvoked = speakInvoked
        val totalSamples: Long? = if (pcmInvoked) {
            maxOf(
                maxOf(
                    audioHandler.samples.get(),
                    synthCallback.samples.get()
                ),
                retrySamples ?: 0L
            )
        } else {
            null
        }
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
        val effectiveLogFile = trialLogFile.takeIf {
            it.exists() && it.length() > 0L
        } ?: trialDir.walkTopDown()
            .filter {
                it.isFile && it.length() > 0L &&
                    it.name.equals(
                        AcapelaVoiceDiagnostics.LOG_FILE_NAME, true
                    )
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
        sb.appendLine(
            "  - nInit => مستدعى=$initInvoked | كود: ${initRes ?: "غير مقيس"}"
        )
        if (modeInvoked) {
            sb.appendLine(
                "  - nSetVoiceMode(mode = \"$voiceMode\") => كود: $modeRes"
            )
        } else {
            sb.appendLine("  - nSetVoiceMode: لم يُستدعَ")
        }
        for ((k, v) in extraResMap) {
            sb.appendLine("  - nSetProperty(name = \"$k\") => كود: $v")
        }
        if (utf8Invoked) {
            sb.appendLine(
                "  - nUtf8Mode(lang = \"${iso3.language}\", " +
                    "country = \"${iso3.country}\") => كود: $utf8Res"
            )
        } else {
            sb.appendLine("  - nUtf8Mode: لم يُستدعَ")
        }
        if (langInvoked) {
            sb.appendLine(
                "  - nSetLanguage(lang = \"${iso3.language}\", " +
                    "country = \"${iso3.country}\", " +
                    "variant = \"\") => كود: $langRes"
            )
        } else {
            sb.appendLine("  - nSetLanguage: لم يُستدعَ")
        }
        if (voiceInvoked) {
            sb.appendLine(
                "  - nSetCurrentVoice(speaker = \"$speaker\") => " +
                    "كود: $voiceRes"
            )
        } else {
            sb.appendLine(
                "  - nSetCurrentVoice: لم يُستدعَ (تخطي صريح)"
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
        if (speakInvoked) {
            sb.appendLine(
                "  - nSpeak(text = \"$probeText\", " +
                    "callback = $cbDesc) => كود: $speakRes"
            )
            sb.appendLine(
                "  - عينات PCM: ${totalSamples ?: "غير مقيس"}"
            )
        } else {
            sb.appendLine("  - nSpeak: لم يُستدعَ (غير مقيس)")
            sb.appendLine("  - عينات PCM: غير مقيس")
        }
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
            babParamUidLines.forEach {
                sb.appendLine(
                    "    ${AcapelaVoiceDiagnostics.redactSensitiveLine(it)}"
                )
            }
        }
        sb.appendLine(
            "  - أسطر babParam.license (${babParamLicLines.size} سطر):"
        )
        if (babParamLicLines.isEmpty()) {
            sb.appendLine("    (لم يظهر أي سطر يطابق babParam.license)")
        } else {
            babParamLicLines.forEach {
                sb.appendLine(
                    "    ${AcapelaVoiceDiagnostics.redactSensitiveLine(it)}"
                )
            }
        }
        sb.appendLine(
            "  - أسطر license / error license (${generalLicLines.size} سطر):"
        )
        if (generalLicLines.isEmpty()) {
            sb.appendLine("    (لم يظهر أي سطر يطابق license)")
        } else {
            generalLicLines.forEach {
                sb.appendLine(
                    "    ${AcapelaVoiceDiagnostics.redactSensitiveLine(it)}"
                )
            }
        }

        sb.appendLine(
            "  - [رصد الملفات] أحداث فتح/إغلاق مجلد nInit " +
                "وواصفات /proc/self/fd:"
        )
        if (probeReport.isBlank()) {
            sb.appendLine("    (لم يُفعَّل الرصد في هذه المحاولة)")
        } else {
            AcapelaVoiceDiagnostics.redactSensitiveText(probeReport)
                .lineSequence()
                .forEach { line ->
                    sb.appendLine("    $line")
                }
        }

        sb.appendLine("  - قائمة الملفات المفتوحة فعلياً قبل الخطأ:")
        if (openedFiles.isEmpty()) {
            sb.appendLine(
                "    (لم يُرصد فتح ملفات عبر /proc/self/fd أو السجل)"
            )
        } else {
            openedFiles.forEach {
                sb.appendLine(
                    "    ${AcapelaVoiceDiagnostics.redactSensitiveLine(it)}"
                )
            }
        }

        sb.appendLine(
            "  - سجل المكتبة الأصلية لهذه التوليفة (${nativeTail.size} سطر):"
        )
        nativeTail.forEach {
            sb.appendLine(
                "    ${AcapelaVoiceDiagnostics.redactSensitiveLine(it)}"
            )
        }

        val logOutput = sb.toString().trimEnd()
        Log.i(TAG, logOutput)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, logOutput)

        val pcmBytesCount = if (pcmInvoked) {
            audioHandler.pcmBytes.size().toLong()
        } else {
            null
        }

        return VoiceLoadTrialResult(
            method = label,
            path = stagePath,
            initInvoked = initInvoked,
            initCode = initRes,
            modeInvoked = modeInvoked,
            modeCode = modeRes,
            utf8Invoked = utf8Invoked,
            utf8Code = utf8Res,
            langInvoked = langInvoked,
            langCode = langRes,
            voiceInvoked = voiceInvoked,
            voiceCode = voiceRes,
            speakInvoked = speakInvoked,
            speakCode = finalSpeakCode,
            callbackConnected = useRealCallback && speakInvoked,
            pcmInvoked = pcmInvoked,
            samples = totalSamples,
            pcmBytes = pcmBytesCount,
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
            isLangAvailResult = isLangAvailRes,
            runId = runId,
            startedAtMs = startedAtMs
        )
    }

    /**
     * نتيجة محاولة لم تُنفَّذ: فشل التحقق قبل `nInit`.
     * تُبلِغ صراحةً أن الاستدعاءات لم تقع والقياسات غير مقيسة
     * (`nullable` + `invoked=false`) بلا استخدام -1/0.
     */
    private fun failedTrialBeforeInit(
        label: String,
        runId: String,
        startedAtMs: Long,
        path: String,
        report: String
    ): VoiceLoadTrialResult {
        val audioHandler = TrialAudio { _, _ -> }
        audioHandler.probeOnly = true
        val synthCallback = AcapelaSynthesisCallback { _, _ -> }
        val text = buildString {
            appendLine("=== تجربة لم تُنفَّذ | runId=$runId ===")
            appendLine("  - المسار: $label")
            appendLine("  - المعامل الحرفي لـ nInit: \"$path\"")
            appendLine("  - nInit: لم يُستدعَ (فشل التحقق قبل التشغيل)")
            appendLine("  - اختيار/إعداد الصوت: لم يُستدعَ")
            appendLine("  - nSpeak: لم يُستدعَ")
            appendLine("  - callback: غير موصول (غير مقيس)")
            appendLine("  - حزم/بايتات/عينات PCM: غير مقيسة")
            appendLine("  - النتيجة: فشل تحقق (لم يُستدعَ المحرك)")
            appendLine("")
            appendLine(AcapelaVoiceDiagnostics.redactSensitiveText(report))
        }.trimEnd()
        Log.e(TAG, text)
        HostLog.e(HostProtocol.ENGINE_ACAPELA, text)
        return VoiceLoadTrialResult(
            method = label,
            path = path,
            initInvoked = false,
            initCode = null,
            modeInvoked = false,
            modeCode = null,
            utf8Invoked = false,
            utf8Code = null,
            langInvoked = false,
            langCode = null,
            voiceInvoked = false,
            voiceCode = null,
            speakInvoked = false,
            speakCode = null,
            callbackConnected = false,
            pcmInvoked = false,
            samples = null,
            pcmBytes = null,
            service = null,
            audioHandler = audioHandler,
            synthCallback = synthCallback,
            details = text,
            runId = runId,
            startedAtMs = startedAtMs
        )
    }

    private suspend fun configureExtendedTriple(
        voice: AcapelaVoiceManifest.VoiceInfo,
        iso3: Iso3,
        registration: String,
        rawParam: String?,
        voiceKey: String
    ): HostResult? {
        val runId = UUID.randomUUID().toString()
        val startedAtMs = System.currentTimeMillis()
        try {
            beginAcapelaFullDiagnostic(runId, startedAtMs)

            // الهوية المطلوبة من اسم الصوت/المجلد، بلا افتراض مسار بديل.
            val expectedSpeaker = voice.rawSpeaker ?: voice.voiceName
            val preInvoke = buildString {
                appendLine("=== ما قبل الاستدعاء ===")
                appendLine("جذر/مجلد الصوت: ${voice.dir.absolutePath}")
                appendLine(
                    "ملف التهيئة المعلن: " +
                        (voice.configFile?.absolutePath ?: "غير محدد")
                )
                appendLine("الهوية المتوقعة (speaker): $expectedSpeaker")
                appendLine("المعرّف: ${voice.id}")
            }.trimEnd()
            appendAcapelaFullDiagnostic(preInvoke)
            val siblingUserdicos = File(
                voice.dir.parentFile ?: voice.dir, "userdicos"
            ).apply { mkdirs() }
            File(voice.dir, "userdicos").mkdirs()
            runCatching {
                voice.dir.walkTopDown().maxDepth(4).filter {
                    it.isFile && it.name.endsWith(
                        ".userdico", ignoreCase = true
                    )
                }.forEach { ud ->
                    val target = File(siblingUserdicos, ud.name)
                    if (!target.exists() &&
                        ud.canonicalPath != target.canonicalPath
                    ) {
                        ud.copyTo(target, overwrite = false)
                    }
                }
            }
            val verification = AcapelaVoiceDiagnostics
                .verifyAuthenticVoicePackage(
                    voiceDir = voice.dir,
                    expectedSpeakerHint = expectedSpeaker
                )
            Log.i(TAG, verification.report)
            HostLog.i(
                HostProtocol.ENGINE_ACAPELA,
                verification.report
            )
            appendAcapelaFullDiagnostic(verification.report)

            val effectiveRawParam = rawParam ?: AcapelaVoiceManifest
                .readParamFromConfigYaml(voice.dir).orEmpty()
            val decodedReg = AcapelaMetadataCodec.resolveAcapelaParam(
                rawParam = effectiveRawParam,
                context = context
            )
            val regToUse = if (decodedReg.registration.isNotEmpty()) {
                decodedReg.registration
            } else {
                registration
            }

            val trial = if (!verification.ok) {
                failedTrialBeforeInit(
                    label = "AuthenticVoice (فشل التحقق قبل nInit)",
                    runId = runId,
                    startedAtMs = startedAtMs,
                    path = directoryArgument(voice.dir),
                    report = verification.report
                )
            } else {
                val stageDir = AcapelaVoiceDiagnostics
                    .stageAuthenticVoiceForRun(
                        context = context,
                        voiceDir = verification.voiceDir,
                        runId = runId
                    )
                val stagedCheck = AcapelaVoiceDiagnostics
                    .verifyAuthenticVoicePackage(
                        voiceDir = verification.voiceDir,
                        stagedDir = stageDir,
                        expectedSpeakerHint = expectedSpeaker
                    )
                Log.i(TAG, stagedCheck.report)
                HostLog.i(
                    HostProtocol.ENGINE_ACAPELA,
                    stagedCheck.report
                )
                appendAcapelaFullDiagnostic(stagedCheck.report)
                if (!stagedCheck.ok) {
                    failedTrialBeforeInit(
                        label = "AuthenticVoice (فشل تحقق المرحلي)",
                        runId = runId,
                        startedAtMs = startedAtMs,
                        path = directoryArgument(stageDir),
                        report = stagedCheck.report
                    )
                } else {
                    executeVoiceLoadTrial(
                        label = "AuthenticVoice (نسخة أصلية حرفية)",
                        runId = runId,
                        startedAtMs = startedAtMs,
                        trialDir = stageDir,
                        speaker = expectedSpeaker,
                        iso3 = iso3,
                        registration = regToUse,
                        voiceMode = VOICE_MODE_HD,
                        probeText = "مرحبا",
                        order = AcapelaSequenceOrder.UTF8_LANG_VOICE,
                        useRealCallback = true,
                        trailingSlash = true,
                        forceCallVoice = true,
                        forceVoiceEvenIfInitFailed = false,
                        allowRetry = false,
                        probeFiles = true
                    )
                }
            }

            lastTrialSucceeded.set(trial.isSuccess)
            recordTrialProgress(trial)
            if (trial.details.isNotBlank()) {
                appendAcapelaFullDiagnostic(trial.details)
            }
            if (!trial.isSuccess) {
                trial.service?.let { srv ->
                    runCatching { srv.nStop() }
                    runCatching { srv.nShutdown() }
                }
            }

            val pcmBytes = trial.audioHandler.pcmBytes.toByteArray()
            if (trial.isSuccess && trial.pcmInvoked && pcmBytes.isNotEmpty()) {
                val wavFile = File(
                    context.cacheDir,
                    "acapela_run_${runId}_speak.wav"
                )
                AcapelaVoiceDiagnostics.savePcmAsWav(
                    wavFile,
                    pcmBytes
                )
                Log.i(
                    TAG,
                    "حُفظ نطق التجربة: ${wavFile.absolutePath} " +
                        "(${wavFile.length()} بايت)"
                )
            }

            val startedText = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US
            ).format(Date(startedAtMs))
            fun fmtInv(inv: Boolean, v: Int?) =
                if (!inv) "لم يُستدعَ (غير مقيس)"
                else (v?.toString() ?: "غير مقيس")
            fun fmtLong(inv: Boolean, v: Long?) =
                if (!inv) "غير مقيس" else (v?.toString() ?: "غير مقيس")
            val pcmBytesCount = trial.audioHandler.pcmBytes.size().toLong()
            val summary = buildString {
                appendLine("=== [نتائج تجربة Acapela] ===")
                appendLine("runId: $runId")
                appendLine("البداية: $startedText")
                appendLine("المسار: ${trial.method}")
                appendLine(
                    "nInit: " +
                        "${fmtInv(trial.initInvoked, trial.initCode)}"
                )
                appendLine(
                    "nSetVoiceMode: " +
                        "${fmtInv(trial.modeInvoked, trial.modeCode)}"
                )
                appendLine(
                    "nSetLanguage: " +
                        "${fmtInv(trial.langInvoked, trial.langCode)}"
                )
                appendLine(
                    "nSetCurrentVoice: " +
                        "${fmtInv(trial.voiceInvoked, trial.voiceCode)}"
                )
                appendLine(
                    "nSpeak: " +
                        "${fmtInv(trial.speakInvoked, trial.speakCode)}"
                )
                appendLine(
                    "callback موصول: " +
                        if (!trial.speakInvoked) "غير مقيس"
                        else if (trial.callbackConnected) "نعم" else "لا"
                )
                appendLine(
                    "عينات PCM: " +
                        "${fmtLong(trial.pcmInvoked, trial.samples)}"
                )
                appendLine(
                    "بايتات PCM: " +
                        if (!trial.pcmInvoked) "غير مقيس"
                        else pcmBytesCount.toString()
                )
                appendLine(
                    "أول خطأ أصلي: ${trial.firstErrorLine ?: "لم تُرصد"}"
                )
                appendLine(
                    "النتيجة: ${
                        if (trial.isSuccess) "نجاح حقيقي" else "فشل"
                    }"
                )
            }.trimEnd()
            Log.i(TAG, summary)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, summary)
            appendAcapelaFullDiagnostic(summary)

            if (!trial.isSuccess) {
                logVoiceDiagnostics(
                    voice,
                    "فشل تجربة runId=$runId: ${trial.method}"
                )
                val speakDesc = if (trial.speakInvoked) {
                    "nSpeak=${trial.speakCode ?: "غير مقيس"}"
                } else {
                    "nSpeak=لم يُستدعَ"
                }
                val pcmDesc = if (trial.pcmInvoked) {
                    "PCM=${trial.samples ?: "غير مقيس"}"
                } else {
                    "PCM=غير مقيس"
                }
                return HostResult.failure(
                    "VOICE_LOAD_FAILED",
                    "فشل تحميل صوت Acapela (runId=$runId): " +
                        "$speakDesc, $pcmDesc"
                )
            }

            trial.audioHandler.probeOnly = false
            val srv = trial.service ?: return HostResult.failure(
                "NOT_INITIALIZED",
                "خدمة Acapela المعتمدة غير متوفرة"
            )
            extendedService = srv
            extendedKey = "$voiceKey|$registration"
            Log.i(
                TAG,
                "حزمة Acapela جاهزة [runId=$runId]: " +
                    "$expectedSpeaker " +
                    "(${iso3.language}-${iso3.country})"
            )
            return null
        } catch (t: Throwable) {
            lastTrialSucceeded.set(false)
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val errText = buildString {
                appendLine("=== [استثناء في تجربة Acapela] ===")
                appendLine("runId: $runId")
                appendLine("النوع: ${t.javaClass.name}")
                appendLine("الرسالة: ${t.message ?: "بدون رسالة"}")
                appendLine("التتبع:\n$sw")
            }.trimEnd()
            Log.e(TAG, errText, t)
            HostLog.e(HostProtocol.ENGINE_ACAPELA, errText)
            appendAcapelaFullDiagnostic(errText)
            return HostResult.failure(
                "CONFIGURE_EXCEPTION",
                t.message ?: "استثناء أثناء تجربة Acapela"
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