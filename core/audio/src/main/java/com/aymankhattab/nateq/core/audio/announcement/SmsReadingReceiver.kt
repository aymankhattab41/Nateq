package com.aymankhattab.nateq.core.audio.announcement

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        // **بند 5.5:** سقف احتجاز نافذة goAsync حتى تمام النطق — لا أطول
        // من نافذة البث الآمنة (~10 ثوانٍ) فلا ANR إن طال التوليف.
        private const val BROADCAST_HOLD_MS = 10_000L

        /** هل مَنح التطبيق إذن قراءة الرسائل الواردة؟
         *  (RECEIVE_SMS أو READ_SMS). على ما قبل أندرويد 6 لا أخطارِ
         *  إذنٍ وقت التشغيل — يُعدّ ممنوحاً. */
        @JvmStatic
        internal fun canReadMessages(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val recvGranted =
                context.checkSelfPermission(
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
            val readGranted =
                context.checkSelfPermission(
                    Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
            return recvGranted || readGranted
        }

        /** يجمع أجزاء (مرسل، نص) في (مرسل، نص) واحد للرسالة المقسّمة —
         *  طبقة نقية تُفحص في الاختبارات؛ المرسل من أول جزء يملكه. */
        @JvmStatic
        internal fun combineParts(
            parts: List<Pair<String?, String>>
        ): Pair<String?, String> {
            val body = StringBuilder()
            var sender: String? = null
            for ((partSender, partBody) in parts) {
                if (sender == null && partSender != null) {
                    sender = partSender
                }
                body.append(partBody)
            }
            return sender to body.toString()
        }

        /** يجمع أجزاء الرسالة (SMS مقسّم) في نص واحد مع المرسل من أول جزء. */
        @JvmStatic
        internal fun combineMessages(
            messages: List<SmsMessage>
        ): Pair<String?, String> = combineParts(
            messages.map { it.originatingAddress to (it.messageBody ?: "") }
        )

        /** خصوصية القفل تُزنّ الوضع إلى المصدر مهما كان الوضع المختار. */
        @JvmStatic
        internal fun resolveEffectiveMode(
            mode: String,
            privacyLocked: Boolean
        ): String = if (privacyLocked) MODE_SOURCE else mode

        /** افتراض العربية عند غياب الحروف أو وجود حروف عربية في
         *  (المرسل + المحتوى) — يرث اختيار نطق «من» القالب. */
        @JvmStatic
        internal fun resolveUseArabicVoice(
            displayAddress: String,
            content: String
        ): Boolean {
            val dynamicText = "$displayAddress $content"
            return !dynamicText.any { it.isLetter() } ||
                LocaleUtils.containsArabic(dynamicText)
        }

        /** يبني النص المَنطوق حسب الأولويات: خصوصية القفل، ثم فلتر التحقق
         *  OTP، ثم القالب المخصص، ثم التراجعات (فارغ/مصدر/كامل). */
        @JvmStatic
        internal fun resolveSpeechText(
            privacyLocked: Boolean,
            isOtp: Boolean,
            smsFrom: String,
            otpSafeText: String,
            template: String,
            content: String,
            displayAddress: String,
            effectiveMode: String
        ): String = when {
            privacyLocked -> smsFrom
            isOtp -> otpSafeText
            template.isNotBlank() -> template
                .replace("{name}", displayAddress)
                .replace("{message}", content.ifBlank { displayAddress })
            content.isBlank() -> smsFrom
            effectiveMode == MODE_SOURCE -> smsFrom
            else -> "$smsFrom، $content"
        }
    }

    /** مصدر الإعدادات المحقون — كائن مشترك عبر عمليات التطبيق. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // حراسة الإذن: سحب RECEIVE_SMS (أو READ_SMS) يُسكّت القراءة — لا
        // يُقرأ المحتوى ولا يُفتح أي مورد دون صلاحية.
        if (!canReadMessages(context)) {
            Log.w(TAG, "SMS permission missing; skipping announcement")
            return
        }

// goAsync() يمنع Android من قتل المستقبل قبل انتهاء العمل اللاتزامني
        val pendingResult = goAsync()
        val appScope =
            (context.applicationContext as AnnouncementAppContext).appScope
        appScope.launch {
            // حارس إنهاء وحيد لدورة البث — ذرّيٌ ليتحمل وصولَ الإنهاء من
            // خيطي اللا-تكرار واكتمال النطق معاً (finishٌ مكررٌ تحذير بلا
            // لزوم).
            val finishedBroadcast = AtomicBoolean(false)
            fun finishOnce() {
                if (finishedBroadcast.compareAndSet(false, true)) {
                    pendingResult.finish()
                }
            }
            var completionListener: (() -> Unit)? = null
            try {
                val settings = settingsRepository
                val mode = settings.getSmsReadingMode()
                if (mode == MODE_OFF) return@launch
                // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
                if (!settings.isAllAnnouncementsEnabled()) return@launch

                val messages =
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isEmpty()) return@launch
                // نجمع نص الرسائل (SMS قد يصل مقسّماً لعدة أجزاء)
                val (sender, body) = combineMessages(messages.toList())

                val displayAddress =
                    sender ?: context.getString(R.string.sms_unknown_sender)
                val voiceId = settings.getSmsReadingVoiceId()
                val speechRate = settings.getSmsReadingRate()
                val volume = settings.getSmsReadingVolume()

                val content = body.trim()
                // خصوصية قفل الشاشة: عند القفل لا يُنطق محتوى الرسالة (قد يحوي
                // كود تحقق OTP أو معلومة خاصة) بل المصدر فقط — مهما كان الوضع.
                val privacyLocked = settings.isLockScreenPrivacyEnabled()
                    && settings.isDeviceScreenLocked()
                val effectiveMode =
                    resolveEffectiveMode(mode, privacyLocked)
                // تحديد لغة النطق من المحتوى والمرسل (افتراضي العربية عند عدم
                // وجود حروف حاسمة، مثل مرسل رقمي فقط أو رسالة فارغة).
                val useArabicVoice =
                    resolveUseArabicVoice(displayAddress, content)
                // القالب المخصص (إن حُدِّد) يتيح للمستخدم صياغة كلامه:
                // {name} للمرسل و{message} للرسالة.
                val template = settings.getSmsAnnouncementTemplate()
                // فلتر رمز التحقق (OTP): إذا كشفت الرسالة كلمة تحقق مرفقة برقم
                // متجاور 4-8 خانات (كود تفعيل بنك/منصة)، لا يُنطق الرمز
                // نفسه في الأماكن العامة بل عبارة أمنية عامة —
                // حتى مع النطق الكامل.
                val isOtp = LocaleUtils.containsOtp(content)
                val speechLang = if (useArabicVoice) LanguageCode.AR.tag
                else LanguageCode.EN.tag
                val smsFrom = LocaleUtils.stringForSpeech(
                    context, speechLang,
                    R.string.sms_from, R.string.sms_from
                ).replace("{name}", displayAddress)
                val otpSafeText = LocaleUtils.stringForSpeech(
                    context, speechLang,
                    R.string.sms_otp_safe, R.string.sms_otp_safe
                ).replace("{name}", displayAddress)
                val text = resolveSpeechText(
                    privacyLocked = privacyLocked,
                    isOtp = isOtp,
                    smsFrom = smsFrom,
                    otpSafeText = otpSafeText,
                    template = template,
                    content = content,
                    displayAddress = displayAddress,
                    effectiveMode = effectiveMode
                )

                // نقرر لغة النطق حسب النص الفعلي المَنطوق
                // (المحتوى عربي أم إنجليزي)
                val isArabic = LocaleUtils.containsArabic(text)
                val locale =
                    if (isArabic) Locale.forLanguageTag(LanguageCode.AR.tag)
                    else Locale.forLanguageTag(LanguageCode.EN.tag)

// متحدث مشترك واحد لكل الإعلانات (يمنع تقاطع أصوات متعددة)
                val speech = AnnouncementSpeaker.getInstance(context)
                speech.resetVoice(voiceId)
                speech.speak(text, locale, speechRate, 1.0f, volume)
                // **بند 5.5:** finish() الفوري قبل تمام التوليف كان يترك
                // أندرويد 14+ يجمد العملية عبر Process Cgroup Freezer
                // فيُبتر صوت الرسالة في منتصف الجملة. نُبقي النافذة حيّةً
                // حتى يُتمَّ النطق الفعلي (بسقف ~10 ثوانٍ فلا ANR) —
                // نمط قارئ المتصل/البطارية نفسه، بسجل ومستمعين فريدين.
                completionListener = { finishOnce() }
                AnnouncementSpeaker.getInstance(context)
                    .addCompletionListener(completionListener!!)
                delay(BROADCAST_HOLD_MS)
                finishOnce()
            } catch (t: Throwable) {
                // أي استثناء (قراءة PDU/حزمة/نطق) يُسجَّل دون إسقاط العملية
                // والإلغاء (نهاية نافذة البث من النظام) يُمرَّر صامتاً.
                if (t is kotlinx.coroutines.CancellationException) {
                    throw t
                }
                Log.e(TAG, "فشل قراءة الرسالة النصية", t)
            } finally {
                // تنظيفٌ تعويضي: يُزال مستمعُنا (لا يُستدعى في دورةٍ لاحقة
                // لا تخصنا) ويُنهى البث — وإن سبق إنهاؤه فلا يُنهى ثانية.
                completionListener?.let { listener ->
                    runCatching {
                        AnnouncementSpeaker.getInstance(context)
                            .removeCompletionListener(listener)
                    }
                }
                finishOnce()
            }
        }
    }
}


