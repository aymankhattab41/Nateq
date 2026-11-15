package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.content.ContextWrapper
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
import java.nio.file.Files
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
                "تهيئة Acapela الممتد (أصوات مسجلة): أصوات القرص=${voices.size}"
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
            val speakRes = try {
                srv.nSpeak(chunk, null)
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
            AcapelaVoiceManifest.extractRegistrationFromParam(rawParam)
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

        val failure = configureExtendedDual(
            voice = voice,
            iso3 = iso3,
            registration = registration,
            rawParam = rawParam,
            voiceKey = voiceKey
        )
        if (failure != null) {
            return failure
        }
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
        extendedKey = null
    }

    private class TrialAudio(
        private val onSamples: (ByteArray?, Int) -> Unit
    ) : AcattsandroidService.Audio {
        var probeOnly = true
        val samples = AtomicLong(0L)

        override fun samples(bArr: ByteArray?, i: Int) {
            if (i > 0) {
                samples.addAndGet((i / 2).toLong())
            }
            if (!probeOnly) {
                onSamples(bArr, i)
            }
        }
    }

    private data class VoiceLoadTrialResult(
        val method: String,
        val path: String,
        val initCode: Int,
        val modeCode: Int,
        val langCode: Int,
        val voiceCode: Int,
        val speakCode: Int,
        val samples: Long,
        val service: AcattsandroidService?,
        val audioHandler: TrialAudio
    ) {
        val isSuccess: Boolean
            get() = voiceCode == 0 && speakCode == 0 && samples > 0L
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

    private fun readMaskedConfigYaml(voiceDir: File): String {
        val yamlFile = File(voiceDir, "config.yaml")
            .takeIf { it.isFile }
            ?: voiceDir.parentFile?.let { File(it, "config.yaml") }
                ?.takeIf { it.isFile }
            ?: return "(ملف config.yaml غير موجود)"
        val lines = runCatching {
            yamlFile.readLines(StandardCharsets.UTF_8)
        }.getOrElse {
            return "(تعذر قراءة config.yaml: ${it.message})"
        }

        return lines.joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("param:", ignoreCase = true)) {
                val after = trimmed.substringAfter("param:").trim()
                val parsed = AcapelaVoiceManifest.splitConfigYamlParam(after)
                if (parsed != null) {
                    val (vCode, email, hex) = parsed
                    val maskedEmail = AcapelaVoiceDiagnostics
                        .maskMiddle(email, 2, 2)
                    val maskedHex = AcapelaVoiceDiagnostics
                        .maskMiddle(hex, 4, 4)
                    "param: $vCode,$maskedEmail,$maskedHex"
                } else {
                    "param: ***"
                }
            } else if (trimmed.startsWith(
                "registration:",
                ignoreCase = true
            )) {
                "registration: ***"
            } else {
                line
            }
        }
    }

    private fun logPreLoadDiagnostics(
        voice: AcapelaVoiceManifest.VoiceInfo,
        pathPassed: String
    ) {
        val endsWithSlash = pathPassed.endsWith(File.separator) ||
            pathPassed.endsWith("/")
        val filesWithSizes = voice.dir.listFiles()?.joinToString(", ") { f ->
            if (f.isDirectory) "${f.name}/"
            else "${f.name}(${f.length()}B)"
        } ?: "فارغ"
        val maskedYaml = readMaskedConfigYaml(voice.dir)

        val sb = StringBuilder()
        sb.appendLine("[تشخيص قبل تحميل الصوت]")
        sb.appendLine("  المسار الكامل الممرر: $pathPassed")
        sb.appendLine("  هل ينتهي بفاصل: $endsWithSlash")
        sb.appendLine(
            "  قائمة الملفات الفعلية داخل مجلد ${voice.dir.name} مع أحجامها:"
        )
        sb.appendLine("    $filesWithSizes")
        sb.appendLine("  محتوى config.yaml كامل (مع إخفاء التسجيل):")
        maskedYaml.lineSequence().forEach { l ->
            sb.appendLine("    $l")
        }
        val text = sb.toString().trimEnd()
        Log.i(TAG, text)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, text)
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

    private fun checkLoadedNativeLibraries(): String {
        return runCatching {
            val maps = File("/proc/self/maps")
            if (!maps.exists()) return "maps_not_found"
            val text = maps.readText(StandardCharsets.ISO_8859_1)
            val ext = text.contains("libttsacattsandroidextended.so")
            val classic = text.contains("libacattsandroid.so")
            "maps(libttsacattsandroidextended=$ext, libacattsandroid=$classic)"
        }.getOrElse { "maps_error: ${it.message}" }
    }

    private suspend fun executeVoiceLoadTrial(
        label: String,
        stageDir: File,
        speaker: String,
        iso3: Iso3,
        registration: String,
        voice: AcapelaVoiceManifest.VoiceInfo
    ): VoiceLoadTrialResult {
        val stagePath = directoryArgument(stageDir)
        logPreLoadDiagnostics(voice, stagePath)

        val audioHandler = TrialAudio { bytes, len ->
            handleRawAudioBytes(bytes, len)
        }
        audioHandler.probeOnly = true

        val srv = AcattsandroidService(
            audioHandler,
            registration
        )

        val initRes = srv.nInit(stagePath)
        Log.i(TAG, "[$label] nInit=$initRes, path=$stagePath")
        HostLog.i(
            HostProtocol.ENGINE_ACAPELA,
            "[$label] nInit=$initRes, path=$stagePath"
        )
        if (initRes != 0) {
            runCatching { srv.nShutdown() }
            return VoiceLoadTrialResult(
                method = label,
                path = stagePath,
                initCode = initRes,
                modeCode = -1,
                langCode = -1,
                voiceCode = -1,
                speakCode = -1,
                samples = 0L,
                service = null,
                audioHandler = audioHandler
            )
        }

        val modeRes = srv.nSetVoiceMode(VOICE_MODE_HD)
        Log.i(TAG, "[$label] nSetVoiceMode=$modeRes")
        HostLog.i(
            HostProtocol.ENGINE_ACAPELA,
            "[$label] nSetVoiceMode=$modeRes"
        )

        if (AcapelaVoiceManifest.hasUtf8Userdico(stageDir) ||
            AcapelaVoiceManifest.hasUtf8Userdico(voice.dir)
        ) {
            val utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
            Log.i(TAG, "[$label] nUtf8Mode=$utf8Res")
            HostLog.i(
                HostProtocol.ENGINE_ACAPELA,
                "[$label] nUtf8Mode=$utf8Res"
            )
        }

        val langRes = srv.nSetLanguage(iso3.language, iso3.country, "")
        Log.i(TAG, "[$label] nSetLanguage=$langRes")
        HostLog.i(
            HostProtocol.ENGINE_ACAPELA,
            "[$label] nSetLanguage=$langRes"
        )

        var voiceRes = srv.nSetCurrentVoice(speaker)
        Log.i(TAG, "[$label] nSetCurrentVoice($speaker)=$voiceRes")
        HostLog.i(
            HostProtocol.ENGINE_ACAPELA,
            "[$label] nSetCurrentVoice($speaker)=$voiceRes"
        )
        if (voiceRes != 0 && speaker != voice.dir.name) {
            val alt = voice.dir.name
            val altRes = srv.nSetCurrentVoice(alt)
            Log.i(
                TAG,
                "[$label] بديل اسم المجلد: nSetCurrentVoice($alt)=$altRes"
            )
            HostLog.i(
                HostProtocol.ENGINE_ACAPELA,
                "[$label] بديل اسم المجلد: nSetCurrentVoice($alt)=$altRes"
            )
            if (altRes == 0) voiceRes = altRes
        }

        setExtendedProperty(
            srv,
            PUNCTUATION_PAUSE_KEY,
            PUNCTUATION_PAUSE_VALUE
        )

        // تشغيل جملة اختبار واحدة وتسجيل كود nSpeak
        val speakRes = runCatching {
            srv.nSpeak(REGISTRATION_PROBE_TEXT, null)
        }.getOrElse { t ->
            Log.w(TAG, "[$label] استثناء أثناء nSpeak", t)
            -2
        }
        Log.i(TAG, "[$label] جملة اختبار nSpeak=$speakRes")
        HostLog.i(
            HostProtocol.ENGINE_ACAPELA,
            "[$label] جملة اختبار nSpeak=$speakRes"
        )

        if (speakRes == 0) {
            val started = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - started < 500L &&
                audioHandler.samples.get() == 0L
            ) {
                delay(WAIT_STEP_MS)
            }
        }

        val totalSamples = audioHandler.samples.get()
        Log.i(TAG, "[$label] ناتج التجربة: samples=$totalSamples")
        HostLog.i(
            HostProtocol.ENGINE_ACAPELA,
            "[$label] ناتج التجربة: samples=$totalSamples"
        )

        val logFile = File(stageDir, AcapelaVoiceDiagnostics.LOG_FILE_NAME)
            .takeIf { it.exists() && it.length() > 0L }
            ?: File(voice.dir, AcapelaVoiceDiagnostics.LOG_FILE_NAME)
                .takeIf { it.exists() && it.length() > 0L }
        if (logFile != null) {
            val tail = AcapelaVoiceDiagnostics.tailLines(logFile, 10)
            if (tail.isNotEmpty()) {
                val formatted = tail.joinToString("\n")
                Log.w(TAG, "[$label logfile.txt]:\n$formatted")
                HostLog.w(
                    HostProtocol.ENGINE_ACAPELA,
                    "[$label logfile.txt]:\n$formatted"
                )
            }
        }

        return VoiceLoadTrialResult(
            method = label,
            path = stagePath,
            initCode = initRes,
            modeCode = modeRes,
            langCode = langRes,
            voiceCode = voiceRes,
            speakCode = speakRes,
            samples = totalSamples,
            service = srv,
            audioHandler = audioHandler
        )
    }

    private suspend fun configureExtendedDual(
        voice: AcapelaVoiceManifest.VoiceInfo,
        iso3: Iso3,
        registration: String,
        rawParam: String?,
        voiceKey: String
    ): HostResult? {
        val regLen = registration.length

        // الخطوة 3: فحص سلامة ملفات الصوت
        checkAudioFilesIntegrity(voice.dir)

        // الخطوة 2: مقارنة اسم الصوت وتمرير اسم config.yaml إن اختلف
        val yamlVoiceName = extractVoiceNameFromConfigYaml(voice.dir)
        val passedVoiceName = voice.rawSpeaker ?: voice.voiceName
        val dirName = voice.dir.name
        val matchesYaml = yamlVoiceName != null &&
            passedVoiceName.equals(yamlVoiceName, ignoreCase = false)
        val matchesDir = passedVoiceName.equals(dirName, ignoreCase = false)

        val compareLog = "[مقارنة اسم الصوت] الممرر: '$passedVoiceName' | " +
            "في config.yaml: '$yamlVoiceName' (تطابق=$matchesYaml) | " +
            "اسم المجلد: '$dirName' (تطابق=$matchesDir)"
        Log.i(TAG, compareLog)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, compareLog)

        val effectiveSpeaker = if (!yamlVoiceName.isNullOrBlank() &&
            passedVoiceName != yamlVoiceName
        ) {
            Log.i(
                TAG,
                "تمرير اسم الصوت كما في config.yaml: '$yamlVoiceName' " +
                    "(بدلاً من '$passedVoiceName')"
            )
            yamlVoiceName
        } else {
            passedVoiceName
        }

        // الخطوة 4: تجربة الطريقتين (مسار المجلد ومسار المجلد الأب)
        val stageDirDirect = stageVoiceDirect(voice)
        val trial1 = executeVoiceLoadTrial(
            label = "مسار المجلد المباشر",
            stageDir = stageDirDirect,
            speaker = effectiveSpeaker,
            iso3 = iso3,
            registration = registration,
            voice = voice
        )

        val stageDirParent = stageVoiceParent(voice)
        val trial2 = executeVoiceLoadTrial(
            label = "مسار المجلد الأب",
            stageDir = stageDirParent,
            speaker = effectiveSpeaker,
            iso3 = iso3,
            registration = registration,
            voice = voice
        )

        // الخطوة 6: طباعة اسم الطريقة ونتيجة كل محاولة في أول سطر
        val topSummary = "[تشخيص تحميل Acapela] الطرق المجربة: " +
            "1- ${trial1.method} (init=${trial1.initCode}, " +
            "voice=${trial1.voiceCode}, speak=${trial1.speakCode}, " +
            "samples=${trial1.samples}) | " +
            "2- ${trial2.method} (init=${trial2.initCode}, " +
            "voice=${trial2.voiceCode}, speak=${trial2.speakCode}, " +
            "samples=${trial2.samples}) | " +
            "النتيجة: " + when {
                trial2.isSuccess -> "نجاح مسار المجلد الأب"
                trial1.isSuccess -> "نجاح مسار المجلد المباشر"
                trial2.speakCode == 0 -> "نجاح جزئي مسار المجلد الأب"
                trial1.speakCode == 0 -> "نجاح جزئي مسار المجلد المباشر"
                else -> "فشل المحاولتين (كود1=${trial1.speakCode}, " +
                    "كود2=${trial2.speakCode})"
            }
        Log.i(TAG, topSummary)
        HostLog.i(HostProtocol.ENGINE_ACAPELA, topSummary)

        // الخطوة 5: تثبيت المسار الناجح كالمسار الافتراضي
        val (chosenTrial, otherTrial) = if (trial2.isSuccess ||
            (trial2.speakCode == 0 && trial1.speakCode != 0)
        ) {
            trial2 to trial1
        } else if (trial1.isSuccess ||
            (trial1.speakCode == 0 && trial2.speakCode != 0)
        ) {
            trial1 to trial2
        } else {
            trial2 to trial1
        }

        // إغلاق خدمة المحاولة الأخرى
        otherTrial.service?.let {
            runCatching { it.nStop() }
            runCatching { it.nShutdown() }
        }

        if (chosenTrial.voiceCode != 0) {
            chosenTrial.service?.let {
                runCatching { it.nStop() }
                runCatching { it.nShutdown() }
            }
            return HostResult.failure(
                "VOICE_LOAD_FAILED",
                "فشل nSetCurrentVoice($effectiveSpeaker): " +
                    "كود1=${trial1.voiceCode}, كود2=${trial2.voiceCode}"
            )
        }

        if (chosenTrial.speakCode != 0) {
            chosenTrial.service?.let {
                runCatching { it.nStop() }
                runCatching { it.nShutdown() }
            }
            return HostResult.failure(
                "SPEAK_ERROR",
                "فشل nSpeak الممتد: " +
                    "كود1=${trial1.speakCode}, كود2=${trial2.speakCode}"
            )
        }

        // تفعيل ضخ الصوت الفعلي للمستخدم في الخدمة المعتمدة
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

    private fun copyVoiceFiles(
        voice: AcapelaVoiceManifest.VoiceInfo,
        targetDir: File
    ) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val targetUserdicos = File(targetDir, "userdicos")
        if (!targetUserdicos.exists()) {
            targetUserdicos.mkdirs()
        }

        val candidateDirs = mutableListOf<File>()
        candidateDirs.add(voice.dir)
        voice.dir.listFiles { f -> f.isDirectory }?.forEach {
            candidateDirs.add(it)
        }
        voice.dir.parentFile?.let { p ->
            candidateDirs.add(p)
            p.listFiles { f -> f.isDirectory }?.forEach {
                candidateDirs.add(it)
            }
        }

        for (dir in candidateDirs.distinctBy { it.absolutePath }) {
            val files = dir.listFiles { f -> f.isFile } ?: continue
            for (file in files) {
                val name = file.name
                val isRelevant = name.matches(
                    AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
                ) || name.equals("config.yaml", ignoreCase = true) ||
                    name.endsWith(".inf", ignoreCase = true) ||
                    name.endsWith(".ini", ignoreCase = true) ||
                    name.endsWith(".bvcu", ignoreCase = true) ||
                    name.endsWith(".dat", ignoreCase = true) ||
                    name.endsWith(".dic", ignoreCase = true) ||
                    name.endsWith(".bnf", ignoreCase = true) ||
                    name.endsWith(".lic", ignoreCase = true) ||
                    name.endsWith(".pwd", ignoreCase = true) ||
                    name.endsWith("_lf.txt", ignoreCase = true)
                if (isRelevant) {
                    val dest = File(targetDir, name)
                    if (!dest.exists() || dest.length() != file.length()) {
                        if (name.endsWith(".bvcu", ignoreCase = true)) {
                            runCatching {
                                Files.createLink(dest.toPath(), file.toPath())
                            }.onFailure {
                                runCatching {
                                    file.copyTo(dest, overwrite = true)
                                }
                            }
                        } else {
                            runCatching {
                                file.copyTo(dest, overwrite = true)
                            }
                        }
                    }
                }
            }
        }

        val sourceUserdicos = candidateDirs.map { File(it, "userdicos") }
            .firstOrNull { it.isDirectory }
        if (sourceUserdicos != null && sourceUserdicos != targetUserdicos) {
            sourceUserdicos.listFiles { f -> f.isFile }?.forEach { f ->
                val dest = File(targetUserdicos, f.name)
                if (!dest.exists()) {
                    runCatching { f.copyTo(dest, overwrite = true) }
                }
            }
        }

        val hexFile = AcapelaVoiceManifest.findHexRegistrationFile(
            voice.dir
        )
        if (hexFile != null && hexFile.isFile) {
            val destHex = File(targetDir, hexFile.name)
            if (!destHex.exists() || destHex.length() != hexFile.length()) {
                runCatching { hexFile.copyTo(destHex, overwrite = true) }
            }
        }

        val yamlPair = AcapelaVoiceManifest.findConfigYamlWithParam(
            listOfNotNull(voice.dir, voice.dir.parentFile)
        )
        if (yamlPair != null) {
            val destYaml = File(targetDir, "config.yaml")
            if (!destYaml.exists() ||
                destYaml.length() != yamlPair.first.length()
            ) {
                runCatching {
                    yamlPair.first.copyTo(destYaml, overwrite = true)
                }
            }
        }

        targetDir.listFiles { f ->
            f.isFile && f.extension.equals("ini", ignoreCase = true)
        }?.forEach { ini ->
            val txt = runCatching {
                ini.readText(StandardCharsets.ISO_8859_1)
            }.getOrDefault("")
            if (!txt.contains("#") || !txt.contains("MODE")) {
                runCatching { ini.delete() }
            }
        }

        targetDir.listFiles { f ->
            f.isFile && f.extension.equals("inf", ignoreCase = true)
        }?.forEach { inf ->
            AcapelaVoiceManifest.ensureIniFor(inf)
        }

        File(targetDir, "userdicos").mkdirs()
    }

    private fun stageVoiceDirect(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): File {
        val stageDir = File(context.cacheDir, "acapela_stage_direct")
        if (!stageDir.exists()) {
            stageDir.mkdirs()
        }
        val userdicos = File(stageDir, "userdicos")
        if (!userdicos.exists()) {
            userdicos.mkdirs()
        }
        copyVoiceFiles(voice, stageDir)
        return stageDir
    }

    private fun stageVoiceParent(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): File {
        val parentDir = File(context.cacheDir, "acapela_stage_parent")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }
        val parentUserdicos = File(parentDir, "userdicos")
        if (!parentUserdicos.exists()) {
            parentUserdicos.mkdirs()
        }
        val voiceSubDir = File(parentDir, voice.dir.name)
        if (!voiceSubDir.exists()) {
            voiceSubDir.mkdirs()
        }
        val subUserdicos = File(voiceSubDir, "userdicos")
        if (!subUserdicos.exists()) {
            subUserdicos.mkdirs()
        }
        copyVoiceFiles(voice, voiceSubDir)
        subUserdicos.listFiles()?.forEach { f ->
            val dst = File(parentUserdicos, f.name)
            if (!dst.exists() || dst.length() != f.length()) {
                runCatching { f.copyTo(dst, true) }
            }
        }
        return parentDir
    }

    private fun stageVoice(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): File = stageVoiceParent(voice)

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
