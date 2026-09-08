package com.aymankhattab.nateq.core.audio.announcement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * مستقبل قراءة الرسائل النصية الواردة (SMS) بالصوت.
 *
 * يقرأ وضع القراءة من الإعدادات:
 * - "off": لا يفعل شيئاً.
 * - "full": ينطق اسم المرسل ثم محتوى الرسالة كاملة.
 * - "source": ينطق اسم/رقم المرسل فقط دون المحتوى.
 *
 * يحتاج إذن RECEIVE_SMS ليستقبل رسائل SMS الواردة.
 * ملاحظة: على أندرويد 19+ لا يُسمح بتسجيل مستقبل SMS ديناميكياً،
 * لذا يجب أن يُعلن في الـ Manifest بـ exported="true".
 */
@AndroidEntryPoint
class SmsReadingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NATEQ_SMS"
        const val MODE_OFF = "off"
        const val MODE_FULL = "full"
        const val MODE_SOURCE = "source"
    }

    /** مصدر الإعدادات المحقون — كائن مشترك عبر عمليات التطبيق. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

// goAsync() يمنع Android من قتل المستقبل قبل انتهاء العمل اللاتزامني
        val pendingResult = goAsync()
        val appScope = (context.applicationContext as AnnouncementAppContext).appScope
        appScope.launch {
            try {
                val settings = settingsRepository
                val mode = settings.getSmsReadingMode()
                if (mode == MODE_OFF) return@launch
                // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
                if (!settings.isAllAnnouncementsEnabled()) return@launch

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isEmpty()) return@launch

                // نجمع نص الرسائل (SMS قد يصل مقسّماً لعدة أجزاء)
                val body = StringBuilder()
                var sender: String? = null
                for (msg in messages) {
                    if (sender == null && msg.originatingAddress != null) {
                        sender = msg.originatingAddress
                    }
                    body.append(msg.messageBody ?: "")
                }

                val displayAddress = sender ?: context.getString(R.string.sms_unknown_sender)
                val voiceId = settings.getSmsReadingVoiceId()
                val speechRate = settings.getSmsReadingRate()
                val volume = settings.getSmsReadingVolume()

                val content = body.toString().trim()
                // خصوصية قفل الشاشة: عند القفل لا يُنطق محتوى الرسالة (قد يحوي
                // كود تحقق OTP أو معلومة خاصة) بل المصدر فقط — مهما كان الوضع.
                val privacyLocked = settings.isLockScreenPrivacyEnabled()
                        && settings.isDeviceScreenLocked()
                val effectiveMode = if (privacyLocked) MODE_SOURCE else mode
                // تحديد لغة النطق من المحتوى والمرسل (افتراضي العربية عند عدم
                // وجود حروف حاسمة، مثل مرسل رقمي فقط أو رسالة فارغة).
                val dynamicText = "$displayAddress $content"
                val useArabicVoice = !dynamicText.any { it.isLetter() } ||
                    LocaleUtils.containsArabic(dynamicText)
                // القالب المخصص (إن حُدِّد) يتيح للمستخدم صياغة كلامه: {name} للمرسل و{message} للرسالة.
                val template = settings.getSmsAnnouncementTemplate()
                // فلتر رمز التحقق (OTP): إذا كشفت الرسالة كلمة تحقق مرفقة برقم
                // متجاور 4-8 خانات (كود تفعيل بنك/منصة)، لا يُنطق الرمز نفسه في
                // الأماكن العامة بل عبارة أمنية عامة — حتى مع النطق الكامل.
                val isOtp = LocaleUtils.containsOtp(content)
                val smsFrom = LocaleUtils.stringForSpeech(
                    context,
                    if (useArabicVoice) LanguageCode.AR.tag else LanguageCode.EN.tag,
                    R.string.sms_from,
                    R.string.sms_from
                ).replace("{name}", displayAddress)
                val text = if (privacyLocked) {
                    // خصوصية القفل: المصدر فقط، ولا حتى عبارة الرمز.
                    smsFrom
                } else if (isOtp) {
                    LocaleUtils.stringForSpeech(
                        context,
                        if (useArabicVoice) LanguageCode.AR.tag else LanguageCode.EN.tag,
                        R.string.sms_otp_safe,
                        R.string.sms_otp_safe
                    ).replace("{name}", displayAddress)
                } else if (template.isNotBlank()) {
                    template
                        .replace("{name}", displayAddress)
                        .replace("{message}", content.ifBlank { displayAddress })
                } else when {
                    content.isBlank() -> smsFrom
                    effectiveMode == MODE_SOURCE -> smsFrom
                    else -> "$smsFrom، $content"
                }

                // نقرر لغة النطق حسب النص الفعلي المَنطوق (المحتوى عربي أم إنجليزي)
                val isArabic = LocaleUtils.containsArabic(text)
                val locale = if (isArabic) Locale.forLanguageTag(LanguageCode.AR.tag) else Locale.forLanguageTag(LanguageCode.EN.tag)

// متحدث مشترك واحد لكل الإعلانات (يمنع تقاطع أصوات متعددة)
                val speech = AnnouncementSpeaker.getInstance(context)
                speech.resetVoice(voiceId)
                speech.speak(text, locale, speechRate, 1.0f, volume)
            } catch (t: Throwable) {
                // أي استثناء (قراءة PDU/حزمة/نطق) يُسجَّل دون إسقاط العملية
                Log.e(TAG, "فشل قراءة الرسالة النصية", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}


