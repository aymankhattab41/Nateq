package com.aymankhattab.nateq.core.audio.enginehost

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    threadName: String = "voc-engine"
) {

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
        HostLog.w(
            engineId,
            "[THREAD] $step إرسال من name=${caller.name} " +
                "إلى خيط المحرك"
        )
        val future = executor.submit(Callable {
            val worker = Thread.currentThread()
            HostLog.w(
                engineId,
                "[THREAD] $step تنفيذ على name=${worker.name}"
            )
            task.call()
        })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            HostLog.e(
                engineId,
                "[THREAD] $step انتهت مهلتها (${timeoutMs}ms)"
            )
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
