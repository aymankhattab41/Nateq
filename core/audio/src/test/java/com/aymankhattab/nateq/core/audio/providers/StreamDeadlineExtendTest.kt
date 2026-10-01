package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * اختبار تمديد مهلة البثّ: أيّ تقدّمٍ في كتابة الملف (إصدارُ شريحةٍ أو
 * نموٌ بطيء دون شريحة) يُرجئ الـ deadline فلا تُقطع كتابةُ محركٍ ما زالت
 * تتقدم، والجمودُ يُبقي الأجلَ الأصلي فيتحرر المدير. منطقٌ نقي (الزمن
 * يُمرَّر صراحةً).
 */
class StreamDeadlineExtendTest {

    @Test
    fun progress_extendsDeadlineForward() {
        val oldDeadline = 1_000L
        val now = 2_000L
        val extended = extendStreamDeadline(oldDeadline, true, now)
        assertNotEquals(oldDeadline, extended)
        assertNotEquals(now, extended)
        // الامتداد = الآن + نافذة 5 ثوانٍ (أوسع من الأجل المسبق).
        assertEquals(now + 5_000L, extended)
    }

    @Test
    fun noProgress_keepsOriginalDeadline() {
        val oldDeadline = 4_000L
        val now = 9_000L
        assertEquals(
            oldDeadline,
            extendStreamDeadline(oldDeadline, false, now)
        )
    }
}