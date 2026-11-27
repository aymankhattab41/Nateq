package com.aymankhattab.nateq.core.audio.enginehost

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.common.VisibleEngines
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ع٢٩-5: عميل المضيف المعزول في العملية الأمامية.
 *
 * كل نداء أصلي (init / listVoices / synthesize) يمرّ عبر ربطٍ طويل
 * العمر إلى [EmbeddedEngineHostService] في `:engine_host`، والصوت
 * يعود في أنبوب `ParcelFileDescriptor` لا عبر الـBinder (سقف ~1MB).
 *
 * **إعادة المحاولة مرة واحدة فقط:** عند `DeadObjectException` أو
 * موت الربط يُعاد بناء الربط ثم يُعاد النداء **مرة واحدة**؛ إن مات
 * ثانيةً يعود فشل مُعلن (`HOST_DIED`) ولا يُجرَّب إلى ما لا نهاية
 * (حلقة إعادة تشغيل تقتل العملية الأمامية).
 *
 * **المهل مرتّبة لا تُكسر:** قراءة الأنبوب لها مهلة
 * [SynthesisBudget.requestTimeoutMs] وهي أكبر دائماً من ميزانية
 * التوليد الداخلية [SynthesisBudget.pieceTimeoutMs] في المضيف، فلا
 * يُغلق العميل أنبوباً وهو يكتب.
 */
object EngineHostClient {

    private const val TAG = "EngineHostClient"

    /** دورة فحص خيط قراءة الأنبوب (يستجيب لانتهائه فوراً). */
    private const val POLL_INTERVAL_MS = 50L

    private val connections = ConcurrentHashMap<String, HostConnection>()

    /** تذكر نجاح التهيئة لكل محرك (ع٣٠-9) لتفادي نداءات Binder المكررة. */
    private val initializedEngines = ConcurrentHashMap<String, String>()
    private val engineInitMutexes = ConcurrentHashMap<String, Mutex>()

    /** آخر مسار بيانات تم تهيئة المحرك به لإعادة استخدامه عند إعادة الربط. */
    private val lastDataDirs = ConcurrentHashMap<String, String>()

    /**
     * عدّاد النداءات النشطة لكل محرك مضيف (synthesize/init/listVoices/…).
     * مع علامة تحرير مؤجّل: يمنع إغلاق/تحرير الاتصال أو المضيف المشترك
     * أثناء استخدامه، ويُنفَّذ التحرير فور خلوّ العداد (بند 1-8/أ).
     */
    private val activeCalls = ConcurrentHashMap<String, AtomicInteger>()
    private val pendingRelease = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var lastCallSent: String = "لا يوجد نداء مسجّل"
        private set

    @Volatile
    var lastCallSentTimeMs: Long = 0L
        private set

    @Volatile
    var lastFrameReceived: String = "لم يُستلم أي إطار عبر الأنبوب"
        private set

    @Volatile
    var framesReceivedCount: Int = 0
        private set

    @Volatile
    var hostConnectedAtUptimeMs: Long = 0L
        internal set

    @Volatile
    var hostConnectedAtWallMs: Long = 0L
        internal set

    @Volatile
    var lastKnownHostPid: Int = -1
        internal set

    /** التقاط PID المضيف للتشخيص على خلفية كي لا يحجب الخيط الرئيسي/الحرج. */
    internal fun captureHostPidAsync(context: Context) {
        val ctx = context.applicationContext ?: context
        background.execute {
            runCatching {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                    as? ActivityManager
                val hostProc = am?.runningAppProcesses?.firstOrNull {
                    it.processName.endsWith(":engine_host")
                }
                if (hostProc != null) {
                    lastKnownHostPid = hostProc.pid
                }
            }
        }
    }

    private fun recordCallSent(call: String) {
        lastCallSentTimeMs = System.currentTimeMillis()
        lastCallSent = call
    }

    private val rebindScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * مهلة المعاينة المدمجة (ع٢٩-10 بند 2) للواجهة العامة خارج
     * الوحدة: ميزانية الطلب + هامش تفريغ AudioTrack، كلها من
     * [SynthesisBudget] — لا معادلة مهلة مستقلّة خارجه.
     */
    fun previewTimeoutMs(text: String): Long =
        SynthesisBudget.previewTimeoutMs(text.length)

