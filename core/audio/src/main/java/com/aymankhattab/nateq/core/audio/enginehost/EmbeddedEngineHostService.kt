package com.aymankhattab.nateq.core.audio.enginehost

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.providers.EngineStatusRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking

/**
 * ع٢٩-5: مضيف المحركات الأصلية في عملية `:engine_host`.
 *
 * وحدها؛ فأي انهيار JNI يقتل هذه العملية فقط. الواجهة AIDL غير
 * مُصدَّرة ولا تصل لتطبيق خارجي أبداً.
 *
 * سلامة الأنبوب: كتّاب الـPCM لا يتقاطعون (محادثةٌ واحدة في كل
 * وقت — ردّ اتصال المحرّك أثناء speak)،
 * والإطار الأخير (الحالة) يُكتب قبل إغلاق الأنبوب دائماً فيقرأه
 * العميل قبل EOF بترتيبٍ مضمون.
 *
 * علامة الانهيار: تُكتب قبل كل تهيئة أصلية وتُمحى بعد رجوعها؛
 * فإن ماتت العملية أثناء التهيئة تبقى العلامة فترفض الدالة
 * التالية بـ`CRASHED_LAST_RUN` حتى يُطلب الإعادة بـforce.
 */
class EmbeddedEngineHostService : Service() {

    companion object {
        private const val TAG = "EngineHost"

        /** المحركات المعروفة — أي معرّف آخر يُرفض برمز معنون. */
        private val KNOWN_ENGINES = setOf(
            HostProtocol.ENGINE_VOCALIZER,
        )
    }

    private val sessions = ConcurrentHashMap<String, HostEngineSession>()
    private val activeCancels = ConcurrentHashMap<Long, Runnable>()
    private val nextRequestId = AtomicLong(1L)

    /**
     * طابور النطق: طلب واحد ناطقاً في كل وقت (محرّك واحد لا يتحمّل
     * نطقين)، وميزانية كل طلب تبدأ بعد دخوله لا بعد انتظاره.
     */
    private val speakGate = Semaphore(1)

    /** معرّف الطلب الناطق فعلاً (لتمييز إلغاءه عن إلغاء منتظر). */
    private val speakingRequestId = AtomicLong(-1L)

    private lateinit var executor: ExecutorService
    private lateinit var watchdog: ScheduledExecutorService

