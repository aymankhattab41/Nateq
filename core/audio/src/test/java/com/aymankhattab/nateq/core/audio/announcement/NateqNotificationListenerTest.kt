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
@Config(sdk = [37])
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

    // ===== بند ١٨: الحد لكل محادثة/رسالة لا للحزمة =====

    @Test
    fun `different messages in same package are not dropped`() {
        // كان الحد على مستوى الحزمة فيُسقط رسالة أحمد حين تصل بعد
        // رسالة محمد بثانية واحدة في نفس تطبيق المراسلة.
        val listener = NateqNotificationListener()
        val times = HashMap<String, Long>()
        val first = NateqNotificationListener.notificationIdentityKey(
            "com.whatsapp", "محمد", "مرحبا", 1
        )
        val second = NateqNotificationListener.notificationIdentityKey(
            "com.whatsapp", "أحمد", "مساء الخير", 2
        )
        assertFalse(listener.isNotificationRateLimited(first, 1_000L, times))
        assertFalse(
            "رسالة مختلفة من شخص آخر لا تُسقط",
            listener.isNotificationRateLimited(second, 1_100L, times)
        )
    }

    @Test
    fun `different text in same conversation is not dropped`() {
        val listener = NateqNotificationListener()
        val times = HashMap<String, Long>()
        val first = NateqNotificationListener.notificationIdentityKey(
            "com.telegram", "أحمد", "رسالة أولى", 7
        )
        val second = NateqNotificationListener.notificationIdentityKey(
            "com.telegram", "أحمد", "رسالة ثانية مختلفة", 7
        )
        assertFalse(listener.isNotificationRateLimited(first, 1_000L, times))
        assertFalse(
            "نص مختلف في نفس المحادثة لا يُسقط",
            listener.isNotificationRateLimited(second, 1_200L, times)
        )
    }

    @Test
    fun `identical message within interval is still dropped`() {
        val listener = NateqNotificationListener()
        val times = HashMap<String, Long>()
        val key = NateqNotificationListener.notificationIdentityKey(
            "com.whatsapp", "أحمد", "نفس الرسالة", 5
        )
        assertFalse(listener.isNotificationRateLimited(key, 1_000L, times))
        assertTrue(
            "تكرار نفس الرسالة خلال 3 ثوانٍ يُسقط",
            listener.isNotificationRateLimited(key, 2_000L, times)
        )
    }
}