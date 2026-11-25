package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SynthesisGroup4Test {

    @Test
    fun callbackDispatcher_postAndWait_executesBlockSuccessfully() {
        val dispatcher = SynthesisCallbackDispatcher("test_dispatcher_1")
        val executed = AtomicBoolean(false)
        try {
            // تشغيل خيط الـ Looper لضمان معالجة المهام في بيئة الاختبار
            val thread = dispatcher.javaClass.getDeclaredField("thread")
                .apply { isAccessible = true }.get(dispatcher)
                as android.os.HandlerThread
            val looper = thread.looper

            // إرسال المهمة
            var result = false
            val t = Thread {
                result = dispatcher.postAndWait(2000L) {
                    executed.set(true)
                }
            }
            t.start()
            // في Robolectric قد نحتاج إلى استهلاك مهام الـ Looper
            ShadowLooper.shadowMainLooper().idle()
            org.robolectric.Shadows.shadowOf(looper).idle()
            t.join(3000L)

            assertTrue("يجب تنفيذ الكتلة البرمجية", executed.get())
            assertTrue("يجب أن يعيد true عند نجاح التنفيذ", result)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun callbackDispatcher_postAndWait_returnsFalseWhenClosed() {
        val dispatcher = SynthesisCallbackDispatcher("test_dispatcher_2")
        dispatcher.close()
        val result = dispatcher.postAndWait(1000L) {
            // لا يجب أن يتم التنفيذ إذا كان الخيط مغلقاً
        }
        assertFalse("يجب أن يعيد false فوراً إذا كان الخيط مغلقاً", result)
    }

    @Test
    fun callbackDispatcher_postAndWait_timesOutWithoutHanging() {
        val dispatcher = SynthesisCallbackDispatcher("test_dispatcher_3")
        try {
            val result = dispatcher.postAndWait(5L) {
                Thread.sleep(100L)
            }
            assertFalse("يجب أن يعيد false عند انتهاء المهلة", result)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun latencyTracker_recordsAndFormatsQueueWaitTime() {
        SpeechLatencyTracker.resetForTesting()
        val metric = SpeechLatencyTracker.UtteranceMetric(
            text = "اختبار الانتظار في الطابور",
            queueWaitMs = 150L,
            preprocessDurationMs = 20L,
            planningDurationMs = 15L,
            timeToFirstByteMs = 200L,
            totalDurationMs = 500L
        )
        SpeechLatencyTracker.recordUtterance(metric)
        assertEquals(150L, metric.queueWaitMs)

        val report = SpeechLatencyTracker.formatSpeedReport()
        assertTrue(
            "يجب أن يتضمن التقرير وقت انتظار الطابور",
            report.contains("انتظار: 150ms")
        )
    }

    @Test
    fun synthesisBudget_adaptiveIdleTimeout_warmIsShorterThanCold() {
        val coldIdle = SynthesisBudget.idleTimeoutMs(
            lastPieceDurationMs = 0L,
            isWarm = false
        )
        val warmIdle = SynthesisBudget.idleTimeoutMs(
            lastPieceDurationMs = 0L,
            isWarm = true
        )

        assertEquals(
            SynthesisBudget.IDLE_TIMEOUT_FLOOR_COLD_MS,
            coldIdle
        )
        assertEquals(
            SynthesisBudget.IDLE_TIMEOUT_FLOOR_WARM_MS,
            warmIdle
        )
        assertTrue(
            "مهلة الخمول للمحرك الدافئ يجب أن تكون أقصر من البارد",
            warmIdle < coldIdle
        )
    }

    @Test
    fun synthesisBudget_inactivityBudget_respectsWarmFlag() {
        val coldBudget = SynthesisBudget.inactivityBudgetMs(
            chars = 50,
            isWarm = false
        )
        val warmBudget = SynthesisBudget.inactivityBudgetMs(
            chars = 50,
            isWarm = true
        )

        assertEquals(15_000L, coldBudget)
        assertEquals(6_000L, warmBudget)
    }
}