    private val binder = object : IEmbeddedEngineHost.Stub() {

        override fun init(
            engineId: String?,
            dataDir: String?,
            force: Boolean
        ): String {
            val rawId = engineId ?: return HostProtocol.error(
                "BAD_ENGINE",
                "معرف محرك فارغ"
            )
            val id = HostProtocol.normalizeEngineId(rawId)
            if (id !in KNOWN_ENGINES) {
                return HostProtocol.error("UNKNOWN_ENGINE", id)
            }
            val dir = dataDir ?: ""
            if (!force && HostProtocol.hasMarker(filesDir, id)) {
                val detail = HostProtocol.readMarker(filesDir, id)
                Log.e(TAG, "$id انهار في آخر تشغيل: $detail")
                val status = EngineStatusRegistry.EngineStatus(
                    engineId = id,
                    state = EngineStatusRegistry.State.CRASHED_LAST_RUN,
                    code = "CRASHED_LAST_RUN",
                    lastError = detail,
                    updatedAt = System.currentTimeMillis()
                )
                EngineStatusRegistry.writeStatusFile(filesDir, status)
                return HostProtocol.error("CRASHED_LAST_RUN", detail)
            }
            val session = sessionFor(id)
                ?: return HostProtocol.error("UNKNOWN_ENGINE", id)
            HostProtocol.writeMarker(filesDir, id, "init dir=$dir")
            val res = try {
                session.init(dir)
            } finally {
                HostProtocol.clearMarker(filesDir, id)
            }
            if (!res.ok) {
                Log.e(
                    TAG,
                    "$id init refused [${res.code}]: ${res.message}"
                )
            }
            val status = if (res.ok) {
                EngineStatusRegistry.EngineStatus(
                    engineId = id,
                    state = EngineStatusRegistry.State.READY,
                    code = null,
                    lastError = null,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                val st = EngineStatusRegistry.stateForCode(
                    res.code ?: "UNKNOWN"
                )
                EngineStatusRegistry.EngineStatus(
                    engineId = id,
                    state = st,
                    code = res.code,
                    lastError = res.message,
                    updatedAt = System.currentTimeMillis()
                )
            }
            EngineStatusRegistry.writeStatusFile(filesDir, status)
            return encode(res)
        }

        override fun listVoices(
            engineId: String?,
            langTag: String?
        ): String {
            val rawId = engineId ?: return HostProtocol.error(
                "BAD_ENGINE",
                "معرف محرك فارغ"
            )
            val id = HostProtocol.normalizeEngineId(rawId)
            if (id !in KNOWN_ENGINES) {
                return HostProtocol.error("UNKNOWN_ENGINE", id)
            }
            val voices = when (id) {
                HostProtocol.ENGINE_VOCALIZER -> {
                    val session = sessions[id] as? VocalizerHostSession
                    session?.listVoices() ?: emptyList()
                }
                else -> emptyList()
            }
            return HostProtocol.encodeVoices(voices)
        }

        override fun synthesize(
            engineId: String?,
            voiceId: String?,
            text: String?,
            rate: Float,
            pitch: Float,
            volume: Float,
            out: ParcelFileDescriptor?
        ): String {
            val rawId = engineId ?: return HostProtocol.error(
                "BAD_ENGINE",
                "معرف محرك فارغ"
            )
            val id = HostProtocol.normalizeEngineId(rawId)
            val pipe = out ?: return HostProtocol.error(
                "BAD_PIPE",
                "لم يُمرَّر أنبوب صوت"
            )
            val content = text ?: ""
            if (content.isEmpty()) {
                runCatching { pipe.close() }
                return HostProtocol.error("EMPTY_TEXT", "نصّ فارغ")
            }
            val session = sessions[id]
                ?: run {
                    runCatching { pipe.close() }
                    return HostProtocol.error(
                        "NOT_INITIALIZED",
                        "المحرك $id غير مهيّأ"
                    )
                }
            val request = SpeakRequest(
                voiceId = voiceId ?: "",
                text = content,
                rate = rate,
                pitch = pitch,
                volume = volume
            )
            val requestId = nextRequestId.getAndIncrement()
            val cancelled = AtomicBoolean(false)
            val speakDone = AtomicBoolean(false)
            val timedOut = AtomicBoolean(false)
            val sink = PcmSink(pipe)
            activeCancels[requestId] = Runnable {
                cancelled.set(true)
                // إلغاء طلب في الطابور لا يوقف جاريه: لا يُستدعى
                // stop() إلا إذا كان هذا الطلب هو الناطق فعلاً.
                if (speakingRequestId.get() == requestId) {
                    runCatching { session.stop() }
                }
            }
            val budgetMs = SynthesisBudget.pieceTimeoutMs(content.length)
            executor.execute {
                var guard: ScheduledFuture<*>? = null
                var gateHeld = false
                try {
                    // طابور واحد: ميزانية الطلب تبدأ بعد الدخول لا
                    // بعد الانتظار، فلا تستهلك مهلتها في الطابور.
                    speakGate.acquire()
                    gateHeld = true
                    if (cancelled.get()) {
                        val skipped = HostResult.failure(
                            "CANCELLED",
                            "أُلغي الطلب قبل بدئه"
                        )
                        sink.end(
                            HostProtocol.STATUS_ERROR,
                            "CANCELLED",
                            skipped.message ?: ""
                        )
                        return@execute
                    }
                    speakingRequestId.set(requestId)
                    // حارس الخمول لا الزمن الكلّي: ما دامت الشرائح
                    // تتوالى (تقدّمٌ حديث) لا يُقطع الطلب مهما طال؛
                    // وإن انقطع لمدّة الميزانية فهو خمولٌ حقيقي.
                    guard = watchdog.scheduleWithFixedDelay(
                        {
                            if (!speakDone.get() &&
                                HostWatchdog.expired(
                                    SystemClock.uptimeMillis(),
                                    sink.lastProgressAtMs(),
                                    budgetMs
                                )
                            ) {
                                timedOut.set(true)
                                runCatching { session.stop() }
                            }
                        },
                        budgetMs,
                        HostWatchdog.DEFAULT_POLL_MS,
                        TimeUnit.MILLISECONDS
                    )
                    var res = try {
                        runBlocking {
                            session.speak(request, sink, cancelled)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "$id speak انهار: ${t.message}", t)
                        HostResult.failure(
                            "ENGINE_ERROR",
                            t.message ?: "خطأ غير متوقّع"
                        )
                    }
                    speakDone.set(true)
                    if (timedOut.get() && res.ok) {
                        res = HostResult.failure(
                            "GENERATION_TIMEOUT",
                            "خمل نطق $id ${budgetMs}ms بلا تقدّم"
                        )
                    }
                    sink.end(
                        if (res.ok) {
                            HostProtocol.STATUS_OK
                        } else {
                            HostProtocol.STATUS_ERROR
                        },
                        res.code ?: "",
                        res.message ?: ""
                    )
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.w(TAG, "توقف خيط النطق لـ$id: ${t.message}")
                } catch (t: Throwable) {
                    Log.e(TAG, "$id synthesize فشل: ${t.message}", t)
                    val fail = HostResult.failure(
                        "ENGINE_ERROR",
                        t.message ?: "خطأ غير متوقّع"
                    )
                    sink.end(
                        HostProtocol.STATUS_ERROR,
                        fail.code ?: "",
                        fail.message ?: ""
                    )
                } finally {
                    guard?.cancel(false)
                    if (requestId == speakingRequestId.get()) {
                        speakingRequestId.set(-1L)
                    }
                    if (gateHeld) speakGate.release()
                    activeCancels.remove(requestId)
                    sink.close()
                }
            }
            return HostProtocol.ok(requestId.toString())
        }

        override fun cancel(requestId: Long) {
            val action = activeCancels[requestId] ?: return
            // لا نُوقف المحرّك على خيط Binder: قد يحجزه النطق
            // الحالي (قفل speak) فيتعطّل بقية الواجهة.
            runCatching { executor.execute(action) }
        }

        override fun shutdown(engineId: String?): String {
            val rawId = engineId ?: return HostProtocol.error(
                "BAD_ENGINE",
                "معرف محرك فارغ"
            )
            val id = HostProtocol.normalizeEngineId(rawId)
            sessions.remove(id)?.let { session ->
                runCatching { session.shutdown() }
            }
            Log.i(TAG, "أُغلق محرك $id")
            HostLog.i(id, "أُغلق محرك $id")
            return HostProtocol.ok()
        }

        override fun getLog(engineId: String?, maxLines: Int): String {
            val rawId = engineId ?: return ""
            val id = HostProtocol.normalizeEngineId(rawId)
            val lines = if (maxLines <= 0) {
                HostLog.BUFFER_CAPACITY
            } else {
                maxLines
            }
            return HostLog.getLog(id, lines)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // قبل أي استخدام للمحرك: عطّل fdsan في هذه العملية.
        // libNuanceVocalizer.so يغلق واصفاً مملوكاً لـFILE* بـclose خام
        // (vplatform_file_Close)، وهو ما يُجهض العملية عند تفعيل fdsan
        // (targetSdk 29+). SmartVoice ينجو لأنه يستهدف API 28.
        NativeFdsanGuard.ensureDisabled()
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "engine-host-synth")
        }
        watchdog = Executors.newScheduledThreadPool(1) { runnable ->
            Thread(runnable, "engine-host-watchdog")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        for (session in sessions.values) {
            runCatching { session.shutdown() }
        }
        sessions.clear()
        runCatching { executor.shutdownNow() }
        runCatching { watchdog.shutdownNow() }
        super.onDestroy()
    }

    /** جلسة المحرك (تُنشأ عند أول استعمال). */
    private fun sessionFor(id: String): HostEngineSession? {
        val normId = HostProtocol.normalizeEngineId(id)
        sessions[normId]?.let { return it }
        val created = when (normId) {
            HostProtocol.ENGINE_VOCALIZER ->
                VocalizerHostSession(this)
            else -> return null
        }
        return sessions.putIfAbsent(normId, created) ?: created
    }

    private fun encode(res: HostResult): String {
        if (!res.ok) {
            return HostProtocol.error(
                res.code ?: "UNKNOWN",
                res.message
            )
        }
        return res.payload?.let { HostProtocol.ok(it) }
            ?: HostProtocol.ok()
    }
}