    /** أوامر الإلغاء والتحرير تعمل خارج أي خيط مُستدعي. */
    private val background: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "engine-host-client")
        }

    // ----------------------------------------------------------------
    // الواجهة العامة
    // ----------------------------------------------------------------

    /**
     * تهيئة محرك في المضيف. [force] = تجاوز علامة «انهار في آخر
     * تشغيل» (زر إعادة المحاولة) — الافتراضي `false` يرفض النداء
     * برمز `CRASHED_LAST_RUN` فلا يتكرر الانهيار بصمت.
     */
    suspend fun init(
        context: Context,
        engineId: String,
        dataDir: String,
        force: Boolean = false
    ): HostResult {
        val id = HostProtocol.normalizeEngineId(engineId)
        val mutex = engineInitMutexes.getOrPut(id) { Mutex() }
        return mutex.withLock {
            setContext(context)
            lastDataDirs[id] = dataDir
            recordCallSent(
                "init(engineId=$id, dataDir=$dataDir, force=$force)"
            )
            if (!force && initializedEngines[id] == dataDir) {
                return@withLock HostResult(ok = true)
            }
            val call = callHost(context, id) { svc ->
                val decoded = HostProtocol.decodeResult(
                    svc.init(id, dataDir, force)
                )
                if (!decoded.ok) {
                    throw HostCallException(
                        decoded.code ?: "UNKNOWN",
                        decoded.message ?: ""
                    )
                }
            }
            val res = call.toHostResult()
            if (res.ok) {
                initializedEngines[id] = dataDir
            } else {
                initializedEngines.remove(id)
            }
            res
        }
    }

    /** قائمة أصوات المضيف؛ فشلها سببٌ معنون لا قائمةٌ صامتة. */
    suspend fun listVoices(
        context: Context,
        engineId: String
    ): HostVoicesResult {
        val id = HostProtocol.normalizeEngineId(engineId)
        recordCallSent("listVoices(engineId=$id)")
        val call = callHost(context, id) { svc ->
            val decoded = HostProtocol.decodeVoicesResponse(
                svc.listVoices(id, null)
            )
            decoded.failure?.let { failure ->
                throw HostCallException(
                    failure.code ?: "UNKNOWN",
                    failure.message ?: ""
                )
            }
            decoded.voices
        }
        return when (call) {
            is HostCall.Success ->
                HostVoicesResult(voices = call.value)
            is HostCall.Failure ->
                HostVoicesResult(
                    failure = HostResult.failure(call.code, call.message)
                )
        }
    }

    /**
     * استرجاع آخر سجلات المضيف للمحرك المحدد (ع٣٠-2).
     */
    suspend fun getLog(
        context: Context,
        engineId: String,
        maxLines: Int = 200
    ): String {
        val id = HostProtocol.normalizeEngineId(engineId)
        recordCallSent("getLog(engineId=$id, maxLines=$maxLines)")
        val call = callHost(context, id) { svc ->
            svc.getLog(id, maxLines) ?: ""
        }
        return when (call) {
            is HostCall.Success -> call.value
            is HostCall.Failure ->
                "تعذّر جلب سجل المضيف [${call.code}]: ${call.message}"
        }
    }

    /**
     * استعلام عن حالة الجاهزية الدافئة للمحرك عبر IPC من المضيف.
     */
    suspend fun getWarmStatus(
        context: Context,
        engineId: String
    ): HostWarmStatusRegistry.HostWarmStatus? {
        val id = HostProtocol.normalizeEngineId(engineId)
        val call = callHost(context, id) { svc ->
            svc.getWarmStatus(id)
        }
        val res = call.toHostResult()
        if (res.ok && !res.payload.isNullOrBlank()) {
            return HostWarmStatusRegistry.fromJson(res.payload)
        }
        return HostWarmStatusRegistry.readWarmStatus(context, id)
    }

    /**
     * نطق نصّ كامل إلى [onFormatInfo]/[onAudioChunk] عبر أنبوب
     * المضيف. تعود بفشلٍ برمز معنون إن مات المضيف أو انتهت المهلة
     * أو أُلغي الطلب (إلغاء كوروتين المُستدعي يصل المضيف في الحال —
     * رمز [CancellationException] يمرّ ولا يبتلع).
     */
    suspend fun synthesize(
        context: Context,
        engineId: String,
        voiceId: String,
        text: String,
        rate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit
    ): HostResult {
        val id = HostProtocol.normalizeEngineId(engineId)
        // البند ١٥: نصّ قارئ الشاشة لا يُكتب في السجلات ولا في تقارير
        // الانهيار (كان يُحفظ في lastCallSent ثم يُعرض للمستخدم)؛ نكتفي
        // بطول النص وبصمته المختصرة.
        recordCallSent(
            "synthesize(engineId=$id, voiceId=$voiceId, " +
                "text=<${SpeechTextRedactor.describe(text)}>, " +
                "rate=$rate, pitch=$pitch, volume=$volume)"
        )
        captureHostPidAsync(context)
        if (text.isEmpty()) {
            return HostResult.failure("EMPTY_TEXT", "نصّ فارغ")
        }
        val call = callHost(
            context,
            id,
            bindTimeoutMs = HostConnection.SPEECH_BIND_TIMEOUT_MS
        ) { svc ->
            val (readFd, writeFd) = ParcelFileDescriptor.createPipe()
            val raw = try {
                svc.synthesize(
                    id,
                    voiceId,
                    text,
                    rate,
                    pitch,
                    volume,
                    writeFd
                )
            } finally {
                // نسختنا من طرف الكتابة انتهت بنداء الـBinder؛ المضيف
                // يحمل نسخته، ولا يرى القارئ EOF قبل إغلاقها.
                runCatching { writeFd.close() }
            }
            val decoded = HostProtocol.decodeResult(raw)
            if (!decoded.ok) {
                runCatching { readFd.close() }
                throw HostCallException(
                    decoded.code ?: "HOST_ERROR",
                    decoded.message ?: ""
                )
            }
            val requestId = HostProtocol.decodeRequestId(raw)
            try {
                val read = readFrames(
                    readFd,
                    id,
                    onFormatInfo,
                    onAudioChunk
                )
                if (!read.ok) {
                    throw HostCallException(
                        read.code ?: "PIPE_ERROR",
                        read.message ?: ""
                    )
                }
            } finally {
                // يوقف المضيف عند المهلة/الإلغاء؛ وبعد النجاح لا يجد
                // الطلب فعلاً فيكون نداءً صامتاً بلا أثر.
                cancelRequest(id, svc, requestId)
            }
        }
        return call.toHostResult()
    }

    /**
     * إلغاء طلب جارٍ في المضيف.
     */
    fun cancel(context: Context, engineId: String, requestId: Long) {
        val id = HostProtocol.normalizeEngineId(engineId)
        val svc = connections[id]?.current() ?: return
        cancelRequest(id, svc, requestId)
    }

    fun cancel(engineId: String, requestId: Long) {
        val id = HostProtocol.normalizeEngineId(engineId)
        val svc = connections[id]?.current() ?: return
        cancelRequest(id, svc, requestId)
    }

    /**
     * تحرير موارد محرك مضيف: يؤجّل التحرير إن كان قيد استخدام نشط
     * (synthesize/init/listVoices/…) ويُنفَّذ فور خلوّ النداءات، منعاً
     * لإغلاق الاتصال أو المضيف المشترك أثناء الاستخدام (بند 1-8/أ).
     * بلا انتظار (لا يعلّق خيط المُستدعي — يُنفَّذ خلفياً عند الإمكان).
     */
    fun release(context: Context, engineId: String) {
        val id = HostProtocol.normalizeEngineId(engineId)
        requestRelease(id)
    }

    /** يطلب تحرير محرك مضيف مع احترام النداءات النشطة الجارية. */
    private fun requestRelease(engineId: String) {
        val id = HostProtocol.normalizeEngineId(engineId)
        val active = activeCalls[id]?.get() ?: 0
        if (active > 0) {
            pendingRelease.add(id)
            Log.i(TAG, "release deferred: $id has $active active call(s)")
            return
        }
        pendingRelease.remove(id)
        performRelease(id)
    }

    private fun performRelease(engineId: String) {
        val id = HostProtocol.normalizeEngineId(engineId)
        initializedEngines.remove(id)
        lastDataDirs.remove(id)
        synchronized(recoveryJob) {
            recoveryJob.getAndSet(null)?.cancel()
        }
        lastRecoveryFailed = false
        val connection = connections.remove(id) ?: return
        background.execute {
            runCatching { connection.current()?.shutdown(id) }
            connection.close()
        }
        activeCalls.remove(id)
    }

    private fun beginActiveCall(engineId: String) {
        val id = HostProtocol.normalizeEngineId(engineId)
        pendingRelease.remove(id)
        activeCalls.computeIfAbsent(id) { AtomicInteger(0) }
            .incrementAndGet()
    }

    private fun endActiveCall(engineId: String) {
        val id = HostProtocol.normalizeEngineId(engineId)
        val counter = activeCalls[id] ?: return
        val remaining = counter.decrementAndGet()
        if (remaining <= 0) {
            activeCalls.remove(id, counter)
            if (pendingRelease.remove(id)) {
                performRelease(id)
            }
        }
    }

    /** هل ثمة ربط حيّ بهذا المحرك الآن؟ (فحص الاختبارات فقط). */
    @VisibleForTesting
    fun isBound(engineId: String): Boolean {
        val id = HostProtocol.normalizeEngineId(engineId)
        return connections[id]?.current() != null
    }

    /** فحص حالة الذاكرة المؤقتة للتهيئة (للاختبارات فقط). */
    @VisibleForTesting
    fun isEngineInitialized(engineId: String, dataDir: String): Boolean {
        val id = HostProtocol.normalizeEngineId(engineId)
        return initializedEngines[id] == dataDir
    }

    @VisibleForTesting
    fun clearInitialized(engineId: String? = null) {
        if (engineId != null) {
            val id = HostProtocol.normalizeEngineId(engineId)
            initializedEngines.remove(id)
        } else {
            initializedEngines.clear()
        }
    }

    val hostGeneration = AtomicLong(1L)

    private val recoveryJob = AtomicReference<kotlinx.coroutines.Job?>(null)

    @Volatile
    var lastRecoveryFailed: Boolean = false
        private set

    internal fun onHostDied(engineId: String) {
        notifyHostDied(engineId, hostGeneration.get())
    }

    internal fun notifyHostDied(engineId: String, generation: Long) {
        val id = HostProtocol.normalizeEngineId(engineId)
        initializedEngines.remove(id)
        appContext?.let { ctx ->
            HostWarmStatusRegistry.clearWarmStatus(ctx, id)
        }

        // دمج البلاغات وتجاهل الجيل القديم
        val currentGen = hostGeneration.get()
        if (generation < currentGen) {
            Log.d(
                TAG,
                "تجاهل بلاغ موت قديم لمضيف $id (جيل=$generation < $currentGen)"
            )
            return
        }

        // تقدم جيل الاتصال لمنع تكرار معالجة نفس الانهيار
        val nextGen = hostGeneration.incrementAndGet()
        Log.w(
            TAG,
            "مات مضيف $id (جيل سابق=$generation، جيل جديد=$nextGen) — " +
                "تنسيق التعافي"
        )

        val ctx = appContext ?: return
        synchronized(recoveryJob) {
            val current = recoveryJob.get()
            if (current != null && current.isActive) {
                Log.d(TAG, "عملية تعافٍ جارية بالفعل لمضيف :engine_host")
                return
            }
            val job = rebindScope.launch {
                performHostRecovery(ctx, id, nextGen)
            }
            recoveryJob.set(job)
        }
    }

    private suspend fun performHostRecovery(
        context: Context,
        triggerEngineId: String,
        recoveryGen: Long
    ) {
        lastRecoveryFailed = false
        val backoffs = longArrayOf(500L, 1000L, 2000L)
        for ((attempt, delayMs) in backoffs.withIndex()) {
            delay(delayMs)
            if (recoveryGen != hostGeneration.get()) {
                Log.d(TAG, "إلغاء محاولة التعافي لتغير جيل المضيف")
                return
            }
            Log.i(
                TAG,
                "محاولة تعافي مضيف :engine_host (${attempt + 1}/3) " +
                    "بعد ${delayMs}ms"
            )
            val conn = connection(context, triggerEngineId)
            conn.invalidate()
            val svc = conn.service()
            if (svc != null) {
                Log.i(
                    TAG,
                    "نجحت استعادة المضيف في المحاولة ${attempt + 1}"
                )
                for ((engId, dataDir) in lastDataDirs) {
                    if (dataDir.isNotBlank()) {
                        val initRes = init(
                            context, engId, dataDir, force = true
                        )
                        Log.i(
                            TAG,
                            "إعادة تحميل محرك $engId بعد التعافي: " +
                                "ok=${initRes.ok}"
                        )
                    }
                }
                lastRecoveryFailed = false
                synchronized(recoveryJob) { recoveryJob.set(null) }
                return
            } else {
                Log.w(
                    TAG,
                    "فشلت محاولة تعافي المضيف (${attempt + 1}/3)"
                )
            }
        }
        Log.e(
            TAG,
            "استنفاد محاولات إعادة ربط المضيف (3/3)؛ توقف التعافي التلقائي"
        )
        lastRecoveryFailed = true
        synchronized(recoveryJob) { recoveryJob.set(null) }
    }

    /**
     * إعادة ربط المضيف بتأخير تراجعي [500ms, 1s, 2s] حتى 3 محاولات.
     * توجَّه لمنسق التعافي الواحد وتمنع الحلقات المتداخلة.
     */
    fun scheduleRebind(context: Context, engineId: String) {
        setContext(context)
        notifyHostDied(engineId, hostGeneration.get())
    }

    /**
     * تغيّر محتوى التخزين المدمج (تنزيل صوت/حذفه/استيراد/نقل): يُبطل
     * ذاكرة «سبق تهيئة المضيف بهذا المسار» فيُعاد نداء `init` إلى
     * المضيف عند أول استعمال تالٍ. والمضيف نفسه يعيد بناء المحرّك إن
     * تغيّرت الترويسات (انظر `shouldRebuildEngine` في
     * `VocalizerHostSession`) فلا تبقى قائمة الأصوات قديمة.
     */
    fun onStorageChanged() {
        initializedEngines.clear()
        appContext?.let { ctx ->
            HostWarmStatusRegistry.clearAll(ctx)
        }
    }

    // ----------------------------------------------------------------
    // النداء مع إعادة المحاولة الواحدة
    // ----------------------------------------------------------------

    /**
     * نداء واحد إلى المضيف، يُعاد **مرة واحدة فقط** عند موت الربط
     * أو `DeadObjectException`؛ أي فشل مُعلن آخر (علامة انهيار،
     * نموذج ناقص، مهلة) يعود كما هو دون إعادة.
     */
    private suspend fun <T> callHost(
        context: Context,
        engineId: String,
        bindTimeoutMs: Long = HostConnection.BIND_TIMEOUT_MS,
        body: suspend (IEmbeddedEngineHost) -> T
    ): HostCall<T> {
        val id = HostProtocol.normalizeEngineId(engineId)
        beginActiveCall(id)
        try {
            var attempt = 0
            var last = HostCall.Failure(
                "HOST_UNAVAILABLE",
                "تعذّر الوصول إلى مضيف $id"
            )
            val fastSpeechBind =
                bindTimeoutMs <= HostConnection.SPEECH_BIND_TIMEOUT_MS
            val maxAttempts = if (fastSpeechBind) 1 else 2
            while (attempt < maxAttempts) {
                attempt++
                val svc = connection(context, id).service(bindTimeoutMs)
                if (svc == null) {
                    last = HostCall.Failure(
                        "HOST_UNAVAILABLE",
                        "تعذّر ربط مضيف $id"
                    )
                    if (fastSpeechBind) break
                    continue
                }
                val result: HostCall<T> = try {
                    HostCall.Success(body(svc))
                } catch (t: HostCallException) {
                    HostCall.Failure(t.code, t.message)
                } catch (t: DeadObjectException) {
                    Log.e(TAG, "موت المضيف أثناء النداء: ${t.message}")
                    HostCall.Failure("HOST_DIED", "انهار مضيف $id")
                } catch (t: CancellationException) {
                    throw t
                } catch (t: RemoteException) {
                    HostCall.Failure("HOST_ERROR", t.message ?: "خطأ بَرْج")
                } catch (t: Throwable) {
                    Log.e(TAG, "خطأ غير متوقّع من المضيف: ${t.message}", t)
                    HostCall.Failure(
                        "HOST_ERROR",
                        t.message ?: "خطأ غير متوقّع"
                    )
                }
                when (result) {
                    is HostCall.Success -> return result
                    is HostCall.Failure -> last = result
                }
                val died = last.code == "HOST_DIED" ||
                    last.code == "HOST_UNAVAILABLE"
                if (!died || attempt >= 2) {
                    if (died) {
                        initializedEngines.remove(id)
                    }
                    return last
                }
                // ع٢٩-5: إعادة محاولة واحدة فقط — أعد البناء ثم أعد النداء.
                initializedEngines.remove(id)
                connection(context, id).invalidate()
            }
            return last
        } finally {
            endActiveCall(id)
        }
    }

    private fun cancelRequest(
        engineId: String,
        svc: IEmbeddedEngineHost,
        requestId: Long
    ) {
        val id = HostProtocol.normalizeEngineId(engineId)
        if (requestId <= 0) return
        background.execute {
            runCatching { svc.cancel(requestId) }
                .onFailure {
                    Log.w(TAG, "إلغاء $id/$requestId: ${it.message}")
                }
        }
    }

    // ----------------------------------------------------------------
    // قراءة الأنبوب
    // ----------------------------------------------------------------

    /**
     * قراءة إطارات الأنبوب على خيطٍ منفصل حتى إطار النهاية أو EOF،
     * مع مهلة [deadlineAt]. لا نُغلق الـFD من خيط الانتظار وقت
     * المهلة (سباق close/read يعلّق القراءة)؛ نكتفي بإرجاع الفشل
     * فتُوقف النداء التاليُ في `finally` طلبَ المضيف.
     */
    private suspend fun readFrames(
        pfd: ParcelFileDescriptor,
        engineId: String,
        onFormatInfo: (Int, Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit
    ): HostResult {
        val captured = AtomicReference<HostResult?>(null)
        val lastProgressAt = AtomicLong(SystemClock.uptimeMillis())
        val lastPieceDurationMs = AtomicLong(0L)
        val sampleRateRef = AtomicInteger(22050)
        val channelsRef = AtomicInteger(1)
        lastFrameReceived = "لم يُستلم أي إطار عبر الأنبوب (EOF فوري)"
        framesReceivedCount = 0

        val reader = Thread({
            try {
                DataInputStream(
                    BufferedInputStream(FileInputStream(pfd.fileDescriptor))
                ).use { input ->
                    while (true) {
                        val frame = HostProtocol.readFrame(input)
                            ?: break
                        framesReceivedCount++
                        when (frame) {
                            is HostProtocol.HostFrame.Format -> {
                                lastFrameReceived =
                                    "Format(sr=${frame.sampleRate}, " +
                                        "ch=${frame.channels})"
                                lastProgressAt.set(SystemClock.uptimeMillis())
                                sampleRateRef.set(frame.sampleRate)
                                channelsRef.set(frame.channels)
                                onFormatInfo(
                                    frame.sampleRate,
                                    frame.channels
                                )
                            }
                            is HostProtocol.HostFrame.Pcm -> {
                                lastFrameReceived =
                                    "Pcm(size=${frame.bytes.size}B)"
                                val sr = sampleRateRef.get()
                                val ch = channelsRef.get()
                                if (sr > 0 && frame.bytes.isNotEmpty()) {
                                    val safeCh = maxOf(1, ch).toLong()
                                    val dur = (frame.bytes.size.toLong() *
                                        1000L) / (sr.toLong() * 2L * safeCh)
                                    lastPieceDurationMs.set(dur)
                                }
                                lastProgressAt.set(SystemClock.uptimeMillis())
                                onAudioChunk(
                                    frame.bytes,
                                    frame.bytes.size
                                )
                            }
                            is HostProtocol.HostFrame.Ping -> {
                                lastFrameReceived = "Ping (نبضة تقدّم)"
                                // نبضة تقدّم أثناء عملٍ أصليّ متزامن:
                                // لا صوت فيها، لكنها تمنع حارس الخمول
                                // من قطع نطقٍ سليم لا يبثّ إطارات.
                                lastProgressAt.set(SystemClock.uptimeMillis())
                            }
                            is HostProtocol.HostFrame.End -> {
                                lastFrameReceived =
                                    "End(status=${frame.status}, " +
                                        "code=${frame.code}, " +
                                        "msg=${frame.message})"
                                lastProgressAt.set(SystemClock.uptimeMillis())
                                captured.set(frame.toResult())
                                break
                            }
                        }
                    }
                }
                if (captured.get() == null) {
                    captured.set(
                        HostResult.failure(
                            "HOST_EOF",
                            "انتهى الأنبوب قبل إطار النهاية"
                        )
                    )
                }
            } catch (t: Throwable) {
                captured.set(
                    HostResult.failure(
                        "PIPE_ERROR",
                        t.message ?: "خطأ في أنبوب الصوت"
                    )
                )
            } finally {
                runCatching { pfd.close() }
            }
        }, "engine-host-read")
        reader.isDaemon = true
        reader.start()

        val startAt = SystemClock.uptimeMillis()
        var timedOut = false
        while (reader.isAlive) {
            delay(POLL_INTERVAL_MS)
            val now = SystemClock.uptimeMillis()
            val idleElapsed = now - lastProgressAt.get()
            val currentIdleLimit = SynthesisBudget.idleTimeoutMs(
                lastPieceDurationMs.get()
            )
            if (idleElapsed > currentIdleLimit) {
                HostLog.w(
                    engineId,
                    "خمول في استلام صوت المضيف: ${idleElapsed}ms > " +
                        "${currentIdleLimit}ms"
                )
                timedOut = true
                runCatching { pfd.close() }
                break
            }
            val totalElapsed = now - startAt
            if (totalElapsed > SynthesisBudget.ABSOLUTE_REQUEST_TIMEOUT_MS) {
                HostLog.w(
                    engineId,
                    "تجاوز السقف المطلق للتخليق: ${totalElapsed}ms"
                )
                timedOut = true
                runCatching { pfd.close() }
                break
            }
        }
        if (timedOut) {
            return HostResult.failure(
                "REQUEST_TIMEOUT",
                "انتهت مهلة قراءة صوت المضيف (غياب التقدّم)"
            )
        }
        return captured.get() ?: HostResult.failure(
            "HOST_EOF",
            "انتهى الأنبوب قبل أن يسجّل القارئ نتيجة"
        )
    }

    private fun HostProtocol.HostFrame.End.toResult(): HostResult {
        return if (status == HostProtocol.STATUS_OK) {
            HostResult(ok = true)
        } else {
            HostResult.failure(
                code.ifBlank { "ENGINE_ERROR" },
                message
            )
        }
    }

    /**
     * ربط مضيف المحرك مبكراً في الخلفية (تدفئة/بدء خدمة) بلا حجب للنطق.
     * يعيد استخدام الربط الحي إن وُجد، ويترك التعافي/الربط الفعلي بالخلفية.
     */
    fun prewarmBindingAsync(context: Context, engineId: String) {
        val id = HostProtocol.normalizeEngineId(engineId)
        val app = context.applicationContext ?: context
        setContext(app)
        if (!VisibleEngines.isVisible(id)) return
        if (connection(app, id).current() != null) return
        background.execute {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    connection(app, id).service(
                        HostConnection.BIND_TIMEOUT_MS
                    )
                }
            }.onFailure { t ->
                Log.w(TAG, "فشل الربط المبكر لمضيف $id: ${t.message}")
            }
        }
    }

    /** ربط كل مضيفات المحركات المدمجة مبكراً في الخلفية. */
    fun prewarmAllEmbeddedBindingsAsync(context: Context) {
        val app = context.applicationContext ?: context
        listOf(
            HostProtocol.ENGINE_ACAPELA,
            HostProtocol.ENGINE_VOCALIZER,
            HostProtocol.ENGINE_MICROSOFT
        ).forEach { prewarmBindingAsync(app, it) }
    }

    internal fun connection(
        context: Context,
        engineId: String
    ): HostConnection {
        val id = HostProtocol.normalizeEngineId(engineId)
        connections[id]?.let { return it }
        val created = HostConnection(context.applicationContext, id)
        return connections.putIfAbsent(id, created) ?: created
    }
}

