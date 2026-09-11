package com.aymankhattab.nateq.core.audio.announcement

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** اختبار منطق قراءة الرسائل الصوتية (بلا شبكة Hilt): دمج الأجزاء، فروع
 *  الوضع والخصوصية والتحقق OTP والقالب، اكتشاف لغة النطق، وحراسة الإذن —
 *  كلها طبقات نقيّة من كائن SmsReadingReceiver. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsReadingReceiverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    // ===== دمج الأجزاء =====

    @Test
    fun `combineParts merges multipart messages with the first sender`() {
        val parts = listOf(
            "+966501234567" to "مرحباً هذا ",
            null to "الجزء الثاني",
            "+966999999999" to " والثالث"
        )
        val (sender, body) = SmsReadingReceiver.combineParts(parts)
        assertEquals("+966501234567", sender)
        assertEquals("مرحباً هذا الجزء الثاني والثالث", body)
    }

    @Test
    fun `combineParts yields null sender and empty body for blank parts`() {
        val (sender, body) = SmsReadingReceiver.combineParts(
            listOf<Pair<String?, String>>(null to "", null to "")
        )
        assertNull(sender)
        assertEquals("", body)
    }

    // ===== وضع الخصوصية على القفل =====

    @Test
    fun `resolveEffectiveMode forces source when screen is locked`() {
        assertEquals(
            "الخصوصية تُزنّ حتى النطق الكامل",
            SmsReadingReceiver.MODE_SOURCE,
            SmsReadingReceiver.resolveEffectiveMode(
                SmsReadingReceiver.MODE_FULL, privacyLocked = true
            )
        )
        assertEquals(
            SmsReadingReceiver.MODE_SOURCE,
            SmsReadingReceiver.resolveEffectiveMode(
                SmsReadingReceiver.MODE_SOURCE, privacyLocked = true
            )
        )
        assertEquals(
            SmsReadingReceiver.MODE_FULL,
            SmsReadingReceiver.resolveEffectiveMode(
                SmsReadingReceiver.MODE_FULL, privacyLocked = false
            )
        )
    }

    // ===== اكتشاف لغة النطق =====

    @Test
    fun `resolveUseArabicVoice prefers arabic on arabic content`() {
        assertTrue(
            SmsReadingReceiver.resolveUseArabicVoice(
                "+966501234567", "مرحباً بالعالم"
            )
        )
    }

    @Test
    fun `resolveUseArabicVoice prefers arabic when no decisive letters`() {
        // مرسل رقمي فقط + رسالة أرقام — افتراضي العربية (بلا حروف حاسمة).
        assertTrue(
            SmsReadingReceiver.resolveUseArabicVoice(
                "966501234567", "1234 5678"
            )
        )
    }

    @Test
    fun `resolveUseArabicVoice prefers english on latin letters`() {
        assertFalse(
            SmsReadingReceiver.resolveUseArabicVoice(
                "Bank", "Your code is 1234"
            )
        )
    }

    // ===== بناء النص المَنطوق =====

    @Test
    fun `resolveSpeechText hides everything but source when locked`() {
        val text = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = true,
            isOtp = true,
            smsFrom = "من البنك",
            otpSafeText = "رمزٌ آمن",
            template = "{name}: {message}",
            content = "كود 1234 سري",
            displayAddress = "البنك",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("من البنك", text)
    }

    @Test
    fun `resolveSpeechText uses the safe phrase for otp codes`() {
        val text = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false,
            isOtp = true,
            smsFrom = "من البنك",
            otpSafeText = "رمزٌ آمن",
            template = "",
            content = "كود التحقق 1234",
            displayAddress = "البنك",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("رمزٌ آمن", text)
    }

    @Test
    fun `resolveSpeechText applies the custom template`() {
        val text = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false,
            isOtp = false,
            smsFrom = "من أحمد",
            otpSafeText = "رمزٌ آمن",
            template = "قال {name}: {message}",
            content = "بنسلفيك",
            displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("قال أحمد: بنسلفيك", text)
    }

    @Test
    fun `resolveSpeechText replaces blank message with the name in template`() {
        val text = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false,
            isOtp = false,
            smsFrom = "من أحمد",
            otpSafeText = "رمزٌ آمن",
            template = "{name}: {message}",
            content = "   ",
            displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("أحمد: أحمد", text)
    }

    @Test
    fun `resolveSpeechText falls back to source for blank or source mode`() {
        val blank = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false, isOtp = false,
            smsFrom = "من أحمد", otpSafeText = "رمزٌ آمن", template = "",
            content = "  ", displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("من أحمد", blank)

        val source = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false, isOtp = false,
            smsFrom = "من أحمد", otpSafeText = "رمزٌ آمن", template = "",
            content = "رسالة كاملة", displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_SOURCE
        )
        assertEquals("من أحمد", source)
    }

    @Test
    fun `resolveSpeechText announces the message in full mode`() {
        val text = SmsReadingReceiver.resolveSpeechText(
            privacyLocked = false, isOtp = false,
            smsFrom = "من أحمد", otpSafeText = "رمزٌ آمن", template = "",
            content = "رسالة كاملة", displayAddress = "أحمد",
            effectiveMode = SmsReadingReceiver.MODE_FULL
        )
        assertEquals("من أحمد، رسالة كاملة", text)
    }

    // ===== حراسة الإذن =====

    @Test
    fun `canReadMessages requires receive or read sms permission`() {
        val app =
            ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).denyPermissions(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        assertFalse(
            "بلا أذونات لا تُقرأ الرسائل",
            SmsReadingReceiver.canReadMessages(context)
        )
        shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS)
        assertTrue(
            "قبول الوارد يكفي للنطق",
            SmsReadingReceiver.canReadMessages(context)
        )
        shadowOf(app).denyPermissions(Manifest.permission.RECEIVE_SMS)
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        assertTrue(
            "قبول القراءة كافٍ أيضاً",
            SmsReadingReceiver.canReadMessages(context)
        )
    }
}