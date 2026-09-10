package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * يغطي قرار حجب رمز التحقق (OTP) في المسار العام لإشعارات التطبيقات
 * (واتساب/تلجرام/البنوك): يُحجب الرمز من النطق فقط عندما تكون حماية
 * الخصوصية مفعّلة، ويبقى النطق طبيعياً لبقية الإشعارات — نفس منطق
 * فلتر OTP الخاص بالرسائل النصية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NateqNotificationListenerTest {

    @Test
    fun `otp is masked when privacy enabled`() {
        assertTrue(
            NateqNotificationListener.shouldMaskOtp(
                true, "رمز التحقق", "123456"
            )
        )
        assertTrue(
            NateqNotificationListener.shouldMaskOtp(
                true, "Alert", "Your verification code is 456789"
            )
        )
    }

    @Test
    fun `otp is not masked when privacy disabled`() {
        assertFalse(
            NateqNotificationListener.shouldMaskOtp(
                false, "رمز التحقق", "123456"
            )
        )
        assertFalse(
            NateqNotificationListener.shouldMaskOtp(
                false, "Alert", "Your verification code is 456789"
            )
        )
    }

    @Test
    fun `plain notification is not masked`() {
        assertFalse(
            NateqNotificationListener.shouldMaskOtp(true, "أحمد", "مساء الخير")
        )
        assertFalse(
            NateqNotificationListener.shouldMaskOtp(
                true, "Ali", "See you tomorrow"
            )
        )
    }

    @Test
    fun `arabic indic digits are normalized before matching`() {
        assertTrue(
            NateqNotificationListener.shouldMaskOtp(
                true, "رمز التحقق", "٩١٤٦٥"
            )
        )
        assertTrue(
            NateqNotificationListener.shouldMaskOtp(
                true, null, "كلمة المرور ٤٦٧١٢"
            )
        )
    }

    @Test
    fun `digits without keyword are not masked`() {
        assertFalse(
            NateqNotificationListener.shouldMaskOtp(true, null, "المبلغ 123456")
        )
    }

    @Test
    fun `keyword without code is not masked`() {
        assertFalse(
            NateqNotificationListener.shouldMaskOtp(
                true, "رمز التحقق", null
            )
        )
    }

    // ===== الحد الزمني لكل حزمة (بند [10]) =====

    @Test
    fun `different packages are not rate limited against each other`() {
        // كان حدّاً عاماً واحداً: إشعارٌ من واتساب خلال 3 ثوانٍ من إشعار
        // تلجرام كان يُسقط الأخير — الآن لكل حزمة حدُّها المستقل.
        val listener = NateqNotificationListener()
        val times = HashMap<String, Long>()
        assertFalse(
            listener.isNotificationRateLimited("com.telegram", 1_000L, times)
        )
        assertFalse(
            "حزمتان مختلفتان بنفس اللحظة لا يتداخلان",
            listener.isNotificationRateLimited("com.whatsapp", 1_000L, times)
        )
    }

    @Test
    fun `same package within interval is rate limited`() {
        val listener = NateqNotificationListener()
        val times = HashMap<String, Long>()
        assertFalse(
            listener.isNotificationRateLimited("com.whatsapp", 1_000L, times)
        )
        assertTrue(
            "نفس الحزمة قبل مضي 3 ثوانٍ تُسقط",
            listener.isNotificationRateLimited("com.whatsapp", 2_000L, times)
        )
    }

    @Test
    fun `same package passes again after the interval elapses`() {
        val listener = NateqNotificationListener()
        val times = HashMap<String, Long>()
        assertFalse(
            listener.isNotificationRateLimited("com.whatsapp", 1_000L, times)
        )
        assertFalse(
            "بعد مضي 3 ثوانٍ تُسمح الحزمة نفسها من جديد",
            listener.isNotificationRateLimited("com.whatsapp", 4_001L, times)
        )
    }
}