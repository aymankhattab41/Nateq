package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبار السعة الافتراضية وسلوك الاحتفاظ لمسبح [BytePool] (JVM نقي). */
class BytePoolTest {

    @Test
    fun `defaultCapacitiesCoverMultilingualSpeech`() {
        // السعة الافتراضية الجديدة 12 (بند توسيع المسبح): يجب ألا تُرفض
        // المصفوفات المبكرة على المسبح الافتراضي قبل بلوغها.
        val pool = BytePool()
        val accepted = (0 until 12).map {
            pool.release(ByteArray(4096))
        }
        assertEquals(12, accepted.size)
        assertTrue(accepted.all { it })
        // الثالثة عشرة تُرفض (بلوغ السعة الافتراضية).
        assertFalse(pool.release(ByteArray(4096)))
    }

    @Test
    fun `acquireReusesRetainedLargeArrays`() {
        val pool = BytePool(minRetainedSize = 64, maxCapacity = 2)
        val first = pool.acquire(4096)
        pool.release(first)
        val second = pool.acquire(4096)
        assertTrue(second === first) // إعادة استخدام غير حرفية (بأكبر حجماً).
    }

    @Test
    fun `smallArraysAreNotRetained`() {
        val pool = BytePool(minRetainedSize = 64, maxCapacity = 2)
        val small = ByteArray(32)
        assertFalse(pool.release(small))
        // رفض الاحتفاظ يعني أن المسبح ما زال فارغاً.
        val fresh = pool.acquire(64)
        assertTrue(fresh.size >= 64)
    }

    @Test
    fun `acquireBelowRetentionThresholdReturnsFresh`() {
        val pool = BytePool(minRetainedSize = 64, maxCapacity = 2)
        val tiny = pool.acquire(16)
        assertEquals(16, tiny.size)
    }

    @Test
    fun `clearEmptiesThePool`() {
        val pool = BytePool(minRetainedSize = 64, maxCapacity = 2)
        pool.release(ByteArray(128))
        pool.clear()
        // بعد الإفراغ يُقبل إيداع جديد كأن المسبح بدأ فارغاً.
        assertTrue(pool.release(ByteArray(128)))
    }
}