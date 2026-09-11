package com.aymankhattab.nateq.core.audio.announcement

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** دورة إبقاء بثّ goAsync حياً بانتظار اكتمال النطق (بند [4]): يختبر أن
 *  [TimeAlarmReceiver.runTickFlow] يُنهي البث مرةً واحدة بالضبط سواء اكتمل
 *  النطق أو انقضت نافذة الأمان، مع نطقٍ واحد وجاهزيةٍ واحدةٍ سابقين. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeAlarmReceiverLifecycleTest {

    @Test
    fun `speech completion finishes the broadcast exactly once`() = runTest {
        val listener = AtomicReference<(() -> Unit)?>()
        var finishCalls = 0
        var tickCalls = 0
        var startCalls = 0
        val removed = ArrayList<() -> Unit>()

        launch {
            TimeAlarmReceiver.runTickFlow(
                startContext = { startCalls++ },
                addCompletionListener = { listener.set(it) },
                removeCompletionListener = { removed.add(it) },
                tick = { tickCalls++ },
                finishPending = { finishCalls++ },
                windowMillis = 10_000L
            )
        }
        // شغّل الدورة حتى ساعة أول تعليق (التسجيل والنطق تمّا) ثم اكتمال
        // النطق الفعلي قبل نافذة الأمان — إنهاء فوري واحد
        runCurrent()
        assertEquals(1, startCalls)
        assertEquals(1, tickCalls)
        listener.get()?.invoke()
        advanceUntilIdle()

        assertEquals(1, finishCalls)
        assertEquals(1, tickCalls)
        assertEquals(1, startCalls)
        assertEquals(1, removed.size)
        assertTrue(removed[0] === listener.get())
    }

    @Test
    fun `silent speech finishes once at the safety window`() = runTest {
        var finishCalls = 0
        var tickCalls = 0
        val removedCount = AtomicInteger(0)
        val listener = AtomicReference<(() -> Unit)?>()

        launch {
            TimeAlarmReceiver.runTickFlow(
                startContext = {},
                addCompletionListener = { listener.set(it) },
                removeCompletionListener = { removedCount.incrementAndGet() },
                tick = { tickCalls++ },
                finishPending = { finishCalls++ },
                windowMillis = 500L
            )
        }
        advanceUntilIdle()

        assertEquals(1, finishCalls)
        assertEquals(1, tickCalls)
        assertEquals(1, removedCount.get())
        assertTrue(listener.get() != null)
    }

    @Test
    fun `completion racing the timeout still finishes once`() = runTest {
        val listener = AtomicReference<(() -> Unit)?>()
        var finishCalls = 0

        launch {
            TimeAlarmReceiver.runTickFlow(
                startContext = {},
                addCompletionListener = { listener.set(it) },
                removeCompletionListener = {},
                tick = {},
                finishPending = { finishCalls++ },
                windowMillis = 1_000L
            )
        }
        // اكتمالٌ في منتصف النافذة ثم انقضاء السقف — استدعاء واحد
        advanceTimeBy(500L)
        listener.get()?.invoke()
        advanceUntilIdle()

        assertEquals(1, finishCalls)
    }

    @Test
    fun `repeated completions do not double-finish`() = runTest {
        val listener = AtomicReference<(() -> Unit)?>()
        var finishCalls = 0

        launch {
            TimeAlarmReceiver.runTickFlow(
                startContext = {},
                addCompletionListener = { listener.set(it) },
                removeCompletionListener = {},
                tick = {},
                finishPending = { finishCalls++ },
                windowMillis = 10_000L
            )
        }
        runCurrent()
        listener.get()?.invoke()
        listener.get()?.invoke()
        advanceUntilIdle()

        assertEquals(1, finishCalls)
    }

    @Test
    fun `tick failure finishes once and cleans the listener`() = runTest {
        var finishCalls = 0
        val removedCount = AtomicInteger(0)
        val outcome = CompletableDeferred<Throwable?>()

        launch {
            try {
                TimeAlarmReceiver.runTickFlow(
                    startContext = {},
                    addCompletionListener = {},
removeCompletionListener = {
                        removedCount.incrementAndGet()
                    },
                    tick = { throw IllegalStateException("tick boom") },
                    finishPending = { finishCalls++ },
                    windowMillis = 500L
                )
                outcome.complete(null)
            } catch (t: Throwable) {
                outcome.complete(t)
            }
        }
        advanceUntilIdle()

        // الاستثناء ينتشر للمتصل (onReceive يلتقطه ويُسجّله) مع إنهاءٍ واحد
        assertTrue(outcome.await() is IllegalStateException)
        assertEquals(1, finishCalls)
        assertEquals(1, removedCount.get())
    }
}