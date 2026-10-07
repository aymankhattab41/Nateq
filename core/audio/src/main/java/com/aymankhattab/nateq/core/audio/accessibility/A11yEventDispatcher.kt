package com.aymankhattab.nateq.core.audio.accessibility

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * موزّع أحداث إمكانية الوصول على HandlerThread مخصص (ع٢٧-7).
 * - دمج الأحداث المتكررة لنفس النافذة (debounce 40–120ms).
 * - طابور محدود الحجم (64) يُسقط الأقدم عند الامتلاء.
 * - قياس زمن المعالجة ومعدل الأحداث للتشخيص.
 */
class A11yEventDispatcher(
    private val onProcessEvent: (AccessibilityEvent) -> Unit
) {

    private val thread = HandlerThread("A11yDispatcherThread").apply {
        start()
    }
    private val handler = Handler(thread.looper)

    private val eventQueue =
        ArrayBlockingQueue<AccessibilityEvent>(QUEUE_CAPACITY)

    // عدّادات التشخيص
    val totalProcessedCount = AtomicLong(0L)
    val droppedEventsCount = AtomicLong(0L)
    val totalProcessingTimeMs = AtomicLong(0L)

    private var lastEventTime = 0L
    private var lastEventType = 0
    private var lastEventPackage: String? = null

    /**
     * إرسال حدث للمعالجة الخلفية مع دمج وتفادي الإغراق.
     */
    fun dispatch(event: AccessibilityEvent) {
        val eventType = event.eventType
        val pkg = event.packageName?.toString()
        val now = SystemClock.uptimeMillis()

        // Debounce: دمج الأحداث المتكررة لنفس الحزمة والنوع
        val debounceMs = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> 100L
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> 120L
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> 40L
            else -> 50L
        }

        if (eventType == lastEventType &&
            pkg == lastEventPackage &&
            (now - lastEventTime) < debounceMs
        ) {
            // حدث مكرر سريع جداً، يُسقط لتفادي إجهاد المعالج
            return
        }

        lastEventType = eventType
        lastEventPackage = pkg
        lastEventTime = now

        // نسخ الحدث قبل تسليمه للخيط الخلفي
        val eventCopy = runCatching {
            @Suppress("DEPRECATION")
            AccessibilityEvent.obtain(event)
        }.getOrNull() ?: return

        if (!eventQueue.offer(eventCopy)) {
            // الطابور ممتلئ، نسقط أقدم حدث
            val dropped = eventQueue.poll()
            dropped?.let { SafeNodeWalker.safeRecycle(null) }
            droppedEventsCount.incrementAndGet()
            eventQueue.offer(eventCopy)
        }

        handler.post {
            processNext()
        }
    }

    private fun processNext() {
        val next = eventQueue.poll() ?: return
        val start = SystemClock.uptimeMillis()
        try {
            onProcessEvent(next)
        } finally {
            val duration = SystemClock.uptimeMillis() - start
            totalProcessingTimeMs.addAndGet(duration)
            totalProcessedCount.incrementAndGet()
            runCatching {
                @Suppress("DEPRECATION")
                next.recycle()
            }
        }
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        while (eventQueue.isNotEmpty()) {
            val ev = eventQueue.poll()
            runCatching {
                @Suppress("DEPRECATION")
                ev?.recycle()
            }
        }
    }

    companion object {
        const val QUEUE_CAPACITY = 64
    }
}
