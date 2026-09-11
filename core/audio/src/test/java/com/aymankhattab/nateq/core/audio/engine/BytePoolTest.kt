package com.aymankhattab.nateq.core.audio.engine

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

    @Test
    fun `clear releases every retained reference`() {
        // منع التسريب بعد الإفراغ: لا يُسلَّم أيٌّ من المراجع المحفوظة
        // سابقاً — كل طلبٍ لاحقٍ صفيفٌ جديد الهوية.
        val pool = BytePool(minRetainedSize = 64, maxCapacity = 4)
        val seeded = (0 until 4).map { ByteArray(1024) }
        seeded.forEach { pool.release(it) }
        pool.clear()
        val handed = (0 until 8).map { pool.acquire(1024) }
        for (array in handed) {
            assertTrue(
                "لا يُعاد مرجعُ الصفيف المحفوظ بعد الإفراغ",
                seeded.none { it === array }
            )
        }
    }

    @Test
    fun `concurrent acquire and release never issues a held array twice`() {
        // التزامن: صفائف متعددة مشتركة بين خيوط — لا تُسلَّم مصفوفة محجوزة
        // لخيطٍ ثانٍ، ولا تُفقد أيٌّ من المصفوفات المتداولة (لا تسريب ضمن
        // دورة القرض/الإعادة عند بقاء كل الخيوط داخل حدود السعة).
        val pool = BytePool(minRetainedSize = 64, maxCapacity = 8)
        repeat(8) { pool.release(ByteArray(1024)) }
        // مجموعة الهويات المحجوزة الآن — إضافة فاشلة تعني توزيعاً مزدوجاً.
        val held = ConcurrentHashMap.newKeySet<ByteArray>()
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (0 until 8).map {
            Thread {
                try {
                    repeat(250) {
                        val array = pool.acquire(1024)
                        check(held.add(array)) {
                            "مصفوفة محجوزة صُدرت مرة ثانية"
                        }
                        Thread.yield()
                        pool.release(array)
                        held.remove(array)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(
            "لا توزيع مزدوج ولا استثناء",
            emptyList<Throwable>(), errors
        )
        assertTrue("لا مصفوفة معلّقة في نهاية الدورة", held.isEmpty())
    }

    @Test
    fun `concurrent churn never exceeds the capacity cap`() {
        // بلوغ السعة تحت التزامن: ينفّض الاسترجاعُ أياً كان فوق السقف —
        // العدد النهائي للمحتفَظ به بعد التصريف لا يتجاوز maxCapacity أبداً
        // (لا تراكم خفي من تزاحم عمليات الإيداع).
        val cap = 4
        val pool = BytePool(minRetainedSize = 64, maxCapacity = cap)
        val sentinel: Byte = 42
        repeat(cap) { pool.release(ByteArray(1024) { sentinel }) }
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (0 until 8).map {
            Thread {
                try {
                    repeat(200) {
                        val array = pool.acquire(2048)
                        pool.release(array)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(emptyList<Throwable>(), errors)
        // التصريف: نطلب بسعة 64 فتخرج أكبر بقايا المسبح حتى نفادها،
        // ثم يُسلَّم صفيف جديد بحجم 64 بالضبط فينهي الحلقة.
        var drained = 0
        var done = false
        while (!done) {
            val array = pool.acquire(64)
            if (array.size == 64) {
                done = true
            } else {
                drained++
            }
        }
        assertTrue(
            "المحتفظ به بعد التزاحم لا يتجاوز السقف",
            drained <= cap
        )
    }
}