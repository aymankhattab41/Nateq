package com.aymankhattab.nateq.core.audio.enginehost

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
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
        val call = callHost(context, engineId) { svc ->
            val decoded = HostProtocol.decodeResult(
                svc.init(engineId, dataDir, force)
            )
            if (!decoded.ok) {
                throw HostCallException(
                    decoded.code ?: "UNKNOWN",
                    decoded.message ?: ""
                )
            }
        }
        return call.toHostResult()
    }

    /** قائمة أصوات المضيف؛ فشلها سببٌ معنون لا قائمةٌ صامتة. */
    suspend fun listVoices(
        context: Context,
        engineId: String
    ): HostVoicesResult {
        val call = callHost(context, engineId) { svc ->
            val decoded = HostProtocol.decodeVoicesResponse(
                svc.listVoices(engineId, null)
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
        if (text.isEmpty()) {
            return HostResult.failure("EMPTY_TEXT", "نصّ فارغ")
        }
        val deadlineAt = SystemClock.uptimeMillis() +
            SynthesisBudget.requestTimeoutMs(text.length)
        val call = callHost(context, engineId) { svc ->
            val (readFd, writeFd) = ParcelFileDescriptor.createPipe()
            val raw = try {
                svc.synthesize(
                    engineId,
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
                    deadlineAt,
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
                cancelRequest(engineId, svc, requestId)
            }
        }
        return call.toHostResult()
    }

    /**
     * إصدار اتصال محرك: يوقف جلسته في المضيف ثم يفكّ الربط. بلا
     * انتظار (لا يعلّق خيط المُستدعي — يُنفَّذ خلفياً).
     */
    fun release(context: Context, engineId: String) {
        val connection = connections.remove(engineId) ?: return
        background.execute {
            runCatching { connection.current()?.shutdown(engineId) }
            connection.close()
        }
    }

    /** هل ثمة ربط حيّ بهذا المحرك الآن؟ (فحص الاختبارات فقط). */
    @VisibleForTesting
    fun isBound(engineId: String): Boolean =
        connections[engineId]?.current() != null

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
        body: suspend (IEmbeddedEngineHost) -> T
    ): HostCall<T> {
        var attempt = 0
        var last = HostCall.Failure(
            "HOST_UNAVAILABLE",
            "تعذّر الوصول إلى مضيف $engineId"
        )
        while (attempt < 2) {
            attempt++
            val svc = connection(context, engineId).service()
            if (svc == null) {
                last = HostCall.Failure(
                    "HOST_UNAVAILABLE",
                    "تعذّر ربط مضيف $engineId"
                )
                continue
            }
            val result: HostCall<T> = try {
                HostCall.Success(body(svc))
            } catch (t: HostCallException) {
                HostCall.Failure(t.code, t.message)
            } catch (t: DeadObjectException) {
                Log.e(TAG, "موت المضيف أثناء النداء: ${t.message}")
                HostCall.Failure("HOST_DIED", "انهار مضيف $engineId")
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
            if (!died || attempt >= 2) return last
            // ع٢٩-5: إعادة محاولة واحدة فقط — أعد البناء ثم أعد النداء.
            connection(context, engineId).invalidate()
        }
        return last
    }

    private fun cancelRequest(
        engineId: String,
        svc: IEmbeddedEngineHost,
        requestId: Long
    ) {
        if (requestId <= 0) return
        background.execute {
            runCatching { svc.cancel(requestId) }
                .onFailure {
                    Log.w(TAG, "إلغاء $engineId/$requestId: ${it.message}")
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
        deadlineAt: Long,
        onFormatInfo: (Int, Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit
    ): HostResult {
        val captured = AtomicReference<HostResult?>(null)
        val reader = Thread({
            try {
                DataInputStream(
                    BufferedInputStream(FileInputStream(pfd.fileDescriptor))
                ).use { input ->
                    while (true) {
                        val frame = HostProtocol.readFrame(input)
                            ?: break
                        when (frame) {
                            is HostProtocol.HostFrame.Format ->
                                onFormatInfo(
                                    frame.sampleRate,
                                    frame.channels
                                )
                            is HostProtocol.HostFrame.Pcm ->
                                onAudioChunk(
                                    frame.bytes,
                                    frame.bytes.size
                                )
                            is HostProtocol.HostFrame.End -> {
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

        val budget = (deadlineAt - SystemClock.uptimeMillis())
            .coerceAtLeast(0L)
        val finished = withTimeoutOrNull(budget) {
            while (reader.isAlive) delay(POLL_INTERVAL_MS)
            true
        } ?: false
        if (finished) {
            return captured.get() ?: HostResult.failure(
                "HOST_EOF",
                "انتهى الأنبوب قبل أن يسجّل القارئ نتيجة"
            )
        }
        return HostResult.failure(
            "REQUEST_TIMEOUT",
            "انتهت مهلة قراءة صوت المضيف"
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

    private fun connection(
        context: Context,
        engineId: String
    ): HostConnection {
        connections[engineId]?.let { return it }
        val created = HostConnection(context.applicationContext, engineId)
        return connections.putIfAbsent(engineId, created) ?: created
    }
}

/**
 * ربط محرك واحد بـ`:engine_host`: بناءٌ متزامن (Mutex) فلا يتداخل
 * بناءان على الواجهة، واستقبال `onBindingDied`/موت الـBinder بإعادة
 * بناء الربط عند النداء التالي.
 */
private class HostConnection(
    context: Context,
    private val engineId: String
) {

    companion object {
        private const val TAG = "EngineHostConn"

        /** مهلة بناء الربط المحلي (الخدمة في نفس الجهاز). */
        private const val BIND_TIMEOUT_MS = 10_000L
    }

    private val app = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var service: IEmbeddedEngineHost? = null

    @Volatile
    private var bound = false

    /** إشارة اكتمال آخر بناء — تتبدّل في كل محاولة بناء. */
    @Volatile
    private var signal = CompletableDeferred<IEmbeddedEngineHost>()

    private val callback = object : ServiceConnection {

        override fun onServiceConnected(
            name: ComponentName?,
            binder: IBinder?
        ) {
            val connected = IEmbeddedEngineHost.Stub.asInterface(binder)
            service = connected
            try {
                binder?.linkToDeath({ onBinderDied() }, 0)
            } catch (t: RemoteException) {
                onBinderDied()
                return
            }
            signal.complete(connected)
            Log.i(TAG, "اتصل المضيف $engineId")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // ماتت العملية: المنظومة تعيد تشغيلها وتستدعي الربط ثانية.
            service = null
            Log.w(TAG, "انفصل المضيف $engineId — ستعيده المنظومة")
        }

        override fun onBindingDied(name: ComponentName?) {
            // ربطٌ ميت لا يُستعاد تلقائياً: نظّفه ليُبنى من جديد.
            drop()
            Log.w(TAG, "مات ربط المضيف $engineId")
        }

        override fun onNullBinding(name: ComponentName?) {
            bound = false
            failSignal()
            Log.e(TAG, "null binding للمضيف $engineId")
        }
    }

    /** الخدمة الحالية إن كانت حيّة (بلا بناء). */
    fun current(): IEmbeddedEngineHost? = service

    /** خدمة صالحة: البناء متزامن بمفتاح واحد فلا يتداخل طلبان. */
    suspend fun service(): IEmbeddedEngineHost? {
        service?.let { return it }
        mutex.withLock {
            service?.let { return it }
            return bindLocked()
        }
    }

    /** نظّف الربط (إعادة محاولة بعد الموت). */
    suspend fun invalidate() {
        mutex.withLock { drop() }
    }

    /** إغلاق نهائي عند إصدار المحرك — ينتظر بناءً جارياً لا يتركه. */
    fun close() {
        runBlocking { mutex.withLock { drop() } }
    }

    private fun drop() {
        service = null
        if (bound) {
            bound = false
            runCatching { app.unbindService(callback) }
        }
        failSignal()
    }

    private fun failSignal() {
        val pending = signal
        if (!pending.isCompleted) {
            pending.completeExceptionally(
                RemoteException("انتهى ربط المضيف $engineId")
            )
        }
    }

    private fun onBinderDied() {
        service = null
        failSignal()
    }

    private suspend fun bindLocked(): IEmbeddedEngineHost? {
        if (bound) {
            runCatching { app.unbindService(callback) }
            bound = false
        }
        signal = CompletableDeferred()
        val intent = Intent(app, EmbeddedEngineHostService::class.java)
        val asked = try {
            app.bindService(intent, callback, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            Log.e(TAG, "bindService رُفض لـ$engineId: ${t.message}")
            false
        }
        if (!asked) return null
        bound = true
        return try {
            withTimeoutOrNull(BIND_TIMEOUT_MS) { signal.await() }
        } catch (t: CancellationException) {
            throw t
        } catch (_: Throwable) {
            null
        }
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
