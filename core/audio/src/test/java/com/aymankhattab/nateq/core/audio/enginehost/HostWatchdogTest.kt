package com.aymankhattab.nateq.core.audio.enginehost

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارسُ عقد حارس المضيف: يقيس الخمول لا الزمن الكلّي.
 *
 * العطل الذي يحرسه: محرّكٌ متدفّق (Vocalizer) نطق نصاً قصيراً لأكثر من
 * `pieceTimeoutMs` بسبب البثّ بسرعة التشغيل، فكان حارسٌ على الزمن
 * الكلّي يوقفه في منتصفه ويعيد `STOPPED` رغم أن الشرائح تتوالى.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostWatchdogTest {

    @Test
    fun doesNotExpireWhilePcmKeepsFlowingBeyondPieceBudget() {
        val pieceBudgetMs = 3_000L
        var lastProgressAtMs = 0L
        var nowMs = 0L
        // محرّكٌ يبثّ شريحة كل 500ms لزمنٍ يتجاوز الميزانية (≈5.1s).
        repeat(10) {
            nowMs += 500L
            lastProgressAtMs = nowMs
            assertFalse(
                "شريحةٌ قبل لحظات: ليس خمولاً",
                HostWatchdog.expired(
                    nowMs,
                    lastProgressAtMs,
                    pieceBudgetMs
                )
            )
        }
    }

    @Test
    fun expiresWhenNoProgressWithinBudget() {
        assertTrue(
            HostWatchdog.expired(
                nowMs = 4_000L,
                lastProgressAtMs = 1_000L,
                inactivityBudgetMs = 3_000L
            )
        )
    }

    @Test
    fun doesNotExpireBeforeBudgetElapses() {
        assertFalse(
            HostWatchdog.expired(
                nowMs = 3_999L,
                lastProgressAtMs = 1_000L,
                inactivityBudgetMs = 3_000L
            )
        )
    }

    @Test
    fun expiresExactlyAtBudgetBoundary() {
        assertTrue(
            HostWatchdog.expired(
                nowMs = 4_000L,
                lastProgressAtMs = 1_000L,
                inactivityBudgetMs = 3_000L
            )
        )
    }

    /**
     * عقد `awaitProgressOrIdle`: توليدٌ متدفّق يطول عن ميزانية
     * القطعة (5s مقابل 3s) لكن الشرائح تتوالى كل 500ms ⇒ ينتهي
     * بنجاح ولا يُقطع.
     */
    @Test
    fun awaitProgressOrIdle_finishesStreamingLongerThanPieceBudget() =
        runTest {
            val scheduler = testScheduler
            var active = true
            var lastProgress = 0L
            val streamer = launch {
                repeat(10) {
                    delay(500L)
                    lastProgress = scheduler.currentTime
                }
                active = false
            }
            val finished = HostWatchdog.awaitProgressOrIdle(
                isActive = { active },
                lastProgressAtMs = { lastProgress },
                nowMs = { scheduler.currentTime },
                inactivityBudgetMs = 3_000L
            )
            assertTrue(
                "تقدّمٌ متواصل لا يُقطع رغم تجاوز الميزانية",
                finished
            )
            streamer.join()
        }

    /** عقد `awaitProgressOrIdle`: انقطاع التقدّم ميزانيةً ⇒ خمول. */
    @Test
    fun awaitProgressOrIdle_expiresWhenProgressStalls() = runTest {
        val scheduler = testScheduler
        val finished = HostWatchdog.awaitProgressOrIdle(
            isActive = { true },
            lastProgressAtMs = { 0L },
            nowMs = { scheduler.currentTime },
            inactivityBudgetMs = 3_000L
        )
        assertFalse("انقطع التقدّم ⇒ خمول", finished)
    }
}
