package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * حارس عقد ع٣٩-1: خيط المحرك الواحد الثابت.
 *
 * يحرس الاسم الصريح للكسر الذي مُنع:
 * `engine tasks must all run on one persistent thread`
 * و`engine task must fail with a named timeout, never hang`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineThreadTest {

    @Test
    fun submit_allTasksRunOnOnePersistentThread() {
        val thread = EngineThread(threadName = "voc-engine-guard")
        val names = (1..5).map {
            thread.submit(5_000L, "step$it") {
                Thread.currentThread().name
            }
        }
        val expected = "voc-engine-guard"
        names.forEach { assertEquals(expected, it) }
        // كل المهام على الخيط نفسه لا على خيوط مستدعِينَ
        assertEquals(1, names.toSet().size)
    }

    @Test
    fun submit_doesNotRunOnCallerThread() {
        val thread = EngineThread(threadName = "voc-engine-guard")
        val callerName = Thread.currentThread().name
        val workerName = thread.submit(5_000L, "step") {
            Thread.currentThread().name
        }
        assertNotEquals(callerName, workerName)
    }

    @Test
    fun submit_returnsTaskResult() {
        val thread = EngineThread(threadName = "voc-engine-guard")
        val result = thread.submit(5_000L, "step") { 41 + 1 }
        assertEquals(42, result)
    }

    @Test
    fun submit_throwsNamedTimeout_whenTaskExceedsDeadline() {
        val thread = EngineThread(threadName = "voc-engine-guard")
        val release = CountDownLatch(1)
        var caught: EngineTaskTimeoutException? = null
        try {
            thread.submit(50L, "slowStep") {
                release.await(5, TimeUnit.SECONDS)
                "done"
            }
        } catch (e: EngineTaskTimeoutException) {
            caught = e
        } finally {
            release.countDown()
        }
        val err = caught
            ?: throw AssertionError(
                "engine task must fail with a named timeout, never hang"
            )
        assertEquals("slowStep", err.step)
        assertEquals(50L, err.timeoutMs)
    }

    @Test
    fun submit_propagatesTaskExceptionCause() {
        val thread = EngineThread(threadName = "voc-engine-guard")
        val marker = IllegalStateException("عطل داخلي مقصود")
        var caught: Throwable? = null
        try {
            thread.submit(5_000L, "step") { throw marker }
        } catch (t: Throwable) {
            caught = t
        }
        assertSame(marker, caught)
    }

    @Test
    fun submit_keepsThreadAfterManySequentialTasks() {
        val thread = EngineThread(threadName = "voc-engine-guard")
        val counter = AtomicInteger(0)
        repeat(20) {
            thread.submit(5_000L, "step") { counter.incrementAndGet() }
        }
        assertEquals(20, counter.get())
    }
}