/**
 * ربط محرك واحد بـ`:engine_host`: بناءٌ متزامن (Mutex) فلا يتداخل
 * بناءان على الواجهة، واستقبال `onBindingDied`/موت الـBinder بإعادة
 * بناء الربط عند النداء التالي.
 */
internal class HostConnection(
    context: Context,
    private val engineId: String
) {

    companion object {
        private const val TAG = "EngineHostConn"

        /** مهلة بناء الربط للتدفئة/الخلفية (الخدمة في نفس الجهاز). */
        internal const val BIND_TIMEOUT_MS = 10_000L

        /** مهلة ربط سريعة للنطق الحقيقي: فشل سريع ثم تراجع للمحرك الاحتياطي. */
        internal const val SPEECH_BIND_TIMEOUT_MS = 1_500L
    }

    private val app = context.applicationContext
    private val bindMutex = Mutex()

    @Volatile
    private var service: IEmbeddedEngineHost? = null

    @Volatile
    private var bound = false

    @Volatile
    var connectionGeneration: Long = 0L
        private set

    /** إشارة اكتمال آخر بناء — تتبدّل في كل محاولة بناء. */
    @Volatile
    private var signal = CompletableDeferred<IEmbeddedEngineHost>()

    @Volatile
    private var deathBinder: IBinder? = null

    @Volatile
    private var deathRecipient: IBinder.DeathRecipient? = null

    private val callback = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            val thisGen = connectionGeneration
            val connected = IEmbeddedEngineHost.Stub.asInterface(binder)
            if (connected == null) {
                bound = false
                failSignal()
                Log.e(TAG, "ربطٌ بلا واجهة للمضيف $engineId")
                return
            }
            if (thisGen < EngineHostClient.hostGeneration.get()) {
                Log.w(
                    TAG,
                    "تجاهل onServiceConnected لجيل قديم $thisGen " +
                        "للمضيف $engineId"
                )
                return
            }
            service = connected
            EngineHostClient.hostConnectedAtUptimeMs =
                SystemClock.uptimeMillis()
            EngineHostClient.hostConnectedAtWallMs =
                System.currentTimeMillis()
            // بند 23: التقاط PID تشخيصي على خلفية دون حجب الخيط الرئيسي.
            EngineHostClient.captureHostPidAsync(app)
            val recipient = IBinder.DeathRecipient { onBinderDied(thisGen) }
            try {
                unlinkDeathRecipient()
                binder?.linkToDeath(recipient, 0)
                deathBinder = binder
                deathRecipient = recipient
            } catch (t: RemoteException) {
                onBinderDied(thisGen)
                return
            }
            signal.complete(connected)
            Log.i(TAG, "اتصل المضيف $engineId (جيل=$thisGen)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val gen = connectionGeneration
            service = null
            Log.w(TAG, "انفصل المضيف $engineId (جيل=$gen)")
            EngineHostClient.notifyHostDied(engineId, gen)
        }

        override fun onBindingDied(name: ComponentName?) {
            val gen = connectionGeneration
            drop()
            Log.w(TAG, "مات ربط المضيف $engineId (جيل=$gen)")
            EngineHostClient.notifyHostDied(engineId, gen)
        }

        override fun onNullBinding(name: ComponentName?) {
            bound = false
            failSignal()
            Log.e(TAG, "null binding للمضيف $engineId")
        }
    }

    /** الخدمة الحالية إن كانت حيّة (بلا بناء). */
    fun current(): IEmbeddedEngineHost? = service

    /** خدمة صالحة: البناء لا يعلّق Mutex أثناء انتظار Binder. */
    suspend fun service(
        bindTimeoutMs: Long = BIND_TIMEOUT_MS
    ): IEmbeddedEngineHost? {
        service?.let { return it }
        val mySignal: CompletableDeferred<IEmbeddedEngineHost>
        val locked = bindMutex.tryLock()
        if (!locked) {
            // النطق الحقيقي يفشل سريعاً بدلاً من الانتظار خلف تعافٍ/ربط.
            if (bindTimeoutMs <= SPEECH_BIND_TIMEOUT_MS) {
                Log.w(TAG, "تجاوز انتظار قفل الربط للنطق: $engineId")
                return null
            }
            bindMutex.lock()
        }
        try {
            service?.let { return it }
            if (bound && !signal.isCompleted) {
                mySignal = signal
            } else {
                if (!VisibleEngines.isVisible(engineId)) {
                    Log.d(
                        TAG,
                        "تجاوز ربط المحرك $engineId لأنه مخفي في التصفية"
                    )
                    return null
                }
                if (bound) {
                    runCatching { app.unbindService(callback) }
                    bound = false
                }
                connectionGeneration =
                    EngineHostClient.hostGeneration.get()
                signal = CompletableDeferred()
                mySignal = signal
                val intent = Intent(
                    app, EmbeddedEngineHostService::class.java
                )
                val asked = try {
                    app.bindService(
                        intent, callback, Context.BIND_AUTO_CREATE
                    )
                } catch (t: Throwable) {
                    Log.e(
                        TAG,
                        "bindService رُفض لـ$engineId: ${t.message}"
                    )
                    false
                }
                if (!asked) return null
                bound = true
            }
        } finally {
            bindMutex.unlock()
        }
        return try {
            withTimeoutOrNull(bindTimeoutMs) { mySignal.await() }
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            null
        }
    }

    /** نظّف الربط محلياً دون توليد أحداث موت أو جدولة تعافٍ. */
    fun invalidate() {
        drop()
    }

    /** إغلاق نهائي عند إصدار المحرك. */
    fun close() {
        drop()
    }

    private fun drop() {
        service = null
        unlinkDeathRecipient()
        if (bound) {
            bound = false
            runCatching { app.unbindService(callback) }
        }
        failSignal()
    }

    private fun unlinkDeathRecipient() {
        val binder = deathBinder
        val recipient = deathRecipient
        deathBinder = null
        deathRecipient = null
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
    }

    private fun failSignal() {
        val pending = signal
        if (!pending.isCompleted) {
            pending.completeExceptionally(
                RemoteException("انتهى ربط المضيف $engineId")
            )
        }
    }

    private fun onBinderDied(gen: Long) {
        service = null
        failSignal()
        Log.w(TAG, "مات binder المضيف $engineId (جيل=$gen)")
        EngineHostClient.notifyHostDied(engineId, gen)
    }
}

/** نتيجة نداء واحد: قيمة ناجحة أو فشل برمز معنون. */
private sealed class HostCall<out T> {
    data class Success<T>(val value: T) : HostCall<T>()
    data class Failure(val code: String, val message: String) :
        HostCall<Nothing>()
}

/** فشل مُعلن من بروتوكول المضيف (رمزاً ورسالة). */
private class HostCallException(
    val code: String,
    override val message: String
) : Exception(message)

private fun <T> HostCall<T>.toHostResult(): HostResult = when (this) {
    is HostCall.Success -> HostResult(ok = true)
    is HostCall.Failure -> HostResult.failure(code, message)
}
