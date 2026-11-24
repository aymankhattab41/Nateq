package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

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

    @Test
    fun messagingStyle_updateFromAToAB_speaksOnlyB() {
        val context = org.robolectric.RuntimeEnvironment.application
        val person = Person.Builder().setName("أحمد").build()
        val convKey = "com.pkg#1#tag#conv1"

        NateqNotificationListener.MessagingStyleTracker.clear()

        val style1 = NotificationCompat.MessagingStyle(person)
            .addMessage("رسالة أ", 1_000L, person)
        val notif1 = NotificationCompat.Builder(context, "c")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style1)
            .build()
        val text1 = NateqNotificationListener.notificationBodyText(
            notif1, convKey
        )
        assertEquals("رسالة أ", text1)

        val style2 = NotificationCompat.MessagingStyle(person)
            .addMessage("رسالة أ", 1_000L, person)
            .addMessage("رسالة ب", 2_000L, person)
        val notif2 = NotificationCompat.Builder(context, "c")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style2)
            .build()
        val text2 = NateqNotificationListener.notificationBodyText(
            notif2, convKey
        )
        assertEquals("رسالة ب", text2)
    }

    @Test
    fun messagingStyle_twoConversationsInSameApp_independentTracking() {
        val context = org.robolectric.RuntimeEnvironment.application
        val personA = Person.Builder().setName("أحمد").build()
        val personS = Person.Builder().setName("سارة").build()

        NateqNotificationListener.MessagingStyleTracker.clear()

        val styleA = NotificationCompat.MessagingStyle(personA)
            .addMessage("رسالة أحمد", 1_000L, personA)
        val notifA = NotificationCompat.Builder(context, "c")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(styleA)
            .build()

        val styleS = NotificationCompat.MessagingStyle(personS)
            .addMessage("رسالة سارة", 1_500L, personS)
        val notifS = NotificationCompat.Builder(context, "c")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(styleS)
            .build()

        val textA = NateqNotificationListener.notificationBodyText(
            notifA, "app#1#null#ahmed"
        )
        val textS = NateqNotificationListener.notificationBodyText(
            notifS, "app#2#null#sarah"
        )

        assertEquals("رسالة أحمد", textA)
        assertEquals("رسالة سارة", textS)
    }

    @Test
    fun smsNlsDeduplicator_dropsDuplicateRepost_allowsNewContent() {
        NateqNotificationListener.SmsNlsDeduplicator.clear()

        val drop1 = NateqNotificationListener.SmsNlsDeduplicator.shouldDrop(
            notificationKey = "sms_key_1",
            notificationId = 101,
            sender = "+201000000000",
            content = "مرحباً",
            nowMs = 1_000L
        )
        assertFalse(drop1)

        val dropDuplicate = NateqNotificationListener.SmsNlsDeduplicator
            .shouldDrop(
                notificationKey = "sms_key_1",
                notificationId = 101,
                sender = "+201000000000",
                content = "مرحباً",
                nowMs = 2_000L
            )
        assertTrue(dropDuplicate)

        val dropNewContent = NateqNotificationListener.SmsNlsDeduplicator
            .shouldDrop(
                notificationKey = "sms_key_1",
                notificationId = 101,
                sender = "+201000000000",
                content = "أين أنت؟",
                nowMs = 3_000L
            )
        assertFalse(dropNewContent)
    }

    @Test
    fun smsReading_unifiedPrecedence_sourceOnlyIgnoresTemplateWithContent() {
        val text = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false,
            isOtp = false,
            smsFrom = "رسالة من أحمد",
            otpSafeText = "رمز تحقق آمن",
            template = "{name}: {message}",
            content = "نص سري للغاية",
            displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_SOURCE
        )
        assertEquals("رسالة من أحمد", text)

        val textFull = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false,
            isOtp = false,
            smsFrom = "رسالة من أحمد",
            otpSafeText = "رمز تحقق آمن",
            template = "{name}: {message}",
            content = "نص علني",
            displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("أحمد: نص علني", textFull)

        val textLocked = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = true,
            isOtp = false,
            smsFrom = "رسالة من أحمد",
            otpSafeText = "رمز تحقق آمن",
            template = "{name}: {message}",
            content = "نص سري",
            displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("رسالة من أحمد", textLocked)
    }
}