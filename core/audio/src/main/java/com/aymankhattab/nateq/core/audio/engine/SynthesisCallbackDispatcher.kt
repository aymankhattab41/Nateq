package com.aymankhattab.nateq.core.audio.engine

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * بند ١٠ و١٣: مُيسِّر إيصال نداءات [SynthesisCallback] على خيط نداءات واحد
 * مملوك للخدمة طوال دورة حياتها (لا الخيط الرئيسي).
 *
 * يوفر مهلة قصوى لـ [postAndWait] لمنع تعليق خيط التخليق إن تأخر
 * أو مات خيط النداءات، مع دعم الإلغاء الفوري.
 */
internal class SynthesisCallbackDispatcher(
    threadName: String = "nateq-tts-callback"
) : AutoCloseable {

    companion object {
        private const val TAG = "SynthesisCallbackDispatcher"
        internal const val DEFAULT_DISPATCH_TIMEOUT_MS = 5_000L
    }

    private val thread = HandlerThread(threadName).apply { start() }
    private val handler = Handler(thread.looper)

    /** نتيجة الاستثناء من الخيط العامل تُعاد إلقاءً على المستدعي. */
    private val thrown = AtomicReference<Throwable?>(null)
    private val closed = AtomicBoolean(false)

    fun postAndWait(block: () -> Unit): Boolean {
        return postAndWait(DEFAULT_DISPATCH_TIMEOUT_MS, block)
    }

    fun postAndWait(
        timeoutMs: Long,
        block: () -> Unit
    ): Boolean {
        if (closed.get()) {
            return false
        }
        if (Thread.currentThread() == thread) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        val ok = handler.post {
            try {
                if (!closed.get()) {
                    block()
                }
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!ok) {
            return false
        }
        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            Log.w(
                TAG,
                "postAndWait timed out after ${timeoutMs}ms"
            )
        }
        thrown.getAndSet(null)?.let { throw it }
        return completed
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            thread.quitSafely()
        }
    }
}
