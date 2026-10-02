package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارسُ خفض الرنين أثناء إعلان المتصل: حسابٌ خالصٌ بلا نظام صوت.
 *
 * الاسمُ في كل اختبار = الكسرُ الذي يحرسه (قاعدة «حارس لكل عقد»):
 * - `never drops more than` يحرس ألّا يفوق الخفضُ النسبةَ التي يقولها
 *   الشريط (القرضُ للأدنى لا للأعلى).
 * - `never reaches zero` يمنع خفضَ الرنين إلى الصمت (يبدو الهاتفُ
 *   صامتاً).
 * - `never rises above` يمنع «الخفض» أن يكون رفعاً (النسبةُ فوق 100
 *   كانت تكذب على المستخدم).
 * - `bounds agree` يمنع مدىً ميتاً في الواجهة لا في المخزن.
 */
class RingtoneDuckMathTest {

    @Test
    fun `ducked level never drops more than the promised percentage`() {
        // 7 عند 40% = 2.8 ⇒ القرضُ للأدنى 2. والتقريبُ للأعلى 3 يعني
        // خفضاً قدره 43% بلا أن يقوله الشريط.
        assertEquals(
            "الخفض لا يتجاوز النسبة التي يَعِد بها الشريط",
            2,
            RingtoneDuckMath.duckedLevel(current = 7, max = 15, percent = 40)
        )
    }

    @Test
    fun `ducked level never reaches zero silence`() {
        assertEquals(
            "صفرٌ يعني صمتاً تاماً فيبدو الهاتفُ صامتاً لحظة إعلانه",
            1,
            RingtoneDuckMath.duckedLevel(current = 1, max = 15, percent = 10)
        )
        assertEquals(
            "الحد الأدنى 1 حتى مع نسبةٍ صغيرة جداً",
            1,
            RingtoneDuckMath.duckedLevel(current = 2, max = 7, percent = 10)
        )
    }

    @Test
    fun `ducked level never rises above the original`() {
        assertEquals(
            "نسبةٌ فوق 100 لا ترفع الرنين بل تُبقيه",
            5,
            RingtoneDuckMath.duckedLevel(current = 5, max = 15, percent = 100)
        )
        assertEquals(
            "ونسبةٌ خارج المدى تُقصّ إلى 100",
            5,
            RingtoneDuckMath.duckedLevel(current = 5, max = 15, percent = 250)
        )
    }

    @Test
    fun `zero ringtone and unknown max stay silent and untouched`() {
        assertEquals(
            "رنّةٌ أصلاً معدومة: لا خفض",
            0,
            RingtoneDuckMath.duckedLevel(current = 0, max = 15, percent = 40)
        )
        assertEquals(
            "قناةٌ بلا قمة معروفة: لا خفض",
            0,
            RingtoneDuckMath.duckedLevel(current = 6, max = 0, percent = 40)
        )
    }

    @Test
    fun `bounds agree across repository and consumer`() {
        assertEquals(10, SettingsRepository.CALLER_RING_DUCK_MIN)
        assertEquals(90, SettingsRepository.CALLER_RING_DUCK_MAX)
        assertTrue(
            "الافتراضي داخل المدى",
            SettingsRepository.CALLER_RING_DUCK_DEFAULT in
                SettingsRepository.CALLER_RING_DUCK_MIN..
                SettingsRepository.CALLER_RING_DUCK_MAX
        )
        // أقصى نسبة 90 عند أقصى مستوى: النتيجةُقابلةٌ للوصول ولا تتجاوز
        // القمة أبداً.
        val top = RingtoneDuckMath.duckedLevel(
            current = 15, max = 15,
            percent = SettingsRepository.CALLER_RING_DUCK_MAX
        )
        assertTrue("لا تجاوز للقمة: $top", top in 1..15)
    }
}