package com.aymankhattab.nateq.core.audio.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات موسع قراءة المزيد وقاطع الدائرة واستراتيجية 5 (الجولة 48 - الأمر 4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadMoreExpanderRound48Test {

    @Before
    fun setUp() {
        ReadMoreExpander.resetStateForTesting()
    }

    @After
    fun tearDown() {
        ReadMoreExpander.resetStateForTesting()
    }

    @Test
    fun rateLimit_twelveClicksAllowed_thirteenthBlocked() {
        val pkg = "org.telegram.messenger"
        for (i in 1..12) {
            val node = AccessibilityNodeInfo.obtain().apply {
                text = "عرض المزيد"
                viewIdResourceName = "btn_$i"
                isClickable = true
                packageName = pkg
            }
            try {
                assertTrue(
                    "Click $i should succeed",
                    ReadMoreExpander.tryExpand(node)
                )
            } finally {
                SafeNodeWalker.safeRecycle(node)
            }
        }

        // النقرة رقم 13 في نفس الدقيقة تُحجب
        val node13 = AccessibilityNodeInfo.obtain().apply {
            text = "عرض المزيد"
            viewIdResourceName = "btn_13"
            isClickable = true
            packageName = pkg
        }
        try {
            assertFalse(
                "Click 13 must be blocked by rate limit",
                ReadMoreExpander.tryExpand(node13)
            )
        } finally {
            SafeNodeWalker.safeRecycle(node13)
        }
    }

    @Test
    fun circuitBreaker_triggersAfterThreeConsecutiveFailures() {
        val pkg = "com.facebook.katana"

        // 3 إخفاقات متتالية
        ReadMoreExpander.onExpandAttemptResult(
            pkg = pkg,
            increased = false,
            oldLength = 100,
            newLength = 100
        )
        ReadMoreExpander.onExpandAttemptResult(
            pkg = pkg,
            increased = false,
            oldLength = 100,
            newLength = 100
        )
        ReadMoreExpander.onExpandAttemptResult(
            pkg = pkg,
            increased = false,
            oldLength = 100,
            newLength = 100
        )

        // الآن قاطع الدائرة مفعل لمدة 60 ثانية
        val node = AccessibilityNodeInfo.obtain().apply {
            text = "عرض المزيد"
            isClickable = true
            packageName = pkg
        }
        try {
            assertFalse(
                "Circuit breaker should block attempts for 60s",
                ReadMoreExpander.tryExpand(node)
            )
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }

    @Test
    fun actionExpand_takesPrecedenceOverStrategy5() {
        val pkg = "com.whatsapp"
        val node = AccessibilityNodeInfo.obtain().apply {
            text = "رسالة طويلة جداً ... المزيد"
            packageName = pkg
            addAction(AccessibilityNodeInfo.ACTION_EXPAND)
        }
        try {
            // يتم تنفيذه عبر Strategy 1 (SELF_EXPAND_ACTION)
            assertTrue(ReadMoreExpander.tryExpand(node))
        } finally {
            SafeNodeWalker.safeRecycle(node)
        }
    }
}
