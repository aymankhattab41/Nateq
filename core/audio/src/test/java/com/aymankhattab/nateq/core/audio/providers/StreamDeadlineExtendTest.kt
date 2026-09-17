package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * اختبار تمديد مهلة البثّ (مرحلة 7): بثُّ شريحةٍ بنجاح يُرجئ الـ deadline
 * فلا تُقطع كتابةُ محركٍ بطيءٍ ما زالت تتقدم، والجمودُ (لا شريحة) يُبقي
 * الأجلَ الأصلي فيتحرر المدير. منطقٌ نقي (الزمن يُمرَّر صراحةً).
 */
class StreamDeadlineExtendTest {

    @Test
    fun emittedChunk_extendsDeadlineForward() {
        val oldDeadline = 1_000L
        val now = 2_000L
        val extended = extendStreamDeadline(oldDeadline, true, now)
        assertNotEquals(oldDeadline, extended)
        assertNotEquals(now, extended)
        // الامتداد = الآن + نافذة 5 ثوانٍ (أوسع من الأجل المسبق).
        assertEquals(now + 5_000L, extended)
    }

    @Test
    fun noChunk_progressKeepsOriginalDeadline() {
        val oldDeadline = 4_000L
        val now = 9_000L
        assertEquals(
            oldDeadline,
            extendStreamDeadline(oldDeadline, false, now)
        )
    }
}