package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.acapelagroup.android.tts.acattsandroid
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.providers.AcapelaLicense
import com.aymankhattab.nateq.core.audio.providers.AcapelaVoiceManifest
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * جلسة محرك Acapela داخل عملية :engine_host المعزولة.
 *
 * **قاعدة الصدق في النتيجة:** لا يعود [init] ولا [speak] بنجاحٍ إلا
 * بعد سماع عيّنة صوتية فعلية من المكتبة الأصلية. فحصُ القرص وحده
 * كان يُعلن نجاحاً لصوتٍ لا يُنطق، وهو جذر «استُورد بنجاح لكنه صامت».
 *
 * **قاعدة التقدّم:** [speak] يستدعي دوالاً أصلية متزامنة بالكامل
 * ([acattsandroid.nLoadVoice] و[nSpeak]) لا تبثّ إطاراتٍ في أثناء
 * الاستدعاء، فيبدو المحرك «خاملاً» لحارسَي المضيف والعميل فيُقطع
 * نطقٌ سليم. لذلك تُفتح نافذة نداءٍ أصليّ ([PcmSink.enterNativeCall])
 * يُرسَل فيها نبضة تقدّم دورياً، والنبضات مقيّدة بسقف الميزانية.
 */
internal class AcapelaHostSession(
    private val context: Context
) : HostEngineSession, acattsandroid.AudioListener {

    companion object {
        private const val TAG = "AcapelaHostSession"
        private const val DEFAULT_SAMPLE_RATE = 22050

        /** نصّ التحقق: أقصر ما يُنتج عيّنة صوتية. */
        private const val VERIFY_TEXT = "1"

        /** فاصل انتظار حلقة اكتمال النطق. */
        private const val WAIT_STEP_MS = 15L

        /** مهلة توقّف الصوت بعد آخر عيّنة (كما كان تاريخياً). */
        private const val TAIL_SILENCE_MS = 150L

        /** مهلة بدء الصوت قبل اعتبار المحرك عاجزاً عن النطق. */
        private const val START_SILENCE_MS = 2_500L

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

        /**
         * مسارات البحث عن الأصوات. يُستكمل إنشاء وتهيئة ملفات
         * .inf و .ini لضمان تعرّف مكتبة Acapela عليها.
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
            return paths.toTypedArray()
        }

        private fun matchesVoice(
            v: AcapelaVoiceManifest.VoiceInfo,
            query: String
        ): Boolean =
            v.rawSpeaker.equals(query, ignoreCase = true) ||
                v.voiceName.equals(query, ignoreCase = true) ||
                v.id.equals(query, ignoreCase = true) ||
                v.displayName.startsWith(query, ignoreCase = true)
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

    /**
     * وضع التحقق الأصلي: لا صوت يُمرَّر إلى أي أنبوب، بل يُكتفى
     * بإثبات أن المكتبة أصدرت عيّنة. [init] يعتمد عليه فلا يعلن
     * نجاحاً لصوتٍ صامت.
     */
    private val verifying = AtomicBoolean(false)
    private val verifyAudio = AtomicBoolean(false)

    private val headerWritten = AtomicBoolean(false)
    private val audioReceived = AtomicBoolean(false)
    private val speechCompleted = AtomicBoolean(false)
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
            System.loadLibrary("acattsandroid")
        } catch (e: Throwable) {
            Log.e(TAG, "فشل تحميل libacattsandroid.so: ${e.message}")
            return HostResult.failure(
                "NATIVE_LOAD_ERROR",
                "تعذر تحميل مكتبة Acapela الأصلية: ${e.message}"
            )
        }

        // إعادةُ التهيئة بلا إغلاق السابقة تُبقي محرّكاً أصلياً حيّاً
        // وصوتاً محمّلاً من مسارٍ قديم (فتضيع الذاكرة ويختلط الحالة)؛
        // فيُغلق السابق أولاً.
        releaseEngine()

        var pending: acattsandroid? = null
        val result = try {
            val inst = acattsandroid()
            pending = inst
            inst.setAudioListener(this)
            val resInit = inst.nInitCallbacks()
            if (resInit != 0) {
                Log.w(TAG, "nInitCallbacks returned $resInit")
            }
            // سجلات المكتبة الأصلية مفيدة للتشخيص فقط؛ تُترك معطّلة
            // افتراضياً كي لا تُغرق Logcat في النطق العادي.
            inst.nSetLog(diagnosticsEnabled())
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
            // تُسجّل المكتبة الأصوات في قائمة find_voices. قيمتها
            // المرجعة تعود 0 دائماً في هذا الإصدار، فلا تُستخدم
            // دليلَ عدد؛ العدد من القرص لا من قيمة غير موثوقة.
            val listed = inst.nGetVoiceList(paths)
            val voices = manifestVoices(dir, extraDirs)
            Log.i(
                TAG,
                "تهيئة Acapela: nGetVoiceList=$listed, " +
                    "أصوات القرص=${voices.size}, مسارات=${paths.size}"
            )
            when {
                voices.isEmpty() -> HostResult.failure(
                    "NO_VOICES",
                    "لا يوجد صوت Acapela صالح في ${dir.absolutePath}"
                )

                else -> verifyVoices(inst, voices) ?: run {
                    acatts = inst
                    pending = null
                    currentVoiceId = null
                    HostResult(ok = true)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ أثناء تهيئة محرك Acapela", t)
            HostResult.failure(
                "INIT_EXCEPTION",
                t.message ?: "خطأ أثناء تهيئة Acapela"
            )
        }
        // فشلُ التحقق لا يترك محرّكاً أصلياً مفتوحاً بلا مُستخدِم.
        pending?.let { shutdownQuietly(it) }
        return result
    }

    /** إغلاق محركٍ أصليّ بلا رمي: الفشل هنا لا يُفشل المسار الأصلي. */
    private fun shutdownQuietly(inst: acattsandroid) {
        runCatching { inst.nStop() }
        runCatching { inst.nShutdown() }
    }

    /** تحرير المحرك الحالي وتصفير حالته (يُستدعى قبل إعادة التهيئة). */
    private fun releaseEngine() {
        acatts?.let { shutdownQuietly(it) }
        acatts = null
        currentVoiceId = null
    }

    /**
     * تحقّق أصليّ فعلي: يُحمَّل كل صوت وتُنطق به عيّنة قصيرة.
     * فشلُ كل عمليات التحميل أو صمتُ المحرك كلّياً سببٌ معنون يُفشل
     * التهيئة، فلا يُعلَن صوتٌ صامت جاهزاً.
     */
    private fun verifyVoices(
        inst: acattsandroid,
        voices: List<AcapelaVoiceManifest.VoiceInfo>
    ): HostResult? {
        var loadedCount = 0
        var audioCount = 0
        for (voice in voices) {
            val candidates = candidateNames(voice, voice.id, voice.voiceName)
            var loadedName: String? = null
            var loadCode = -1
            for (cand in candidates) {
                val code = runCatching { inst.nLoadVoice(cand) }
                    .getOrDefault(-1)
                if (code == 0) {
                    loadedName = cand
                    loadCode = 0
                    break
                }
                loadCode = code
            }
            if (loadedName == null) {
                Log.e(
                    TAG,
                    "تحقق Acapela: فشل تحميل [${voice.id}] " +
                        "(${voice.dir.absolutePath}) آخر كود=$loadCode" +
                        " lastError=${lastErrorOf(inst)}"
                )
                continue
            }
            loadedCount++
            verifyAudio.set(false)
            verifying.set(true)
            val speakCode = try {
                inst.nSpeak(VERIFY_TEXT)
            } catch (t: Throwable) {
                Log.e(TAG, "تحقق Acapela: nSpeak انهار", t)
                -1
            } finally {
                verifying.set(false)
            }
            val heard = verifyAudio.get()
            runCatching { inst.nStop() }
            if (speakCode == 0 && heard) audioCount++
            Log.i(
                TAG,
                "تحقق Acapela [${voice.id}] باسم [$loadedName]: " +
                    "load=ok speak=$speakCode audio=$heard" +
                    " lang=${lastLanguageOf(inst)}"
            )
        }
        if (loadedCount == 0) {
            return HostResult.failure(
                "NO_VOICE_LOADED",
                "وُجدت ملفات صوت لكن المكتبة لم تحمّل أياً منها " +
                    "(${voices.size} صوتاً)"
            )
        }
        if (audioCount == 0) {
            return HostResult.failure(
                "NO_AUDIO_OUTPUT",
                "حُمّل الصوت لكن المكتبة لم تصدر أي عيّنة صوتية"
            )
        }
        return null
    }

    /**
     * سرد الأصوات المتوفرة لـ Acapela من القرص والمحرك.
     */
    fun listVoices(): List<HostVoice> {
        val dir = dataDirectory ?: return emptyList()
        val allDirs = (listOf(dir) + EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )).distinctBy { it.absolutePath }

        val manifestVoices = allDirs.flatMap {
            AcapelaVoiceManifest.listVoices(it)
        }.distinctBy {
            "${it.dir.absolutePath}|${it.id.lowercase(Locale.ROOT)}"
        }
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
        val inst = acatts ?: return HostResult.failure(
            "NOT_INITIALIZED",
            "محرك Acapela غير مهيأ"
        )
        if (cancelled.get()) {
            return HostResult.failure("CANCELLED", "أُلغي الطلب قبل بدئه")
        }

        activeSink = sink
        activeCancelled = cancelled
        isSpeaking = true

        headerWritten.set(false)
        audioReceived.set(false)
        speechCompleted.set(false)
        lastAudioTime.set(SystemClock.uptimeMillis())

        val nativeCapMs = SynthesisBudget.inactivityBudgetMs(req.text.length)
        try {
            val cleanVoiceId = req.voiceId
                .removePrefix("acapela_")
                .substringBefore('(')
                .trim()
            val cleanSpeaker = AcapelaVoiceManifest
                .cleanAcapelaSpeaker(cleanVoiceId)

            val dir = dataDirectory
            if (dir == null) {
                return HostResult.failure(
                    "DIR_NOT_FOUND",
                    "مجلد بيانات Acapela لم يُضبط"
                )
            }
            val extraDirs = EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.ACAPELA
            )
            val voices = manifestVoices(dir, extraDirs)
            // الاسمُ الخام لحقل speaker هو ما تقارنه المكتبة الأصلية
            // في nLoadVoice، فتُجرَّب أولاً قبل الاسم المطبّع.
            val voiceInfo = voices.firstOrNull { v ->
                matchesVoice(v, cleanVoiceId) ||
                    matchesVoice(v, cleanSpeaker)
            }
            val targetName = voiceInfo?.rawSpeaker
                ?: voiceInfo?.voiceName
                ?: cleanSpeaker
            if (voiceInfo != null && voiceInfo.sampleRate > 0) {
                currentSampleRate = voiceInfo.sampleRate
            }

            // الترويسة أولاً: بها تعبر نبضاتُ التقدّم أثناء النداءات
            // الأصلية المتزامنة، ولولاها عدّ العميلُ المحرك خاملاً
            // فقطع نطقاً سليماً قبل وصول أول عيّنة.
            sink.writeHeader(currentSampleRate, 1)
            headerWritten.set(true)

            if (currentVoiceId != targetName &&
                currentVoiceId != cleanSpeaker
            ) {
                val loadResult = loadVoice(
                    inst = inst,
                    sink = sink,
                    dir = dir,
                    voiceInfo = voiceInfo,
                    requestedId = cleanVoiceId,
                    cleanSpeaker = cleanSpeaker,
                    targetName = targetName,
                    nativeCapMs = nativeCapMs
                )
                if (loadResult != null) return loadResult
            }

            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }

            // حساب نسبة السرعة (100 = 1.0x) والنبرة
            val speedPercent = calculateSpeedPercent(req.rate)
            val pitchPercent = calculatePitchPercent(req.pitch)

            // ضبط سرعة النطق والشكل مباشرة على مستوى المحرك
            val rateCode = inst.nSetSpeechRate(speedPercent.toFloat())
            if (rateCode != 0) {
                Log.w(
                    TAG,
                    "nSetSpeechRate($speedPercent) فشل: $rateCode"
                )
            }
            val shapeFloat = pitchPercent.toFloat().coerceIn(70f, 130f)
            inst.nSetShape(shapeFloat)
            if (diagnosticsEnabled()) {
                Log.d(
                    TAG,
                    "speak voice=$targetName rate=${req.rate}" +
                        " speedPercent=$speedPercent" +
                        " rateCode=$rateCode"
                )
            }

            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }

            // nSpeak متزامن بالكامل: يولّد ويبثّ أثناء الاستدعاء نفسه.
            sink.enterNativeCall(nativeCapMs)
            val speakRes = try {
                var code = inst.nSpeak(req.text)
                if (code != 0 && !cancelled.get()) {
                    Log.w(
                        TAG,
                        "nSpeak raw returned $code, retrying with tags"
                    )
                    code = inst.nSpeak(
                        formatAcapelaText(req.text, req.rate, req.pitch)
                    )
                }
                code
            } finally {
                sink.exitNativeCall()
            }

            if (speakRes != 0) {
                Log.e(
                    TAG,
                    "فشل nSpeak: $speakRes" +
                        " lastError=${lastErrorOf(inst)}"
                )
                return HostResult.failure(
                    "SPEAK_ERROR",
                    "فشل استدعاء nSpeak: $speakRes"
                )
            }
            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }

            // انتظار اكتمال التخليق وبث العينات الصوتية بالكامل
            val startTime = SystemClock.uptimeMillis()
            val timeoutMs = SynthesisBudget.pieceTimeoutMs(req.text.length)
            var timedOut = false
            while (!cancelled.get() && !speechCompleted.get()) {
                val now = SystemClock.uptimeMillis()
                if (now - startTime > timeoutMs) {
                    Log.w(TAG, "تجاوز مهلة تخليق Acapela (${timeoutMs}ms)")
                    timedOut = true
                    break
                }
                if (audioReceived.get()) {
                    val speaking = runCatching {
                        inst.nIsSpeaking()
                    }.getOrDefault(0)
                    if (speaking == 0 &&
                        now - lastAudioTime.get() > TAIL_SILENCE_MS
                    ) {
                        break
                    }
                } else if (now - startTime > START_SILENCE_MS) {
                    val speaking = runCatching {
                        inst.nIsSpeaking()
                    }.getOrDefault(0)
                    if (speaking == 0) break
                }
                delay(WAIT_STEP_MS)
            }

            if (cancelled.get()) {
                return HostResult.failure("CANCELLED", "أُلغي الطلب")
            }
            if (timedOut) {
                return HostResult.failure(
                    "GENERATION_TIMEOUT",
                    "تجاوز نطق Acapela ${timeoutMs}ms بلا اكتمال"
                )
            }
            if (!audioReceived.get()) {
                Log.e(
                    TAG,
                    "Acapela لم يصدر صوتاً: voice=$targetName" +
                        " lastError=${lastErrorOf(inst)}"
                )
                return HostResult.failure(
                    "SPEAK_NO_AUDIO",
                    "لم يصدر المحرك أي صوت للصوت $targetName"
                )
            }
            return HostResult(ok = true)
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

    /**
     * تحميل الصوت المطلوب داخل نافذة نداءٍ أصليّ. يعود `null` عند
     * النجاح أو فشلاً معنوناً عند تعذّر التحميل.
     */
    private fun loadVoice(
        inst: acattsandroid,
        sink: PcmSink,
        dir: File,
        voiceInfo: AcapelaVoiceManifest.VoiceInfo?,
        requestedId: String,
        cleanSpeaker: String,
        targetName: String,
        nativeCapMs: Long
    ): HostResult? {
        val candidates = candidateNames(voiceInfo, requestedId, cleanSpeaker)
        var loadRes = -1
        var loadedName: String? = null
        sink.enterNativeCall(nativeCapMs)
        try {
            for (cand in candidates) {
                loadRes = inst.nLoadVoice(cand)
                if (loadRes == 0) {
                    loadedName = cand
                    break
                }
            }
            if (loadRes != 0) {
                // المكتبة لم تسجّل الأصوات بعد (أو نُسيت قائمتها):
                // أعد مسح المسارات ثم أعد المحاولة.
                val extraDirs = EmbeddedStorage.allSearchDirs(
                    context,
                    EmbeddedStorage.Engine.ACAPELA
                )
                val paths = collectVoiceSearchPaths(dir, extraDirs)
                val count = inst.nGetVoiceList(paths)
                Log.i(
                    TAG,
                    "إعادة فحص أصوات Acapela: عُثر على $count في " +
                        "${paths.size} مسار"
                )
                for (cand in candidates) {
                    loadRes = inst.nLoadVoice(cand)
                    if (loadRes == 0) {
                        loadedName = cand
                        break
                    }
                }
            }
        } finally {
            sink.exitNativeCall()
        }

        if (loadRes == 0) {
            currentVoiceId = loadedName ?: targetName
            Log.i(TAG, "تم تحميل صوت Acapela [$currentVoiceId] بنجاح")
            return null
        }

        Log.e(
            TAG,
            "فشل تحميل صوت Acapela ($targetName): كود $loadRes, " +
                "مرشحات=$candidates, lastError=${lastErrorOf(inst)}, " +
                "lang=${lastLanguageOf(inst)}, ملفات=${dirFiles(dir)}"
        )
        return HostResult.failure(
            "VOICE_LOAD_FAILED",
            "تعذر تحميل الصوت: $targetName (كود $loadRes)"
        )
    }

    override fun onAudioAvailable(samples: ShortArray, length: Int) {
        if (verifying.get()) {
            if (length > 0) verifyAudio.set(true)
            return
        }
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
        isSpeaking = false
        activeSink = null
        activeCancelled = null
        releaseEngine()
        dataDirectory = null
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
     * أسماء مرشَّحة للتحميل: الاسم الخام أولاً، ثم أسماء التهيئة
     * والملفات. لا تطبيع للاسم الخام قبل تمريره للمكتبة.
     */
    private fun candidateNames(
        voice: AcapelaVoiceManifest.VoiceInfo?,
        requestedId: String,
        cleanSpeaker: String
    ): List<String> {
        val names = LinkedHashSet<String>()
        if (voice != null) {
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
        }
        names.add(requestedId)
        names.add(cleanSpeaker)
        names.add(cleanSpeaker.lowercase(Locale.ROOT))
        names.add(cleanSpeaker.uppercase(Locale.ROOT))
        names.add(
            cleanSpeaker.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT)
                else it.toString()
            }
        )
        return names.filter { it.isNotBlank() }.distinct()
    }

    private fun lastErrorOf(inst: acattsandroid): Int =
        runCatching { inst.nGetLastError() }.getOrDefault(-1)

    private fun lastLanguageOf(inst: acattsandroid): String =
        runCatching { inst.nGetLanguage() }.getOrDefault("")

    private fun dirFiles(dir: File): List<String> =
        dir.walkTopDown().maxDepth(2)
            .filter { it.isFile }
            .map { "${it.name} (${it.length()}B)" }
            .toList()
}
