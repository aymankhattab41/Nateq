package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.acapelagroup.android.tts.AcattsandroidService
import com.acapelagroup.android.tts.acattsandroid
import com.aymankhattab.nateq.core.audio.engine.SpeechFallbackTracker
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.providers.AcapelaEmbeddedVoiceProvider
import com.aymankhattab.nateq.core.audio.providers.AcapelaLicense
import com.aymankhattab.nateq.core.audio.providers.AcapelaMetadataCodec
import com.aymankhattab.nateq.core.audio.providers.AcapelaParameters
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceDiagnostics
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.audio.providers.EngineStatusRegistry
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** حالات دورة حياة خدمة Acapela الممتدة. */
internal enum class ExtendedServiceState {
    CREATED,
    INITIALIZED,
    VOICE_LOADED,
    SYNTHESIZING,
    POISONED,
    CLOSED
}

/** حزمة صوت Acapela أصلية محققة بالكامل وجاهزة للاستخدام. */
internal data class ResolvedVerifiedPackage(
    val voiceDir: File,
    val iniFile: File?,
    val speaker: String,
    val dependencies: List<String> = emptyList(),
    val metadataFile: File? = null,
    val rawParam: String? = null,
    val registration: String = "",
    val regLength: Int = registration.length,
    val supportDir: File? = null,
    val supportFiles: List<File> = emptyList(),
    val supportRelation: String = "NONE"
)

