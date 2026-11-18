package com.aymankhattab.nateq.core.audio.enginehost

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * استثناء انتهاء مهلة مهمة على خيط المحرك (ع٣٩-1).
 *
 * يُرمى من [EngineThread.submit] حين لا تكتمل المهمة داخل
 * مهلة الاستدعاء؛ فيُعالجه المُستدعي بفشل معنون لا بتعلّق أبدي.
 */
internal class EngineTaskTimeoutException(
    val step: String,
    val timeoutMs: Long
) : RuntimeException(
    "انتهت مهلة [$step] بعد ${timeoutMs}ms على خيط المحرك"
)

/**
 * ع٣٩-1: خيط محرّك واحد ثابت لكل [VocalizerHostSession].
 *
 * مكتبة Vocalizer الأصلية تعتمد على ثبات خيط التنفيذ (thread-local
 * state)؛ فكان تنفيذ الإنشاء والتهيئة على خيط Binder، وتحميل الصوت
 * والنطق على خيط مُجمّع آخر، والإيقاف على خيط المُشرف — وهو تباينٌ
 * يُفسّر فشل `loadVoice` رغم نجاح `initialize`.
 *
 * الحل: طابور خيط واحد حقيقي يبقى نفسه طول عمر الجلسة، وكل نداء
 * أصلي يُنفَّذ عليه وحده، والباقي ينتظر نتيجته بمهلة صريحة.
 */
internal class EngineThread(
    private val engineId: String = HostProtocol.ENGINE_VOCALIZER,
    threadName: String = "voc-engine",
    /** مجلد علامات المضيف؛ يُمرَّر في الإنتاج، ويُترك null في الاختبارات. */
    private val markerDir: File? = null,
    /** رابط إنهاء المضيف وإعادة تشغيله؛ الافتراضي قتل عملية :engine_host. */
    private val onNativeTimeout: (() -> Unit)? = null
) {

    companion object {
        /**
         * إعادة تشغيل واحدة فقط لكل عمر العملية: منع حلقة إعادة تشغيل
         * لا نهائية لو ظل النداء الأصلي معلّقاً بعد عودة المضيف.
         */
        private val restartAttempted = AtomicBoolean(false)

        /** يعيد ضبط حارس إعادة التشغيل — للاختبارات فقط. */
        internal fun resetRestartGuardForTest() {
            restartAttempted.set(false)
        }
    }

    /** خيط واحد حقيقي مُعاد استخدامه (لا يُنشأ بغيره). */
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }

    /**
     * يُرسل مهمة إلى خيط المحرك وينتظر نتيجتها حتى [timeoutMs].
     *
     * يُسجّل اسم الخيط لحظة الإرسال ولحظة التنفيذ في سجل المضيف
     * الدائم بصيغة `[THREAD] <step>` — دليلٌ قاطع على أن كل
     * العمليات جرت على خيط واحد عند تشخيص فشل التحميل.
     *
     * @throws EngineTaskTimeoutException عند تجاوز المهلة.
     * @throws Throwable أي استثناء رُمي داخل المهمة نفسه.
     */
    fun <T> submit(timeoutMs: Long, step: String, task: Callable<T>): T {
        val caller = Thread.currentThread()
        // البند ١٦: توجيه الخيط روتيني وليس تحذيراً — لا يُغرق السجل.
        HostLog.d(
            engineId,
            "[THREAD] $step إرسال من name=${caller.name} " +
                "إلى خيط المحرك"
        )
        val future = executor.submit(Callable {
            val worker = Thread.currentThread()
            HostLog.d(
                engineId,
                "[THREAD] $step تنفيذ على name=${worker.name}"
            )
            task.call()
        })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // البند ٩: نداء أصلي معلّق يُبقي كل النداءات التالية في
            // الطابور. نكتب علامة تعليق بجانب CRASHED_LAST_RUN ثم
            // ننهي :engine_host مرة واحدة كي يُعاد تشغيله نظيفاً.
            HostLog.e(
                engineId,
                "[THREAD] $step انتهت مهلتها (${timeoutMs}ms) — " +
                    "${HostProtocol.CODE_CRASHED_LAST_RUN} " +
                    "PENDING_NATIVE_TIMEOUT step=$step"
            )
            val dir = markerDir
            if (dir != null &&
                restartAttempted.compareAndSet(false, true)
            ) {
                runCatching {
                    HostProtocol.writeMarker(
                        dir,
                        engineId,
                        "${HostProtocol.CODE_CRASHED_LAST_RUN} " +
                            "PENDING_NATIVE_TIMEOUT step=$step"
                    )
                }
                val restart = onNativeTimeout
                if (restart != null) {
                    runCatching { restart() }
                } else {
                    runCatching {
                        android.os.Process.killProcess(
                            android.os.Process.myPid()
                        )
                    }
                }
            }
            throw EngineTaskTimeoutException(step, timeoutMs)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /** إغلاق خيط المحرك عند تفكيك الجلسة (best-effort). */
    fun shutdown() {
        executor.shutdownNow()
    }
}
