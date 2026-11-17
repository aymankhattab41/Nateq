package com.aymankhattab.nateq.core.audio.engine

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * بند ١٠: مُيسِّر إيصال نداءات [SynthesisCallback] على خيط تخليق واحد
 * مملوك للخدمة (لا الخيط الرئيسي).
 *
 * قبل هذا كانت نداءات `start/audioAvailable/done/error` تُستدعى من خيوط
 * مزوّدي الصوت العاملة، فمع التخليق المتوازي تتسابق أكثر من شريحة على
 * الكائن وتختلط ترتيبُ الدفعات. المبدأ هنا:
 *  - خيط واحد مخصّص للنداءات (`HandlerThread` مستقلّ) تُنفَّذ عليه كل
 *    النداءات بترتيب وصولها.
 *  - كل استدعاء يحظر حتى ينفذ فعلاً على ذلك الخيط (`postAndWait`)،
 *    فيبقى [TextToSpeechService.onSynthesizeText] في انتظار الاكتمال
 *    قبل العودة (معيار AOSP).
 *  - لا يُستخدم الخيط الرئيسي إطلاقاً.
 */
internal class SynthesisCallbackDispatcher : AutoCloseable {

    private val thread = HandlerThread("nateq-tts-callback").apply { start() }
    private val handler = Handler(thread.looper)

    /** نتيجة الاستثناء من الخيط العامل تُعاد إلقاءً على المستدعي. */
    private val thrown = AtomicReference<Throwable?>(null)

    fun postAndWait(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val ok = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!ok) {
            // الخيط أُغلق أثناء التدمير: نفّذ مباشرةً كملاذ أخير.
            block()
            return
        }
        latch.await()
        thrown.getAndSet(null)?.let { throw it }
    }

    override fun close() {
        thread.quitSafely()
    }
}