/** نتيجة فحص عقد وسائط نداءات JNI للتشخيص الآمن وتتبع NULLARG. */
internal data class JniContractAudit(
    val callName: String,
    val argName: String,
    val expectedType: String,
    val actualType: String,
    val presence: String,
    val isNullOrBlank: Boolean,
    val safeLength: Int?,
    val passed: Boolean,
    val notes: String = ""
)

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

        /** الحد الأقصى لمرات الفشل المتتالية قبل تثبيت قفل الجلسة. */
        private const val MAX_CONSECUTIVE_FAILURES = 3

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
    internal var activeManagedService: ManagedExtendedService? = null
    internal var lastResolvedPackage: ResolvedVerifiedPackage? = null

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
    internal var failedTrialKey: String? = null

    /** عداد مرات الفشل المتتالية لكل مفتاح تجربة لمنع القفل من أول محاولة. */
    internal val consecutiveTrialFailures = ConcurrentHashMap<String, Int>()

    /** مجلد التجهيز الفعلي الحالي (إن وجد) للوصول إلى logfile.txt. */
    @Volatile
    private var activeStageDir: File? = null

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

    enum class WarmupState {
        IDLE,
        IN_PROGRESS,
        SUCCESS,
        FAILED
    }

    private val sessionMutex = Mutex()
    private val warmupState = AtomicReference(WarmupState.IDLE)
    @Volatile
    private var lastWarmupFailure: HostResult? = null

    override fun init(dataDir: String): HostResult = runBlocking {
        sessionMutex.withLock {
            doInit(dataDir)
        }
    }

    private fun doInit(dataDir: String): HostResult {
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
            val candidateVoice = voices.firstOrNull { v ->
                val hasReg = v.registration.isNotEmpty() ||
                    AcapelaVoiceManifest.findHexRegistrationFile(v.dir) != null
                hasReg && AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
                    voiceDir = v.dir,
                    expectedSpeakerHint = v.rawSpeaker ?: v.voiceName
                ).ok
            } ?: voices.firstOrNull { it.registration.isNotEmpty() }
              ?: voices.firstOrNull {
                  AcapelaVoiceManifest.findHexRegistrationFile(it.dir) != null
              }

            if (candidateVoice == null) {
                return HostResult.failure(
                    "NO_AUTHENTIC_VOICE",
                    "لم يُعثر على حزمة صوت صالحة ومحققة في ${dir.absolutePath}"
                )
            }

            if (warmupState.compareAndSet(
                    WarmupState.IDLE,
                    WarmupState.IN_PROGRESS
                )
            ) {
                Log.i(
                    TAG,
                    "بدء إحماء Acapela الموحد بعد نجاح التهيئة..."
                )
                val warmupRes = runBlocking {
                    performUnifiedWarmup(candidateVoice)
                }
                if (!warmupRes.ok) {
                    warmupState.set(WarmupState.FAILED)
                    lastWarmupFailure = warmupRes
                    releaseEngine()
                    return warmupRes
                }
                warmupState.set(WarmupState.SUCCESS)
                lastWarmupFailure = null
            } else if (warmupState.get() == WarmupState.FAILED) {
                return lastWarmupFailure ?: HostResult.failure(
                    "WARMUP_FAILED",
                    "فشل إحماء محرك Acapela مسبقاً — الخدمة غير جاهزة"
                )
            }
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

    private fun isVoiceActuallyLoaded(
        srv: AcattsandroidService?,
        speaker: String
    ): Boolean {
        if (srv == null) return false
        val recentLogs = AcapelaVoiceDiagnostics
            .readNativeLogcatLines(60)
        return AcapelaVoiceDiagnostics.isSpeakerLoadedInLogs(
            speaker = speaker,
            logs = recentLogs
        )
    }

    private suspend fun performUnifiedWarmup(
        voice: AcapelaVoiceManifest.VoiceInfo
    ): HostResult {
        val dummySink = PcmSink(
            DataOutputStream(ByteArrayOutputStream())
        )
        val cancelled = AtomicBoolean(false)
        val openResult = runCatching {
            openExtendedEngine(voice, dummySink, cancelled)
        }.getOrElse { t ->
            Log.e(TAG, "استثناء أثناء إحماء Acapela الموحد", t)
            HostResult.failure(
                "WARMUP_EXCEPTION",
                t.message ?: "استثناء أثناء الإحماء الموحد"
            )
        }
        if (openResult != null) {
            return openResult
        }
        val srv = extendedService
        if (srv == null || !lastTrialSucceeded.get()) {
            return HostResult.failure(
                "WARMUP_FAILED",
                "فشل إحماء صوت Acapela: تعذر الحصول على خدمة جاهزة"
            )
        }
        return HostResult(ok = true)
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

    /** إغلاق المحرك الممتد الحالي (إن وُجد) وتصفير مفتاحه بأمان. */
    private fun closeExtended() {
        activeManagedService?.safeRelease()
        activeManagedService = null
        extendedService = null
        extendedKey = null
        lastTrialSucceeded.set(false)
        activeStageDir = null
        runCatching {
            AcapelaVoiceDiagnostics.cleanupOldStagingDirectories(context, null)
        }
    }

    /**
     * تصفير قفل المحاولة لتمكين إعادة التجربة فوراً بعد أي تغيير أو نجاح.
     */
    fun resetTrialLock(key: String? = null) {
        if (key != null) {
            consecutiveTrialFailures.remove(key)
            if (failedTrialKey == key) failedTrialKey = null
        } else {
            consecutiveTrialFailures.clear()
            failedTrialKey = null
        }
        extendedKey = null
        lastTrialSucceeded.set(false)
    }

    /** تحرير المحركات الحالية وتصفير حالتها (قبل إعادة التهيئة). */
    private fun releaseEngine() {
        acatts?.let { shutdownQuietly(it) }
        acatts = null
        closeExtended()
        resetTrialLock()
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
    ): HostResult = sessionMutex.withLock {
        if (dataDirectory == null) {
            val waitStart = SystemClock.uptimeMillis()
            while (dataDirectory == null &&
                SystemClock.uptimeMillis() - waitStart < 3000L
            ) {
                delay(100L)
            }
        }
        doSpeak(req, sink, cancelled)
    }

    private suspend fun doSpeak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        if (cancelled.get()) {
            return HostResult.failure("CANCELLED", "أُلغي الطلب قبل بدئه")
        }
        if (warmupState.get() == WarmupState.FAILED) {
            val failure = lastWarmupFailure ?: HostResult.failure(
                "WARMUP_FAILED",
                "خدمة Acapela غير جاهزة بسبب فشل الإحماء"
            )
            Log.w(
                TAG,
                "منع النطق على خدمة غير جاهزة: ${failure.message}"
            )
            return failure
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
            val dir = dataDirectory ?: run {
                Log.w(
                    TAG,
                    "تأخرت تهيئة مجلد بيانات Acapela؛ تحويل آمن دون خطأ حرج"
                )
                return HostResult.failure(
                    "DIR_NOT_FOUND",
                    "مجلد بيانات Acapela لم يُضبط في الوقت المحدد"
                )
            }
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

        val runId = UUID.randomUUID().toString().take(8)
        val myPid = android.os.Process.myPid()
        val hostGen = EngineHostClient.hostGeneration.get()
        val startMarker =
            "=== START_REAL_SPEAK_NATIVE_${voice.id}_${runId}_" +
                "G${hostGen}_PID${myPid} ==="
        val endMarker =
            "=== END_REAL_SPEAK_NATIVE_${voice.id}_${runId}_" +
                "G${hostGen}_PID${myPid} ==="
        Log.i("acattsandroid-extended-native", startMarker)

        for (chunk in chunks) {
            if (cancelled.get()) {
                Log.i("acattsandroid-extended-native", endMarker)
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }

            sink.enterNativeCall(nativeCapMs)
            val speakRes = try {
                inst.nSpeak(chunk)
            } catch (t: Throwable) {
                Log.e(TAG, "استثناء في nSpeak الكلاسيكي", t)
                -2
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
                return handleRealSpeechFailure(
                    voice = voice,
                    path = "classic",
                    chunk = chunk,
                    speakRes = speakRes,
                    startMarker = startMarker,
                    endMarker = endMarker,
                    runId = runId
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
            return handleRealSpeechFailure(
                voice = voice,
                path = "classic",
                chunk = chunks.firstOrNull().orEmpty(),
                speakRes = 0,
                startMarker = startMarker,
                endMarker = endMarker,
                runId = runId
            )
        }
        Log.i("acattsandroid-extended-native", endMarker)
        onRealSpeechSuccess(voice)
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
        val runId = UUID.randomUUID().toString().take(8)
        val myPid = android.os.Process.myPid()
        val hostGen = EngineHostClient.hostGeneration.get()
        val startMarker =
            "=== START_REAL_SPEAK_${voice.id}_${runId}_" +
                "G${hostGen}_PID${myPid} ==="
        val endMarker =
            "=== END_REAL_SPEAK_${voice.id}_${runId}_" +
                "G${hostGen}_PID${myPid} ==="
        Log.i("acattsandroid-extended-native", startMarker)

        val speaker = voice.rawSpeaker ?: voice.voiceName
        val managed = activeManagedService
        if (managed == null ||
            managed.state != ExtendedServiceState.VOICE_LOADED ||
            !isVoiceActuallyLoaded(srv, speaker)
        ) {
            managed?.markPoisoned(
                "الصوت $speaker غير محمل في الخدمة الممتدة"
            )
            Log.e(
                TAG,
                "الصوت $speaker غير محمل في محرك Acapela " +
                    "(الحالة=${managed?.state})"
            )
            return HostResult.failure(
                "VOICE_LOAD_FAILED",
                "الصوت $speaker غير محمل في محرك Acapela"
            )
        }

        for (chunk in chunks) {
            if (cancelled.get()) {
                Log.i("acattsandroid-extended-native", endMarker)
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }
            sink.enterNativeCall(nativeCapMs)
            val synthCallback = AcapelaSynthesisCallback { bArr, len ->
                handleRawAudioBytes(bArr, len)
            }
            managed.markSynthesizing()
            val speakRes = try {
                srv.nSpeak(chunk, synthCallback)
            } catch (t: Throwable) {
                Log.e(TAG, "استثناء في nSpeak الممتد", t)
                -2
            } finally {
                sink.exitNativeCall()
                managed.markSynthesisDone()
            }
            val speakLog = "Acapela Extended: nSpeak=$speakRes, " +
                "regLength=$regLen, chunkChars=${chunk.length}"
            Log.i(TAG, speakLog)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, speakLog)
            if (speakRes != 0) {
                managed.markPoisoned("فشل nSpeak الممتد: $speakRes")
                Log.e(TAG, "فشل nSpeak في Acapela الممتد: $speakRes")
                return handleRealSpeechFailure(
                    voice = voice,
                    path = "extended",
                    chunk = chunk,
                    speakRes = speakRes,
                    startMarker = startMarker,
                    endMarker = endMarker,
                    runId = runId
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
            return handleRealSpeechFailure(
                voice = voice,
                path = "extended",
                chunk = chunks.firstOrNull().orEmpty(),
                speakRes = 0,
                startMarker = startMarker,
                endMarker = endMarker,
                runId = runId
            )
        }
        Log.i("acattsandroid-extended-native", endMarker)
        onRealSpeechSuccess(voice)
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

    internal fun onRealSpeechSuccess(voice: AcapelaVoiceManifest.VoiceInfo) {
        val voiceKey = registrationKey(voice)
        val trialKey = "$voiceKey|${voice.registration}"
        consecutiveTrialFailures.remove(trialKey)
        failedTrialKey = null
        lastTrialSucceeded.set(true)
        runCatching {
            SpeechFallbackTracker.clearAcapelaFallbackState()
            EngineStatusRegistry.noteReady(
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
            EngineStatusRegistry.noteSpeakSuccess(
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
        }
    }

    internal fun handleRealSpeechFailure(
        voice: AcapelaVoiceManifest.VoiceInfo,
        path: String,
        chunk: String,
        speakRes: Int?,
        startMarker: String,
        endMarker: String,
        runId: String = UUID.randomUUID().toString().take(8)
    ): HostResult {
        Log.i("acattsandroid-extended-native", endMarker)

        val samples = totalAudioSamples.get()
        val bytes = totalAudioBytes.get()
        val nativeLines = AcapelaVoiceDiagnostics.readLogcatBetweenMarkers(
            startMarker,
            endMarker,
            1000
        )
        val effectiveLogFile = activeStageDir?.let { sDir ->
            File(sDir, AcapelaVoiceDiagnostics.LOG_FILE_NAME).takeIf {
                it.exists() && it.length() > 0L
            } ?: sDir.walkTopDown().filter {
                it.isFile && it.length() > 0L &&
                    it.name.equals(AcapelaVoiceDiagnostics.LOG_FILE_NAME, true)
            }.maxByOrNull { it.lastModified() }
        } ?: File(voice.dir, AcapelaVoiceDiagnostics.LOG_FILE_NAME).takeIf {
            it.exists() && it.length() > 0L
        }
        val logFileContent = if (effectiveLogFile != null) {
            runCatching {
                AcapelaVoiceDiagnostics.redactSensitiveText(
                    effectiveLogFile.readText(StandardCharsets.ISO_8859_1)
                )
            }.getOrDefault("(تعذر قراءة logfile.txt)")
        } else {
            "(لم يُنشأ ملف logfile.txt أو فارغ)"
        }
        val firstErr = AcapelaVoiceDiagnostics
            .findFirstErrorAndPreceding(nativeLines)

        val failTimestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val failureSection = buildString {
            appendLine("=== آخر محاولة نطق حقيقية فاشلة ===")
            appendLine("runId: $runId")
            appendLine("الوقت: $failTimestamp")
            appendLine(
                "الصوت: " +
                    "${voice.displayName.ifBlank { voice.id }} " +
                    "(${voice.locale.toLanguageTag()})"
            )
            appendLine("المسار: $path")
            appendLine("النص: \"${chunk.take(100)}\"")
            appendLine("كود رجوع nSpeak الرقمي: ${speakRes ?: "غير مقيس"}")
            appendLine("كود nSpeak: ${speakRes ?: "غير مقيس"}")
            appendLine("عدد العينات: $samples")
            appendLine("عينات PCM: $samples")
            appendLine("بايتات PCM: $bytes")
            appendLine(
                "أول خطأ أصلي في الأسطر: " +
                    "${firstErr?.first ?: "لم تُرصد"}"
            )
            appendLine("أول خطأ أصلي: ${firstErr?.first ?: "لم تُرصد"}")
            if (firstErr?.second != null &&
                firstErr.second != "(لا يوجد سطر سابق)"
            ) {
                appendLine("السطر السابق له: ${firstErr.second}")
            }
            appendLine()
            val logFileSizeDesc = if (effectiveLogFile != null) {
                "${effectiveLogFile.length()} بايت"
            } else {
                "غير موجود"
            }
            appendLine("--- سجل logfile.txt ($logFileSizeDesc) ---")
            appendLine(
                AcapelaVoiceDiagnostics.redactSensitiveText(
                    logFileContent
                ).trimEnd()
            )
            appendLine()
            appendLine(
                "--- أسطر المكتبة الأصلية (acattsandroid) " +
                    "(${nativeLines.size} سطر) ---"
            )
            if (nativeLines.isEmpty()) {
                appendLine("(لم يُعثر على أسطر في سجل المكتبة الأصلية)")
            } else {
                AcapelaVoiceDiagnostics.redactSensitiveLines(nativeLines)
                    .forEach { line ->
                        appendLine(line)
                    }
            }
        }.trimEnd()

        runCatching {
            val failFile = File(
                context.cacheDir,
                AcapelaVoiceDiagnostics.ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
            )
            failFile.writeText(failureSection, StandardCharsets.UTF_8)
        }

        val exitInfo = HostCrashDiagnostics.collectExitInfo(context)
        val isConfirmedCrash = exitInfo.found && exitInfo.reasonCode == 5
        val exitDesc = if (exitInfo.found) {
            exitInfo.description
        } else {
            "لم يُرصد خروج مؤكد عبر ApplicationExitInfo"
        }

        val buildVerName = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.6.71"

        val buildCode = runCatching {
            val pInfo = context.packageManager
                .getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo)
        }.getOrDefault(10671L)

        val connectionGen = EngineHostClient.hostGeneration.get()

        val firstRealRecord = AcapelaVoiceDiagnostics.FirstFailureRecord(
            buildVersion = buildVerName,
            buildCode = buildCode,
            timestamp = failTimestamp,
            runId = runId,
            connectionGeneration = connectionGen,
            engineName = HostProtocol.ENGINE_ACAPELA,
            stage = "RealSpeech",
            verifiedVoiceDir = voice.dir.absolutePath,
            speaker = voice.rawSpeaker ?: voice.voiceName,
            initInvoked = true,
            initCode = 0,
            voiceInvoked = true,
            voiceCode = 0,
            speakInvoked = true,
            speakCode = speakRes,
            speakDesc = "nSpeak=$speakRes",
            samples = samples,
            bytes = bytes,
            exitReasonDesc = exitDesc,
            isConfirmedNativeCrash = isConfirmedCrash,
            evidenceState = if (speakRes == 0 && samples > 0) {
                AcapelaVoiceDiagnostics.VoiceEvidenceState.LOADED
            } else {
                AcapelaVoiceDiagnostics.VoiceEvidenceState.LOAD_FAILED
            },
            evidenceSource = "real_speech",
            missingEvidenceElements = emptyList()
        )

        AcapelaVoiceDiagnostics.recordFirstFailure(
            context = context,
            record = firstRealRecord,
            nativeTail = nativeLines,
            logFileContent = logFileContent
        )

        appendAcapelaFullDiagnostic(failureSection)

        val voiceKey = registrationKey(voice)
        val trialKey = "$voiceKey|${voice.registration}"
        val currentFailures = (consecutiveTrialFailures[trialKey] ?: 0) + 1
        consecutiveTrialFailures[trialKey] = currentFailures
        if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
            failedTrialKey = trialKey
            Log.w(
                TAG,
                "تثبيت قفل جلسة Acapela بعد $currentFailures محاولات " +
                    "فاشلة متتالية للصوت $trialKey"
            )
        } else {
            failedTrialKey = null
            Log.i(
                TAG,
                "فشل عابر ($currentFailures/$MAX_CONSECUTIVE_FAILURES) " +
                    "للصوت $trialKey؛ سيُعاد في المحاولة التالية"
            )
        }

        forgetRegistration(voice)
        closeExtended()

        val errSummary = "فشل nSpeak ($speakRes) | عينات=$samples | " +
            "أول خطأ: ${firstErr?.first ?: "لم تُرصد"}"
        return HostResult.failure("SPEAK_ERROR", errSummary)
    }

    private fun recordTrialFailure(
        voice: AcapelaVoiceManifest.VoiceInfo,
        runId: String,
        trial: VoiceLoadTrialResult
    ) {
        val failTimestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        ).format(Date())
        val logFileContent = trial.logFileContent.ifBlank {
            "(لم يُنشأ ملف logfile.txt أو فارغ)"
        }
        val firstErr = AcapelaVoiceDiagnostics
            .findFirstErrorAndPreceding(trial.nativeLines)

        val failureTitle = if (trial.speakInvoked) {
            "=== آخر محاولة نطق حقيقية فاشلة ==="
        } else {
            "=== فشل تحميل الصوت قبل استدعاء النطق ==="
        }
        val speakCodeText = if (trial.speakInvoked) {
            trial.speakCode?.toString() ?: "غير مقيس"
        } else {
            "null (لم يُستدعَ)"
        }
        val pcmSamplesText = if (trial.speakInvoked) {
            (trial.samples ?: 0L).toString()
        } else {
            "غير مقيس"
        }
        val pcmBytesText = if (trial.speakInvoked) {
            (trial.pcmBytes ?: 0L).toString()
        } else {
            "غير مقيس"
        }

        val failureSection = buildString {
            appendLine(failureTitle)
            appendLine("runId: $runId")
            appendLine("الوقت: $failTimestamp")
            appendLine(
                "الصوت: " +
                    "${voice.displayName.ifBlank { voice.id }} " +
                    "(${voice.locale.toLanguageTag()})"
            )
            appendLine("المسار: ${trial.method}")
            appendLine("النص: \"مرحبا\"")
            appendLine("كود رجوع nSpeak الرقمي: $speakCodeText")
            appendLine("كود nSpeak: $speakCodeText")
            appendLine("عدد العينات: $pcmSamplesText")
            appendLine("عينات PCM: $pcmSamplesText")
            appendLine("بايتات PCM: $pcmBytesText")
            appendLine(
                "أول خطأ أصلي في الأسطر: " +
                    "${firstErr?.first ?: trial.firstErrorLine ?: "لم تُرصد"}"
            )
            appendLine(
                "أول خطأ أصلي: " +
                    "${firstErr?.first ?: trial.firstErrorLine ?: "لم تُرصد"}"
            )
            val prevLine = firstErr?.second ?: trial.precedingLine
            if (prevLine != null && prevLine != "(لا يوجد سطر سابق)") {
                appendLine("السطر السابق له: $prevLine")
            }
            appendLine()
            appendLine("--- سجل logfile.txt ---")
            appendLine(
                AcapelaVoiceDiagnostics.redactSensitiveText(
                    logFileContent
                ).trimEnd()
            )
            appendLine()
            appendLine(
                "--- أسطر المكتبة الأصلية (acattsandroid) " +
                    "(${trial.nativeLines.size} سطر) ---"
            )
            if (trial.nativeLines.isEmpty()) {
                appendLine("(لم يُعثر على أسطر في سجل المكتبة الأصلية)")
            } else {
                AcapelaVoiceDiagnostics.redactSensitiveLines(
                    trial.nativeLines
                ).forEach { line ->
                    appendLine(line)
                }
            }
        }.trimEnd()

        runCatching {
            val failFile = File(
                context.cacheDir,
                AcapelaVoiceDiagnostics.ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
            )
            failFile.writeText(failureSection, StandardCharsets.UTF_8)
        }

        val exitInfo = HostCrashDiagnostics.collectExitInfo(context)
        val isConfirmedCrash = exitInfo.found && exitInfo.reasonCode == 5
        val exitDesc = if (exitInfo.found) {
            exitInfo.description
        } else {
            "لم يُرصد خروج مؤكد عبر ApplicationExitInfo"
        }

        val buildVerName = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.6.71"

        val buildCode = runCatching {
            val pInfo = context.packageManager
                .getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo)
        }.getOrDefault(10671L)

        val connectionGen = EngineHostClient.hostGeneration.get()

        val speakDesc = if (trial.speakInvoked) {
            "nSpeak=${trial.speakCode ?: "غير مقيس"}"
        } else {
            "لم يُستدعَ"
        }

        val firstRecord = AcapelaVoiceDiagnostics.FirstFailureRecord(
            buildVersion = buildVerName,
            buildCode = buildCode,
            timestamp = failTimestamp,
            runId = runId,
            connectionGeneration = connectionGen,
            engineName = HostProtocol.ENGINE_ACAPELA,
            stage = trial.method,
            verifiedVoiceDir = trial.path,
            speaker = voice.rawSpeaker ?: voice.voiceName,
            initInvoked = trial.initInvoked,
            initCode = trial.initCode,
            voiceInvoked = trial.voiceInvoked,
            voiceCode = trial.voiceCode,
            speakInvoked = trial.speakInvoked,
            speakCode = if (trial.speakInvoked) trial.speakCode else null,
            speakDesc = speakDesc,
            samples = if (trial.speakInvoked) (trial.samples ?: 0L) else 0L,
            bytes = if (trial.speakInvoked) (trial.pcmBytes ?: 0L) else 0L,
            exitReasonDesc = exitDesc,
            isConfirmedNativeCrash = isConfirmedCrash,
            evidenceState = trial.evidenceState,
            evidenceSource = trial.evidenceSource,
            missingEvidenceElements = trial.missingEvidenceElements,
            provenFailedResource = trial.firstFailedResource
        )

        AcapelaVoiceDiagnostics.recordFirstFailure(
            context = context,
            record = firstRecord,
            nativeTail = trial.nativeLines,
            logFileContent = logFileContent
        )

        appendAcapelaFullDiagnostic(failureSection)
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
        if (voice.sampleRate != 22050) {
            return HostResult.failure(
                "INVALID_SAMPLE_RATE",
                "محرك Acapela الممتد يتطلب معدل عينات 22050 هرتز"
            )
        }

        val voiceKey = registrationKey(voice)
        val cachedPackage = lastResolvedPackage
        if (cachedPackage != null &&
            extendedService != null &&
            lastTrialSucceeded.get() &&
            extendedKey == "$voiceKey|${cachedPackage.registration}" &&
            activeManagedService?.state == ExtendedServiceState.VOICE_LOADED
        ) {
            return null
        }

        val failCount = consecutiveTrialFailures[voiceKey] ?: 0
        if (failedTrialKey?.startsWith("$voiceKey|") == true &&
            failCount >= MAX_CONSECUTIVE_FAILURES
        ) {
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

        val failure = configureExtendedTriple(
            voice = voice,
            iso3 = iso3,
            voiceKey = voiceKey
        )
        if (failure != null) {
            val count = (consecutiveTrialFailures[voiceKey] ?: 0) + 1
            consecutiveTrialFailures[voiceKey] = count
            if (count >= MAX_CONSECUTIVE_FAILURES) {
                failedTrialKey = "$voiceKey|failed"
                Log.w(
                    TAG,
                    "تثبيت قفل تجربة Acapela بعد $count محاولات " +
                        "فاشلة متتالية ($voiceKey)"
                )
            } else {
                failedTrialKey = null
                Log.i(
                    TAG,
                    "فشل تجربة عابر ($count/$MAX_CONSECUTIVE_FAILURES) " +
                        "لـ $voiceKey؛ سيُعاد في المحاولة التالية"
                )
            }
            closeExtended()
            return failure
        }
        consecutiveTrialFailures.remove(voiceKey)
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
        val vKey = registrationKey(voice)
        chosenRegistrations.remove(vKey)
        runCatching {
            File(voice.dir, REGISTRATION_CHOICE_FILE).delete()
        }
        extendedKey = null
    }

    internal class TrialAudio(
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

    internal class ManagedExtendedService(
        val service: AcattsandroidService,
        val audioHandler: TrialAudio,
        val resolvedPackage: ResolvedVerifiedPackage?
    ) {
        var state: ExtendedServiceState = ExtendedServiceState.CREATED
            private set

        fun markInitialized() {
            if (state == ExtendedServiceState.CREATED) {
                state = ExtendedServiceState.INITIALIZED
            }
        }

        fun markVoiceLoaded() {
            if (state == ExtendedServiceState.INITIALIZED ||
                state == ExtendedServiceState.CREATED
            ) {
                state = ExtendedServiceState.VOICE_LOADED
            }
        }

        fun markSynthesizing() {
            if (state == ExtendedServiceState.VOICE_LOADED) {
                state = ExtendedServiceState.SYNTHESIZING
            }
        }

        fun markSynthesisDone() {
            if (state == ExtendedServiceState.SYNTHESIZING) {
                state = ExtendedServiceState.VOICE_LOADED
            }
        }

        fun markPoisoned(reason: String) {
            if (state != ExtendedServiceState.CLOSED) {
                state = ExtendedServiceState.POISONED
                Log.w(TAG, "[ManagedService] عُزلت الخدمة كمسمومة: $reason")
            }
        }

        fun safeRelease(isCurrentSynthesizing: Boolean = false) {
            val cur = state
            if (cur == ExtendedServiceState.CLOSED) return
            try {
                if (cur == ExtendedServiceState.SYNTHESIZING ||
                    isCurrentSynthesizing
                ) {
                    runCatching { service.nStop() }
                }
                if (cur == ExtendedServiceState.INITIALIZED ||
                    cur == ExtendedServiceState.VOICE_LOADED
                ) {
                    runCatching { service.nShutdown() }
                } else if (cur == ExtendedServiceState.POISONED) {
                    Log.i(
                        TAG,
                        "[ManagedService] تخطي nShutdown للخدمة المسمومة " +
                            "لمنع انهيار unload الأصلي"
                    )
                }
            } finally {
                state = ExtendedServiceState.CLOSED
                runCatching { audioHandler.pcmBytes.reset() }
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
        val startedAtMs: Long = 0L,
        val managedService: ManagedExtendedService? = null,
        val evidenceState: AcapelaVoiceDiagnostics.VoiceEvidenceState =
            AcapelaVoiceDiagnostics.VoiceEvidenceState.EVIDENCE_UNAVAILABLE,
        val evidenceSource: String = "none",
        val missingEvidenceElements: List<String> = emptyList(),
        val firstFailedResource: String = "غير محدد"
    ) {
        val isSuccess: Boolean
            get() = speakInvoked && (speakCode == 0) &&
                ((samples ?: 0L) > 0L) &&
                AcapelaVoiceDiagnostics.evaluateTrialSuccess10724(
                    speakCode = speakCode,
                    samples = samples,
                    nativeLines = nativeLines,
                    callbackConnected = callbackConnected,
                    invoked = speakInvoked && pcmInvoked,
                    speaker = managedService?.resolvedPackage?.speaker
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
        resolvedPackage: ResolvedVerifiedPackage,
        iso3: Iso3,
        registration: String,
        voiceMode: String = VOICE_MODE_HD,
        extraProperties: Map<String, String> = emptyMap(),
        probeText: String = REGISTRATION_PROBE_TEXT,
        order: AcapelaSequenceOrder = AcapelaSequenceOrder.UTF8_LANG_VOICE,
        useRealCallback: Boolean = true,
        allowRetry: Boolean = false,
        probeFiles: Boolean = false
    ): VoiceLoadTrialResult {
        val trialDir = resolvedPackage.voiceDir
        val speaker = resolvedPackage.speaker
        val stagePath = directoryArgument(trialDir)
        val cleanLabel = label.filter { it.isLetterOrDigit() }

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

        val audits = mutableListOf<JniContractAudit>()

        audits.add(
            JniContractAudit(
                callName = "AcattsandroidService.<init>",
                argName = "audio",
                expectedType = "AcattsandroidService.Audio",
                actualType = "AcapelaHostSession.TrialAudio",
                presence = "موجود",
                isNullOrBlank = false,
                safeLength = null,
                passed = true,
                notes = "واجهة استقبال عينات الصوت"
            )
        )
        val regPassed = registration.isNotBlank()
        audits.add(
            JniContractAudit(
                callName = "AcattsandroidService.<init>",
                argName = "registration",
                expectedType = "String",
                actualType = "String",
                presence = if (regPassed) "موجود" else "فارغ",
                isNullOrBlank = !regPassed,
                safeLength = registration.length,
                passed = regPassed,
                notes = "بيانات التسجيل (طول آمن=${registration.length})"
            )
        )
        val pathPassed = stagePath.isNotBlank() && trialDir.isDirectory
        audits.add(
            JniContractAudit(
                callName = "nInit",
                argName = "path",
                expectedType = "String",
                actualType = "String",
                presence = if (stagePath.isNotBlank()) "موجود" else "فارغ",
                isNullOrBlank = stagePath.isBlank(),
                safeLength = stagePath.length,
                passed = pathPassed,
                notes = "مسار مجلد الحزمة (nInit يستقبل هذا المسار فقط، " +
                    "وبقية الحقول تخص نداءات منفصلة)"
            )
        )
        audits.add(
            JniContractAudit(
                callName = "nSetVoiceMode",
                argName = "mode",
                expectedType = "String",
                actualType = "String",
                presence = if (voiceMode.isNotBlank()) "موجود" else "فارغ",
                isNullOrBlank = voiceMode.isBlank(),
                safeLength = voiceMode.length,
                passed = voiceMode.isNotBlank(),
                notes = "نمط الصوت (HD)"
            )
        )
        audits.add(
            JniContractAudit(
                callName = "nUtf8Mode",
                argName = "lang, country",
                expectedType = "String, String",
                actualType = "String, String",
                presence = "موجود",
                isNullOrBlank = false,
                safeLength = iso3.language.length + iso3.country.length,
                passed = iso3.language.isNotBlank() &&
                    iso3.country.isNotBlank(),
                notes = "ترميز UTF-8 للغة والدولة"
            )
        )
        audits.add(
            JniContractAudit(
                callName = "nSetLanguage",
                argName = "lang, country, variant",
                expectedType = "String, String, String",
                actualType = "String, String, String",
                presence = "موجود",
                isNullOrBlank = false,
                safeLength = iso3.language.length + iso3.country.length,
                passed = iso3.language.isNotBlank() &&
                    iso3.country.isNotBlank(),
                notes = "ضبط لغة المتحدث"
            )
        )
        val speakerPassed = speaker.isNotBlank()
        audits.add(
            JniContractAudit(
                callName = "nSetCurrentVoice",
                argName = "speaker",
                expectedType = "String",
                actualType = "String",
                presence = if (speakerPassed) "موجود" else "فارغ",
                isNullOrBlank = !speakerPassed,
                safeLength = speaker.length,
                passed = speakerPassed,
                notes = "اسم المتحدث المطلوب تفعيله"
            )
        )
        audits.add(
            JniContractAudit(
                callName = "nSpeak",
                argName = "text",
                expectedType = "String",
                actualType = "String",
                presence = if (probeText.isNotBlank()) "موجود" else "فارغ",
                isNullOrBlank = probeText.isBlank(),
                safeLength = probeText.length,
                passed = probeText.isNotBlank(),
                notes = "نص التجربة"
            )
        )
        audits.add(
            JniContractAudit(
                callName = "nSpeak",
                argName = "callback",
                expectedType = "SynthesisCallback",
                actualType = if (useRealCallback) {
                    "AcapelaSynthesisCallback"
                } else "null",
                presence = if (useRealCallback) "موجود" else "null",
                isNullOrBlank = false,
                safeLength = null,
                passed = true,
                notes = "مستقبل عينات النطق"
            )
        )

        val srv = AcattsandroidService(
            audioHandler,
            registration
        )

        val managed = ManagedExtendedService(
            srv,
            audioHandler,
            resolvedPackage
        )

        val idFuncVal = runCatching {
            srv.idfunction()
        }.getOrNull().orEmpty()
        val maskedReg = if (idFuncVal.isNotEmpty()) {
            "[محجوب]"
        } else {
            "(فارغ)"
        }

        val myPid = android.os.Process.myPid()
        val hostGen = EngineHostClient.hostGeneration.get()
        val logcatReader = AcapelaVoiceDiagnostics.AcapelaLogcatReader(
            runId = runId,
            hostGeneration = hostGen,
            pid = myPid
        )
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

        try {
            logcatReader.start()
            logcatReader.emitStartMarker()
            val startMarkerSeen = logcatReader.waitForStartMarker(500L)
            val startMarker = logcatReader.startMarker
            val endMarker = logcatReader.endMarker

            recordAcapelaBreadcrumb("[$label] nInit($stagePath)")
        val fdsBefore = AcapelaVoiceDiagnostics.getOpenFileDescriptors()
        var probeReport = ""
        var initRes: Int? = null
        try {
            initRes = srv.nInit(stagePath)
        } catch (t: Throwable) {
            Log.e(TAG, "[$label] استثناء أثناء nInit", t)
            initRes = null
            managed.markPoisoned("استثناء nInit: ${t.message}")
        } finally {
            probeReport = runCatching { fileProbe?.stopAndLog() }
                .getOrNull().orEmpty()
        }
        val initInvoked = true
        if (initRes == 0) {
            managed.markInitialized()
        } else if (managed.state != ExtendedServiceState.POISONED) {
            managed.markPoisoned("فشل nInit: كود=$initRes")
        }

        var modeInvoked = false
        var modeRes: Int? = null
        if (managed.state == ExtendedServiceState.INITIALIZED) {
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
        if (managed.state == ExtendedServiceState.INITIALIZED) {
            for ((propKey, propVal) in extraProperties) {
                val res = setExtendedProperty(srv, propKey, propVal)
                extraResMap[propKey] = res
            }
        }

        val earlyLogs = logcatReader.getCapturedLines()
        val loadVoiceLines = earlyLogs.filter {
            it.contains("tts_function_load_voice")
        }
        val earlyLoadLine = loadVoiceLines.lastOrNull().orEmpty()
        val earlyEbMatch = Regex("eb\\s+(-?\\d+)").find(earlyLoadLine)
        val earlyEbVal = earlyEbMatch?.groupValues?.get(1)?.toIntOrNull()

        val isFreshLogFileEarly = trialLogFile.exists() &&
            trialLogFile.length() > 0L &&
            trialLogFile.lastModified() >= (startedAtMs - 500L)

        val earlyEvidence = AcapelaVoiceDiagnostics.evaluateVoiceEvidenceState(
            speaker = speaker,
            logs = earlyLogs,
            expectedPid = myPid,
            source = if (startMarkerSeen && earlyLogs.isNotEmpty()) {
                "logcat"
            } else "none",
            boundariesFound = startMarkerSeen
        )

        if (managed.state == ExtendedServiceState.INITIALIZED) {
            when (earlyEvidence.state) {
                AcapelaVoiceDiagnostics.VoiceEvidenceState.LOADED -> {
                    managed.markVoiceLoaded()
                }
                AcapelaVoiceDiagnostics.VoiceEvidenceState.LOAD_FAILED -> {
                    managed.markPoisoned(
                        "الصوت $speaker لم يُحمَّل في المكتبة الأصلية: " +
                            earlyEvidence.details
                    )
                }
                AcapelaVoiceDiagnostics.VoiceEvidenceState
                    .EVIDENCE_UNAVAILABLE -> {
                    if (isFreshLogFileEarly) {
                        val fileLines = runCatching {
                            trialLogFile.readLines(StandardCharsets.ISO_8859_1)
                        }.getOrDefault(emptyList())
                        val fileEvidence = AcapelaVoiceDiagnostics
                            .evaluateVoiceEvidenceState(
                                speaker = speaker,
                                logs = fileLines,
                                expectedPid = myPid,
                                source = "logfile.txt",
                                boundariesFound = true
                            )
                        when (fileEvidence.state) {
                            AcapelaVoiceDiagnostics.VoiceEvidenceState
                                .LOADED -> {
                                managed.markVoiceLoaded()
                            }
                            AcapelaVoiceDiagnostics.VoiceEvidenceState
                                .LOAD_FAILED -> {
                                managed.markPoisoned(
                                    "الصوت $speaker لم يُحمَّل في المكتبة " +
                                        "الأصلية: ${fileEvidence.details}"
                                )
                            }
                            else -> {
                                managed.markPoisoned(
                                    "غياب دليل تحميل الصوت $speaker " +
                                        "(حدود المحاولة غير متاحة في السجل)"
                                )
                            }
                        }
                    } else {
                        managed.markPoisoned(
                            "غياب دليل تحميل الصوت $speaker " +
                                "(حدود المحاولة غير متاحة في logcat)"
                        )
                    }
                }
            }
        }

        var utf8Invoked = false
        var utf8Res: Int? = null
        var langInvoked = false
        var langRes: Int? = null
        var voiceInvoked = false
        var voiceRes: Int? = null

        if (managed.state == ExtendedServiceState.VOICE_LOADED) {
            utf8Invoked = true
            utf8Res = srv.nUtf8Mode(iso3.language, iso3.country)
            langInvoked = true
            langRes = srv.nSetLanguage(iso3.language, iso3.country, "")
            voiceInvoked = true
            voiceRes = srv.nSetCurrentVoice(speaker)
            if (voiceRes != 0) {
                managed.markPoisoned("فشل nSetCurrentVoice: كود=$voiceRes")
            }
        } else if (managed.state == ExtendedServiceState.INITIALIZED) {
            langInvoked = true
            langRes = srv.nSetLanguage(iso3.language, iso3.country, "")
            recordAcapelaBreadcrumb(
                "[$label] تم تخطي nSetCurrentVoice " +
                    "لعدم نجاح تحميل الصوت $speaker"
            )
        }

        val isVoiceReady =
            managed.state == ExtendedServiceState.VOICE_LOADED
        val pauseRes = if (isVoiceReady) {
            setExtendedProperty(
                srv,
                PUNCTUATION_PAUSE_KEY,
                PUNCTUATION_PAUSE_VALUE
            )
        } else null

        val prefRes: Int? = null
        val enumRes: Int? = null
        val loadLangRes: Int? = null
        val getLangRes: String? = null
        val closestVoiceRes: String? = null
        val isLangAvailRes: Int? = null

        val cbToPass = if (useRealCallback) synthCallback else null
        val cbName = if (cbToPass != null) {
            "AcapelaSynthesisCallback"
        } else {
            "null"
        }

        var speakInvoked = false
        var speakRes: Int? = null
        val canSpeak = managed.state == ExtendedServiceState.VOICE_LOADED &&
            voiceRes == 0

        if (canSpeak) {
            recordAcapelaBreadcrumb(
                "[$label] nSpeak(text='$probeText', cb=$cbName)"
            )
            managed.markSynthesizing()
            speakInvoked = true
            speakRes = runCatching {
                srv.nSpeak(probeText, cbToPass)
            }.getOrElse { t ->
                Log.w(TAG, "[$label] استثناء أثناء nSpeak", t)
                -2
            }
            managed.markSynthesisDone()
            recordAcapelaBreadcrumb(
                "[$label] انتهى nSpeak: كود=${speakRes ?: "غير مقيس"}"
            )
        } else {
            val skipReason = when {
                initRes != 0 -> "فشل nInit ($initRes)"
                managed.state != ExtendedServiceState.VOICE_LOADED ->
                    "الصوت $speaker غير محمل"
                voiceRes != 0 -> "فشل nSetCurrentVoice ($voiceRes)"
                else -> "الحالة: ${managed.state}"
            }
            recordAcapelaBreadcrumb(
                "[$label] تخطي nSpeak: السبب=$skipReason | " +
                    "speakInvoked=false | speakCode=null | PCM=غير مقيس"
            )
        }
        Log.i("acattsandroid-extended-native", endMarker)

        var retryVoiceRes: Int? = null
        var retrySpeakRes: Int? = null
        var retrySamples: Long? = null
        if (canSpeak && speakInvoked && (speakRes == null || speakRes < 0)) {
            var attempt = 1
            while (attempt <= 2 &&
                (retrySpeakRes == null || retrySpeakRes < 0)
            ) {
                delay(150L)
                retryVoiceRes = runCatching {
                    srv.nSetCurrentVoice(speaker)
                }.getOrElse { -99 }
                val retryStart =
                    "=== RETRY_NSPEAK_${cleanLabel}_$attempt ==="
                val retryEnd =
                    "=== END_RETRY_NSPEAK_${cleanLabel}_$attempt ==="
                Log.i("acattsandroid-extended-native", retryStart)
                retrySpeakRes = runCatching {
                    srv.nSpeak(probeText, cbToPass)
                }.getOrElse { -99 }
                Log.i("acattsandroid-extended-native", retryEnd)
                attempt++
            }
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
        val finalSpeakCode = if (speakInvoked) {
            if (retrySpeakRes == 0) 0 else speakRes
        } else {
            null
        }
        if (synthCallback.pcmStream.size() > 0) {
            val synthBytes = synthCallback.pcmStream.toByteArray()
            synchronized(audioHandler.pcmBytes) {
                if (audioHandler.pcmBytes.size() == 0) {
                    audioHandler.pcmBytes.write(synthBytes)
                }
            }
        }

        logcatReader.emitEndMarker()
        val capturedLogcat = logcatReader.stop(800L)
        val markersFound = startMarkerSeen || capturedLogcat.any {
            !it.contains("حدود المحاولة غير متاحة") &&
                !it.contains("تعذر قراءة logcat")
        }
        val isFreshLogFile = trialLogFile.exists() &&
            trialLogFile.length() > 0L &&
            trialLogFile.lastModified() >= (startedAtMs - 500L)

        val (evidenceLines, evidenceSource, boundariesFound) = when {
            markersFound && capturedLogcat.isNotEmpty() ->
                Triple(capturedLogcat, "logcat", true)
            isFreshLogFile -> {
                val fileLines = runCatching {
                    trialLogFile.readLines(StandardCharsets.ISO_8859_1)
                }.getOrDefault(emptyList())
                Triple(fileLines, "logfile.txt", true)
            }
            trialLogFile.exists() && !isFreshLogFile ->
                Triple(emptyList<String>(), "stale_logfile.txt", false)
            else ->
                Triple(capturedLogcat, "none", false)
        }

        val missingElements = mutableListOf<String>()
        if (!startMarkerSeen) missingElements.add("start_marker")
        if (!markersFound) missingElements.add("logcat_markers")
        if (!isFreshLogFile && trialLogFile.exists()) {
            missingElements.add("fresh_logfile")
        }

        val evidenceResult =
            AcapelaVoiceDiagnostics.evaluateVoiceEvidenceState(
                speaker = speaker,
                logs = evidenceLines,
                expectedPid = myPid,
                source = evidenceSource,
                boundariesFound = boundariesFound,
                missingElements = missingElements
            )
        val nativeTail = if (markersFound) capturedLogcat else evidenceLines

        val effectiveLogFile = if (isFreshLogFile) trialLogFile else null
        val logFileContent = if (isFreshLogFile && effectiveLogFile != null) {
            runCatching {
                AcapelaVoiceDiagnostics.redactSensitiveText(
                    effectiveLogFile.readText(StandardCharsets.ISO_8859_1)
                )
            }.getOrDefault("(تعذر قراءة logfile.txt)")
        } else if (trialLogFile.exists()) {
            "(ملف logfile.txt قديم من محاولة سابقة: تم استبعاده كدليل)"
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
        val ebCode = evidenceResult.ebCode ?: nativeTail
            .firstNotNullOfOrNull { line ->
                if (line.contains("eb", ignoreCase = true)) {
                    val match = Regex("eb\\s*[:=]?\\s*(-?\\d+)").find(line)
                    match?.groupValues?.get(1)?.toIntOrNull()
                } else null
            } ?: earlyEbVal
        val nlpCode = evidenceResult.nlpCode ?: nativeTail
            .firstNotNullOfOrNull { line ->
                if (line.contains("nlpInitError", ignoreCase = true)) {
                    val match = Regex(
                        "nlpInitError\\s*[:=]?\\s*(-?\\d+)"
                    ).find(line)
                    match?.groupValues?.get(1)?.toIntOrNull()
                } else null
            }
        val sNlpCode = evidenceResult.sNlpCode ?: nativeTail
            .firstNotNullOfOrNull { line ->
                if (line.contains("sNLPConfig", ignoreCase = true)) {
                    val match = Regex(
                        "sNLPConfig\\s*[:=]?\\s*(-?\\d+)"
                    ).find(line)
                    match?.groupValues?.get(1)?.toIntOrNull()
                } else null
            } ?: 0

        val loadVoiceLine = evidenceResult.loadVoiceLine ?: nativeTail
            .firstOrNull {
                it.contains("tts_function_load_voice")
            } ?: earlyLoadLine

        val cbDesc = if (useRealCallback) "SynthesisCallback" else "null"
        val proactiveAudits =
            AcapelaVoiceDiagnostics.auditProactiveContractPaths(
                voiceDir = trialDir,
                supportDir = resolvedPackage.supportDir,
                iniFile = resolvedPackage.iniFile,
                metadataFile = resolvedPackage.metadataFile,
                supportFiles = resolvedPackage.supportFiles
            )

        val firstProactiveFailure = proactiveAudits.firstOrNull { !it.passed }
        val firstProvenFailedResource = when {
            firstProactiveFailure != null ->
                "${firstProactiveFailure.role} " +
                    "(${firstProactiveFailure.expectedPath})"
            nativeTail.any {
                it.contains("error opening file", ignoreCase = true) ||
                    it.contains("error license", ignoreCase = true)
            } -> "ملف الترخيص (0x*) في مجلد nInit ($stagePath)"
            ebCode == -27 -> "مكونات NLP/ غير مكتملة في مجلد الصوت"
            ebCode == -25 -> "تهيئة BABILE_initEx (كود -25 E_BABILE_NULLARG)"
            initRes != null && initRes != 0 -> "دالة nInit (كود $initRes)"
            else -> "غير محدد"
        }
        val failedStage = when {
            firstProactiveFailure != null -> "PROACTIVE_VERIFICATION"
            nativeTail.any {
                it.contains("error opening file", ignoreCase = true) ||
                    it.contains("error license", ignoreCase = true)
            } -> "NATIVE_CHECK_LICENSE"
            ebCode == -27 -> "NATIVE_LOAD_VOICE_NLP"
            ebCode == -25 -> "NATIVE_LOAD_VOICE_INITEX"
            initRes != null && initRes != 0 -> "NATIVE_NINIT"
            speakInvoked && (finalSpeakCode == null || finalSpeakCode != 0) ->
                "NATIVE_SPEAK"
            else -> "NONE"
        }

        val sb = StringBuilder()
        sb.appendLine("=== [ملخص تجربة تحميل الصوت] ===")
        sb.appendLine("  * أول مورد فاشل مثبت: $firstProvenFailedResource")
        sb.appendLine("  * المرحلة: $failedStage")
        sb.appendLine("  * مصدر الدليل: $evidenceSource")
        sb.appendLine("  * كود eb: ${ebCode ?: "غير مقيس"}")
        sb.appendLine(
            "  * استدعاء nSpeak: $speakInvoked " +
                "(كود=${finalSpeakCode ?: "غير مقيس"})"
        )
        sb.appendLine("  * عينات PCM: ${totalSamples ?: "غير مقيس"}")
        sb.appendLine(
            "  * عملية المضيف: PID=$myPid | " +
                "HostGen=$hostGen | RunId=$runId"
        )
        sb.appendLine(
            "  * اعتراض نداءات النظام الأصلية: " +
                AcapelaVoiceDiagnostics.NATIVE_INTERCEPTION_DISCLAIMER
        )
        sb.appendLine(
            "  * حالة القارئ: بدأ=$startMarkerSeen | " +
                "انتهى=${logcatReader.endMarkerSeen} | " +
                "أسطر=${logcatReader.linesCount} | " +
                "سبب_التوقف=${logcatReader.stopReason}"
        )
        if (evidenceSource == "logfile.txt") {
            sb.appendLine(
                "  * توضيح الدليل البديل: استُخدم logfile.txt الجديد " +
                    "لأن logcat لم يوفر حدود المحاولة المطلوبة."
            )
        }
        sb.appendLine("[$label - وسائط النداءات وأكواد الإرجاع الحرفية]")
        sb.appendLine(
            "  === [التحقق الاستباقي غير المدمر لمسارات العقد المثبت] ==="
        )
        sb.appendLine(
            "  * تمييز: هذا فحص استباقي من طبقة التطبيق للتحقق من " +
                "الجاهزية، ومستقل عن آثار الفتح الأصلية للمكتبة المغلقة."
        )
        for (pa in proactiveAudits) {
            val status = pa.auditStatus
            val notesStr = if (pa.notes.isNotBlank()) " (${pa.notes})" else ""
            sb.appendLine(
                "  - [${pa.role}] الحالة=$status | " +
                    "موجود=${pa.exists} | " +
                    "قراءة=${pa.canRead} | " +
                    "مسار=${pa.expectedPath} | " +
                    "محلول=${pa.canonicalPath ?: "غير محلول"}$notesStr"
            )
        }
        sb.appendLine(
            "  === [تدقيق عقود وسائط JNI وفحص NULLARG الآمن] ==="
        )
        sb.appendLine(
            "  * توضيح: nInit يستقبل وسيطاً واحداً فقط هو مسار " +
                "المجلد (path)، وبقية الحقول تخص نداءات منفصلة."
        )
        val allAuditsPassed = audits.all { it.passed }
        for (audit in audits) {
            val status = if (audit.passed) "ناجح" else "فاشل"
            val lenDesc = audit.safeLength?.let {
                "طول_آمن=$it"
            } ?: "طول_آمن=غير مقيد"
            val notesDesc = if (audit.notes.isNotBlank()) {
                " (${audit.notes})"
            } else ""
            sb.appendLine(
                "  - [${audit.callName}] وسيط_العقد=${audit.argName} | " +
                    "نوع_متوقع=${audit.expectedType} | " +
                    "نوع_فعلي=${audit.actualType} | " +
                    "الوجود=${audit.presence} | " +
                    "$lenDesc | الحالة=$status$notesDesc"
            )
        }
        if (allAuditsPassed) {
            sb.appendLine(
                "  * تقييم العقد: كافة الوسائط الخارجية اجتازت فحص " +
                    "التحقق؛ أي خطأ أصلي (مثل NULLARG أو eb=-25) " +
                    "ينبع من داخل المكتبة الأصلية المغلقة " +
                    "ولا يمكن قياس سببه الداخلي مباشرة من خارج المكتبة."
            )
        } else {
            sb.appendLine(
                "  * تقييم العقد: وُجد وسيط خارجي لم يجتز فحص العقد!"
            )
        }
        sb.appendLine(
            "  * دلالة الأكواد: nInit=0 يعني اكتمال تفريغ التهيئة " +
                "للمكتبة ولا يعني جاهزية الصوت؛ تحميل الصوت الفعلي " +
                "يحدده كود eb في السجل (eb=-25 فشل تحميل داخلي للصوت)."
        )
        sb.appendLine(
            "  * حالة دليل الصوت: ${evidenceResult.state} | " +
                "المصدر=${evidenceResult.source} | " +
                "تفاصيل=${evidenceResult.details}"
        )
        if (evidenceResult.missingElements.isNotEmpty()) {
            sb.appendLine(
                "  * العناصر الغائبة: " +
                    evidenceResult.missingElements.joinToString()
            )
        }
        sb.appendLine(
            "  === [البند هـ] قيم معاملات nInit التي مُرِّرت فعلياً ==="
        )
        sb.appendLine("  - path = \"$stagePath\"")
        sb.appendLine("  - trailingSlash = true")
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
        val babParamLicLines = AcapelaVoiceDiagnostics.redactSensitiveLines(
            nativeTail.filter {
                it.contains("babParam.license", ignoreCase = true)
            }
        )
        val generalLicLines = AcapelaVoiceDiagnostics.redactSensitiveLines(
            nativeTail.filter {
                it.contains("error license", ignoreCase = true) ||
                    it.contains("license", ignoreCase = true)
            }
        )
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
                sb.appendLine("    $it")
            }
        }
        sb.appendLine(
            "  - أسطر license / error license (${generalLicLines.size} سطر):"
        )
        if (generalLicLines.isEmpty()) {
            sb.appendLine("    (لم يظهر أي سطر يطابق license)")
        } else {
            generalLicLines.forEach {
                sb.appendLine("    $it")
            }
        }

        sb.appendLine(
            "  === [رصد أحداث inotify / NativeFileProbe] ==="
        )
        if (probeReport.isBlank()) {
            sb.appendLine("    (لم يُفعَّل الرصد في هذه المحاولة)")
        } else {
            AcapelaVoiceDiagnostics.redactSensitiveText(probeReport)
                .lineSequence()
                .take(50)
                .forEach { line ->
                    sb.appendLine("    $line")
                }
        }

        sb.appendLine("  === [واصفات الملفات المفتوحة (/proc/self/fd)] ===")
        if (newFds.isEmpty()) {
            sb.appendLine(
                "    (لم يُرصد فتح واصفات جديدة عبر /proc/self/fd)"
            )
        } else {
            newFds.take(50).forEach {
                sb.appendLine(
                    "    ${AcapelaVoiceDiagnostics.redactSensitiveLine(it)}"
                )
            }
            if (newFds.size > 50) {
                sb.appendLine("    (+${newFds.size - 50} واصفات إضافية)")
            }
        }

        sb.appendLine("  === [آثار الفتح الأصلية من سجل المكتبة] ===")
        if (logFiles.isEmpty()) {
            sb.appendLine(
                "    (لم يُرصد فتح ملفات في سجل المكتبة)"
            )
        } else {
            logFiles.take(50).forEach {
                sb.appendLine(
                    "    ${AcapelaVoiceDiagnostics.redactSensitiveLine(it)}"
                )
            }
            if (logFiles.size > 50) {
                sb.appendLine("    (+${logFiles.size - 50} ملفات إضافية)")
            }
        }

        val sanitizedTail = AcapelaVoiceDiagnostics.redactSensitiveLines(
            nativeTail
        )
        sb.appendLine(
            "  === [سجل المكتبة الأصلية لهذه التوليفة] " +
                "(${sanitizedTail.size} سطر) ==="
        )
        sanitizedTail.forEach {
            sb.appendLine("    $it")
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
            startedAtMs = startedAtMs,
            managedService = managed,
            evidenceState = evidenceResult.state,
            evidenceSource = evidenceResult.source,
            missingEvidenceElements = evidenceResult.missingElements,
            firstFailedResource = firstProvenFailedResource
        )
        } finally {
            logcatReader.close()
            runCatching { fileProbe?.stopAndLog() }
        }
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
            startedAtMs = startedAtMs,
            evidenceState =
                AcapelaVoiceDiagnostics.VoiceEvidenceState
                    .EVIDENCE_UNAVAILABLE,
            evidenceSource = "none",
            missingEvidenceElements = listOf("before_init"),
            firstFailedResource = "فشل التحقق قبل nInit"
        )
    }

    private suspend fun configureExtendedTriple(
        voice: AcapelaVoiceManifest.VoiceInfo,
        iso3: Iso3,
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

            if (!verification.ok || verification.voiceDir == null) {
                val trial = failedTrialBeforeInit(
                    label = "AuthenticVoice (فشل التحقق قبل nInit)",
                    runId = runId,
                    startedAtMs = startedAtMs,
                    path = directoryArgument(
                        verification.voiceDir ?: voice.dir
                    ),
                    report = verification.report
                )
                recordTrialFailure(voice, runId, trial)
                return HostResult.failure(
                    "AUTHENTIC_VERIFICATION_FAILED",
                    "فشل التحقق من حزمة الصوت الأصلية: " +
                        verification.issues.joinToString { it.dependency }
                )
            }

            val verifiedDir = verification.voiceDir!!
            val verifiedIni = verification.iniFile

            // التحقق من تطابق ملف ini مع هوية المتحدث
            val iniToken = verifiedIni?.let {
                AcapelaVoiceManifest.tokenOfIdentity(it.name)
            }
            val speakerToken =
                AcapelaVoiceManifest.tokenOfIdentity(expectedSpeaker)
            if (iniToken != null && speakerToken != null &&
                iniToken != speakerToken
            ) {
                val mismatchReason = "ملف ini المتحقق " +
                    "(${verifiedIni?.name}) لا يطابق " +
                    "هوية الصوت المطلوب ($expectedSpeaker)"
                Log.e(TAG, mismatchReason)
                return HostResult.failure(
                    "IDENTITY_MISMATCH",
                    mismatchReason
                )
            }

            // تحديد سياق ملفات الدعم (supportDir / supportFiles)
            val parentContainer = verifiedDir.parentFile
                ?.takeIf { it.exists() && it.isDirectory }
            val hasHexDirect = AcapelaVoiceManifest
                .findHexRegistrationFile(verifiedDir)
                ?.parentFile?.canonicalPath == verifiedDir.canonicalPath
            val (supportDir, supportRelation) = if (hasHexDirect) {
                verifiedDir to "CO_LOCATED"
            } else if (parentContainer != null) {
                parentContainer to "PARENT_CONTAINER"
            } else {
                null to "NONE"
            }

            // ملفات الدعم المرشحة في سياق الدعم
            val supportYaml = supportDir?.let { dir ->
                File(dir, "config.yaml").takeIf { it.isFile }
                    ?: AcapelaVoiceManifest.findConfigYamlWithParam(dir)?.first
            }
            val supportHexList = supportDir?.listFiles { f ->
                f.isFile && f.name.matches(
                    AcapelaVoiceManifest.HEX_REGISTRATION_REGEX
                ) && f.length() > 0L
            }?.toList().orEmpty()

            // التحقق من ملكية ملف الدعم لنفس الحزمة وهوية الصوت
            if (supportYaml != null) {
                val yamlParam = AcapelaVoiceManifest
                    .readParamFromConfigYaml(supportYaml.parentFile)
                val parsed = yamlParam?.let {
                    AcapelaVoiceManifest.splitConfigYamlParam(it)
                }
                if (parsed != null) {
                    val (yamlVoice, _, _) = parsed
                    val yamlVoiceToken =
                        AcapelaVoiceManifest.tokenOfIdentity(yamlVoice)
                    if (yamlVoiceToken != null && speakerToken != null &&
                        yamlVoiceToken != speakerToken
                    ) {
                        val mismatchMsg = "ملف الدعم (config.yaml) في " +
                            "${supportDir?.name} يخص صوتاً آخر " +
                            "($yamlVoice) ولا يطابق الصوت " +
                            "المطلوب ($expectedSpeaker)"
                        Log.e(TAG, mismatchMsg)
                        return HostResult.failure(
                            "SUPPORT_IDENTITY_MISMATCH",
                            mismatchMsg
                        )
                    }
                }
            }

            // التحقق من ملف 0x... في سياق الدعم وربطه بهوية الصوت
            val matchedHexFile: File? = if (supportHexList.size == 1) {
                supportHexList.first()
            } else if (supportHexList.size > 1) {
                val yamlHex = supportYaml?.let {
                    AcapelaVoiceManifest.readParamFromConfigYaml(it.parentFile)
                }?.let { AcapelaVoiceManifest.splitConfigYamlParam(it) }
                    ?.third?.trim()
                if (yamlHex != null) {
                    supportHexList.firstOrNull {
                        it.name.equals(yamlHex, ignoreCase = true) ||
                            it.name.equals(
                                "0x" + yamlHex.removePrefix("0x"),
                                ignoreCase = true
                            )
                    }
                } else {
                    val mismatchMsg = "تعدد ملفات الترخيص " +
                        "(${supportHexList.size}) في سياق الدعم " +
                        "دون مرجع يحدد هوية الصوت $expectedSpeaker"
                    Log.e(TAG, mismatchMsg)
                    return HostResult.failure(
                        "SUPPORT_IDENTITY_MISMATCH",
                        mismatchMsg
                    )
                }
            } else null

            val verifiedSupportFiles = listOfNotNull(
                supportYaml,
                matchedHexFile
            ).distinct()

            // حل metadata
            val verifiedYaml = File(verifiedDir, "config.yaml")
                .takeIf { it.isFile }
                ?: supportYaml
            val hexFile = matchedHexFile
                ?: AcapelaVoiceManifest.findHexRegistrationFile(verifiedDir)
            val metadataSource = verifiedYaml ?: hexFile

            if (metadataSource == null) {
                val missingMsg = "مصدر بيانات التهيئة (config.yaml أو " +
                    "ملف الترخيص) مفقود في مجلد الحزمة المتحققة: " +
                    verifiedDir.absolutePath
                Log.e(TAG, missingMsg)
                return HostResult.failure(
                    "METADATA_SOURCE_MISSING",
                    missingMsg
                )
            }

            // تكييف محدود غير مدمر لملفات الدعم في مجلد nInit
            // وفق ما أثبته تفكيك _Z13check_licensev من اشتراط
            // وجود ملف 0x* في مسار mVoicesPath الممرر إلى nInit.
            // يحفظ الملفات الأصلية ومحتواها وعلاقاتها بلا نقل عشوائي
            // ولا إعادة كتابة.
            if (matchedHexFile != null &&
                matchedHexFile.parentFile.canonicalPath !=
                verifiedDir.canonicalPath
            ) {
                val targetHexInVoice = File(verifiedDir, matchedHexFile.name)
                if (!targetHexInVoice.exists()) {
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            targetHexInVoice.toPath(),
                            matchedHexFile.toPath()
                        )
                    }.onFailure {
                        runCatching {
                            matchedHexFile.copyTo(
                                targetHexInVoice,
                                overwrite = false
                            )
                        }
                    }
                }
            }
            if (supportYaml != null &&
                supportYaml.parentFile.canonicalPath !=
                verifiedDir.canonicalPath
            ) {
                val targetYamlInVoice = File(verifiedDir, supportYaml.name)
                if (!targetYamlInVoice.exists()) {
                    runCatching {
                        java.nio.file.Files.createSymbolicLink(
                            targetYamlInVoice.toPath(),
                            supportYaml.toPath()
                        )
                    }.onFailure {
                        runCatching {
                            supportYaml.copyTo(
                                targetYamlInVoice,
                                overwrite = false
                            )
                        }
                    }
                }
            }

            val rawParamFromVerified = verifiedYaml?.let {
                AcapelaVoiceManifest.readParamFromConfigYaml(verifiedDir)
                    ?: AcapelaVoiceManifest.readParamFromConfigYaml(
                        it.parentFile
                    )
            }
            val decodedReg = if (!rawParamFromVerified.isNullOrBlank()) {
                AcapelaMetadataCodec.resolveAcapelaParam(
                    rawParam = rawParamFromVerified,
                    context = context
                )
            } else null

            val regSourceDecision: String
            val regSourceType: String
            val regSourcePath: String?
            val regToUse: String?

            if (decodedReg != null && decodedReg.registration.isNotEmpty()) {
                regToUse = decodedReg.registration
                regSourceType = if (decodedReg.isEncrypted) {
                    "encrypted_metadata"
                } else {
                    "plain_metadata"
                }
                regSourcePath = metadataSource.name
                regSourceDecision = "metadata_decoded"
            } else if (hexFile != null) {
                val hexReg = AcapelaVoiceManifest
                    .resolveRegistration(verifiedDir)
                if (AcapelaVoiceManifest.isRegistrationMetadata(hexReg)) {
                    regToUse = hexReg
                    regSourceType = "hex_registration_file"
                    regSourcePath = hexFile.name
                    regSourceDecision = "hex_file"
                } else {
                    regToUse = null
                    regSourceType = "invalid_hex"
                    regSourcePath = hexFile.name
                    regSourceDecision = "invalid_hex"
                }
            } else {
                regToUse = null
                regSourceType = "none"
                regSourcePath = null
                regSourceDecision = "missing_metadata"
            }

            if (regToUse.isNullOrBlank()) {
                val regMissingMsg = "بيانات التسجيل غير متوفرة في " +
                    "الحزمة المتحققة: ${verifiedDir.absolutePath} " +
                    "(مصدر التسجيل=$regSourceDecision | " +
                    "نوع المصدر=$regSourceType | " +
                    "تم منع الرجوع الصامت إلى تسجيل قديم)"
                Log.e(TAG, regMissingMsg)
                return HostResult.failure(
                    "REGISTRATION_SOURCE_INVALID",
                    regMissingMsg
                )
            }

            if (decodedReg != null) {
                if (decodedReg.voiceCode.isBlank()) {
                    val msg = "فشل التحقق من metadata: voiceCode فارغ"
                    Log.e(TAG, msg)
                    return HostResult.failure(
                        "METADATA_FORMAT_INVALID",
                        msg
                    )
                }
                if (!AcapelaVoiceManifest.isRegistrationMetadata(regToUse)) {
                    val msg = "صيغة التسجيل المستخرج من metadata غير صالحة"
                    Log.e(TAG, msg)
                    return HostResult.failure(
                        "REGISTRATION_FORMAT_INVALID",
                        msg
                    )
                }
            }

            Log.i(
                TAG,
                "قرار مصدر التسجيل: نوع=$regSourceType | " +
                    "مسار=${regSourcePath ?: "غير محدد"} | " +
                    "هوية الحزمة=$expectedSpeaker | " +
                    "طول_آمن=${regToUse.length}"
            )

            // التحقق من تطابق هوية metadata
            if (decodedReg != null && decodedReg.voiceCode.isNotBlank()) {
                val metaVoiceToken = AcapelaVoiceManifest
                    .tokenOfIdentity(decodedReg.voiceCode)
                if (metaVoiceToken != null && speakerToken != null &&
                    metaVoiceToken != speakerToken
                ) {
                    val mismatchReason = "رمز الصوت في metadata " +
                        "(${decodedReg.voiceCode}) لا يطابق هوية " +
                        "الصوت المطلوب ($expectedSpeaker)"
                    Log.e(TAG, mismatchReason)
                    return HostResult.failure(
                        "IDENTITY_MISMATCH",
                        mismatchReason
                    )
                }
            }
            val yamlSpeaker = extractVoiceNameFromConfigYaml(verifiedDir)
                ?: supportYaml?.let {
                    extractVoiceNameFromConfigYaml(it.parentFile)
                }
            if (yamlSpeaker != null) {
                val yamlToken = AcapelaVoiceManifest
                    .tokenOfIdentity(yamlSpeaker)
                if (yamlToken != null && speakerToken != null &&
                    yamlToken != speakerToken
                ) {
                    val mismatchReason = "اسم الصوت في config.yaml " +
                        "($yamlSpeaker) لا يطابق هوية الصوت " +
                        "المطلوب ($expectedSpeaker)"
                    Log.e(TAG, mismatchReason)
                    return HostResult.failure(
                        "IDENTITY_MISMATCH",
                        mismatchReason
                    )
                }
            }

            val resolvedVerifiedPackage = ResolvedVerifiedPackage(
                voiceDir = verifiedDir,
                iniFile = verifiedIni,
                speaker = expectedSpeaker,
                dependencies = verification.checked,
                metadataFile = metadataSource,
                rawParam = rawParamFromVerified,
                registration = regToUse,
                regLength = regToUse.length,
                supportDir = supportDir,
                supportFiles = verifiedSupportFiles,
                supportRelation = supportRelation
            )
            lastResolvedPackage = resolvedVerifiedPackage

            val trial = executeVoiceLoadTrial(
                label = "AuthenticVoice (تحميل مباشر من الحزمة)",
                runId = runId,
                startedAtMs = startedAtMs,
                resolvedPackage = resolvedVerifiedPackage,
                iso3 = iso3,
                registration = regToUse,
                voiceMode = VOICE_MODE_HD,
                probeText = "مرحبا",
                order = AcapelaSequenceOrder.UTF8_LANG_VOICE,
                useRealCallback = true,
                allowRetry = false,
                probeFiles = true
            )

            val isSynthesizedOk = trial.speakInvoked && trial.speakCode == 0 &&
                (trial.samples ?: 0L) > 0L
            val isTrialSuccessful = trial.isSuccess && isSynthesizedOk

            lastTrialSucceeded.set(isTrialSuccessful)
            if (isTrialSuccessful) {
                activeManagedService = trial.managedService
                extendedService = trial.service
                val trialKey = "$voiceKey|$regToUse"
                consecutiveTrialFailures.remove(trialKey)
                failedTrialKey = null
                runCatching {
                    com.aymankhattab.nateq.core.audio.engine
                        .SpeechFallbackTracker.clearAcapelaFallbackState()
                    EngineStatusRegistry.noteReady(
                        AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                    )
                    EngineStatusRegistry.noteSpeakSuccess(
                        AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                    )
                }
            } else {
                trial.managedService?.markPoisoned(
                    "فشل تجربة التحميل أو النطق"
                )
                trial.managedService?.safeRelease()
                activeManagedService = null
                extendedService = null
            }
            recordTrialProgress(trial)
            if (trial.details.isNotBlank()) {
                appendAcapelaFullDiagnostic(trial.details)
            }

            val pcmBytes = trial.audioHandler.pcmBytes.toByteArray()
            if (isTrialSuccessful && trial.pcmInvoked &&
                pcmBytes.isNotEmpty()
            ) {
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
                        if (isTrialSuccessful) "نجاح حقيقي" else "فشل"
                    }"
                )
            }.trimEnd()
            Log.i(TAG, summary)
            HostLog.i(HostProtocol.ENGINE_ACAPELA, summary)
            appendAcapelaFullDiagnostic(summary)

            if (!isTrialSuccessful) {
                logVoiceDiagnostics(
                    voice,
                    "فشل تجربة runId=$runId: ${trial.method}"
                )
                val speakDesc = if (trial.speakInvoked) {
                    "nSpeak=${trial.speakCode ?: "غير مقيس"}"
                } else {
                    "nSpeak=null (لم يُستدعَ)"
                }
                val pcmDesc = if (trial.pcmInvoked) {
                    "PCM=${trial.samples ?: "غير مقيس"}"
                } else {
                    "PCM=غير مقيس"
                }
                recordTrialFailure(
                    voice = voice,
                    runId = runId,
                    trial = trial
                )
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
            activeManagedService = trial.managedService
            extendedService = srv
            extendedKey = "$voiceKey|$regToUse"
            lastTrialSucceeded.set(true)
            failedTrialKey = null
            consecutiveTrialFailures.remove("$voiceKey|$regToUse")
            runCatching {
                com.aymankhattab.nateq.core.audio.engine
                    .SpeechFallbackTracker.clearAcapelaFallbackState()
                EngineStatusRegistry.noteReady(
                    AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                )
                EngineStatusRegistry.noteSpeakSuccess(
                    AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                )
            }
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
        val norm = AcapelaVoiceDiagnostics.normalizeToUser0(dir)
        val path = norm.absolutePath
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
        if (isSpeaking ||
            activeManagedService?.state == ExtendedServiceState.SYNTHESIZING
        ) {
            runCatching { extendedService?.nStop() }
        }
    }

    override fun shutdown() {
        isSpeaking = false
        activeSink = null
        activeCancelled = null
        releaseEngine()
        warmupState.set(WarmupState.IDLE)
        lastWarmupFailure = null
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