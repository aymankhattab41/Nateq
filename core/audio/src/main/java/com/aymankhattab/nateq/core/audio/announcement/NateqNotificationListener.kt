package com.aymankhattab.nateq.core.audio.announcement

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import com.aymankhattab.nateq.util.LanguageCode
import dagger.hilt.android.AndroidEntryPoint
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * خدمة الاستماع للإشعارات — تقرأ إشعارات التطبيقات المهمة
 * (واتساب، تلجرام، إلخ) بالصوت.
 * يجب منح الإذن يدوياً من: الإعدادات ← التطبيقات الخاصة ← الوصول للإشعارات.
 */
@AndroidEntryPoint
class NateqNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NATEQ_NOTIF"

        // سقفُ عدد الحزم المتتبَّعة في نافذة منع التكرار (بند [10]) — كل إصدار
        // يتحدث قائمة الحزم الشائعة، والحزم الجديدة تتسرب القديمة (LRU).
        private const val MAX_TRACKED_NOTIF_PACKAGES = 64

        /** التحقق مما إذا كان التطبيق لديه إذن الاستماع للإشعارات. */
        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(
                context, NateqNotificationListener::class.java
            )
            return flat.contains(cn.flattenToString())
        }

        /** هل يحوي عنوان/نص الإشعار رمز تحقق سري ينبغي حجبه؟ يُطبَّق فقط مع
         * تفعيل حماية الخصوصية — نفس منطق فلتر OTP الخاص بالرسائل النصية. */
        fun shouldMaskOtp(
            privacyEnabled: Boolean,
            title: String?,
            text: String?
        ): Boolean = privacyEnabled && LocaleUtils.containsOtp(
            "${title.orEmpty()} ${text.orEmpty()}".trim()
        )

        /** استخراج نص الإشعار بترتيب سقوط: EXTRA_TEXT ثم نصّ الموسّع
         *  EXTRA_BIG_TEXT (إشعارات واتساب/أميل متعددة الأسطر) ثم أسطر
         *  EXTRA_TEXT_LINES مربوطة — حتى لا تُفقد رسالة طويلة النص
         *  (تحسين [بند 26]). */
        fun notificationBodyText(extras: android.os.Bundle): String? {
            val bigText = extras.getCharSequence(
                Notification.EXTRA_BIG_TEXT
            )?.toString()?.trim()
            val textLines = extras.getCharSequenceArray(
                Notification.EXTRA_TEXT_LINES
            )?.filterNotNull()?.joinToString("\n")
            return extras.getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: bigText?.takeIf { it.isNotEmpty() }
                ?: textLines?.takeIf { it.isNotEmpty() }
        }
    }

    /** مصدر الإعدادات المحقون — نفس كائن عملية المحرك المُدار من Hilt. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val minIntervalMs = 3000L // الحد الأدنى لإشعارات نفس التطبيق

    // الحد الزمني يُطبَّق لكل حزمة على حدة (بند [10]): إشعاران من تطبيقين
    // مختلفين خلال 3 ثوانٍ لا يُسقط أحدهما الآخر (كان حدّاً عاماً واحداً
    // يُفقد إشعاراً مهماً تصادفَ بعد أي إشعارٍ آخر). متزمِّنة للأمان،
    // LRU (accessOrder=true) لسقفِ الحزم المتتالية.
    private val lastNotifTimes = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Long>
            ): Boolean = size > MAX_TRACKED_NOTIF_PACKAGES
        }
    )

    /** هل الإشعار من هذه الحزمة مسقطُ الشفرة (وصل إشعارٌ سابق خلال
     *  [minIntervalMs])؟ فحصٌ والتحديث معاً تحت نفس القفل — القابلية
     *  للاختبار بحقن خريطة (بند [10]). */
    internal fun isNotificationRateLimited(
        pkg: String,
        now: Long,
        times: MutableMap<String, Long> = lastNotifTimes
    ): Boolean {
        return synchronized(times) {
            val last = times[pkg]
            if (last == null) {
                // أول إشعار من هذه الحزمة: مسموح دوماً ويُسجَّل وقتُه.
                times[pkg] = now
                false
            } else {
                val limited = (now - last) < minIntervalMs
                if (!limited) times[pkg] = now
                limited
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        // كل المعالجة (قراءات الإعدادات، استعلام تطبيق الرسائل الافتراضي،
        // إطارات الواجهة Keyguard، فحص الاستثناءات، النطق) تُنفَّذ على
        // appScope (Io) — استدعاء NLS يأتي على خيط الخدمة الرئيسي وكانت
        // عمليات قرص و IPC متزامنة عليه تسبب إسقاط إطارات مع وصول كثيف.
        (applicationContext as AnnouncementAppContext).appScope.launch {
            try {
                onNotificationPostedWorker(sbn)
            } catch (t: Throwable) {
                Log.e(TAG, "onNotificationPosted failed", t)
            }
        }
    }

    private fun onNotificationPostedWorker(sbn: StatusBarNotification) {
        try {
            val pkg = sbn.packageName ?: return

            val settings = settingsRepository
            if (!settings.isNotificationReadingEnabled()) return
            // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
            if (!settings.isAllAnnouncementsEnabled()) return

            // إشعار من تطبيق الرسائل النصية مع قراءة SMS مفعّلة:
            // يُعالج بمسار SMS المستقل (قبل فحص قائمة تطبيقات قراءة الإشعارات
            // العادية) لأنه ميزة منفصلة لها إعداداتها الخاصة.
            // - إن كان RECEIVE_SMS ممنوحاً فسيَنطقه SmsReadingReceiver
            //   مباشرة (نتجنب هنا).
            // - إن لم يكن ممنوحاً نقرأ الرسالة عبر خدمة الاستماع
            //   للإشعارات (NLS) بإعدادات SMS المتخصصة
            //   (الصوت/السرعة/الخصوصية/فلتر OTP) بدل الإذن المقيّد.
            val defaultSmsApp = runCatching {
                android.provider.Telephony.Sms
                    .getDefaultSmsPackage(applicationContext)
            }.getOrNull()
            val isSmsApp = pkg == defaultSmsApp
            val smsMode = settings.getSmsReadingMode()
            if (isSmsApp && smsMode != SmsReadingReceiver.MODE_OFF) {
                val hasSmsPermission = ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
                if (hasSmsPermission) return
                handleSmsNotification(sbn, settings, smsMode)
                return
            }

            // قراءة التطبيقات المختارة فقط (للمستخدم حرية اختيار قائمتها).
            if (!settings.shouldReadNotificationApp(pkg)) return

            // تجنب إسقاط الإشعارات المتتالية بشكل سريع — لكل حزمة حدُّها
            // المستقل (بند [10]) فلا يُسقط إشعارُ تطبيقٍ إشعارَ تطبيقٍ آخر.
            if (isNotificationRateLimited(pkg, System.currentTimeMillis())) {
                return
            }

            val notification = sbn.notification ?: return
            val extras = notification.extras

val title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()
        val text = notificationBodyText(extras)

            if (title.isNullOrBlank() && text.isNullOrBlank()) return

            val appName = getAppName(pkg)
            // خصوصية قفل الشاشة: عند القفل يُنطق اسم التطبيق فقط دون العنوان
            // والنص (حماية لكلمات تحقق OTP وغيرها من الحساسيات في الإشعارات).
            val privacyEnabled = settings.isLockScreenPrivacyEnabled()
            val privacyLocked =
                privacyEnabled && settings.isDeviceScreenLocked()
            // فلتر OTP الشامل (حماية الخصوصية مفعّلة): كلُّ الإشعارات لا مسار
            // تطبيق الرسائل فقط — واتساب/تلجرام/البنوك تُرسل رموز تحقق تُنطق
            // علناً دون حجب. يُنطق بدل الرمز عبارة آمنة عامة (نمط حماية SMS).
            val isOtp = shouldMaskOtp(
                privacyEnabled, title, text
            )
            val speechText = if (isOtp) {
                val appLang = if (LocaleUtils.containsArabic(appName)) {
                    LanguageCode.AR.tag
                } else {
                    LanguageCode.EN.tag
                }
                LocaleUtils.stringForSpeech(
                    applicationContext, appLang,
                    R.string.notif_otp_safe, R.string.notif_otp_safe
                ).replace("{app}", appName)
            } else {
                buildSpeechText(appName, title, text, privacyLocked)
            }
            val isArabic = LocaleUtils.containsArabic(speechText)
            val locale = if (isArabic) {
                Locale.forLanguageTag(LanguageCode.AR.tag)
            } else {
                Locale.forLanguageTag(LanguageCode.EN.tag)
            }

            // سجلّ مجرّد من مضمون الإشعار (قد يحوي OTP/حساسيات)
            // — الطول والحزمة فقط.
            Log.d(TAG, "Notification from $pkg: ${speechText.length} chars")

            // احترام إعدادات فئة "صوت الإشعارات"
            // (سرعته/نبرته/مستواه) بدل ثوابت 1.0
            val speechRate = settings.getSpeechRateForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            val pitch = settings.getPitchForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            val volume = settings.getVolumeForCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            AnnouncementSpeaker.getInstance(applicationContext)
                .speak(speechText, locale, speechRate, pitch, volume)

        } catch (t: Throwable) {
            Log.e(TAG, "onNotificationPosted failed", t)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // لا نفعل شيئاً عند حذف الإشعار
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AnnouncementSpeaker.getInstance(applicationContext).stop()
        Log.w(TAG, "NotificationListener disconnected — محاولة إعادة الربط")
        // إعادة الربط التلقائي بعد فصل النظام (توفير الطاقة/إيقاف مؤقت)
        // حتى لا تتوقف قراءة الإشعارات دون تدخل المستخدم.
        try {
            requestRebind(
                ComponentName(this, NateqNotificationListener::class.java)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "requestRebind failed", t)
        }
    }

    /** قراءة الرسائل النصية الواردة عبر إشعار تطبيق الرسائل
     * (بديل NLS بدل إذن RECEIVE_SMS). */
    private fun handleSmsNotification(
        sbn: StatusBarNotification,
        settings: SettingsRepository,
        smsMode: String
    ) {
        val notification = sbn.notification ?: return
        val extras = notification.extras

        // في إشعارات تطبيقات الرسائل: العنوان يحمل اسم/رقم المرسل
        // عادةً والنص المحتوى.
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim()
        if (sender.isNullOrBlank() && body.isNullOrBlank()) return

        val displayAddress = sender ?: getString(R.string.sms_unknown_sender)
        val voiceId = settings.getSmsReadingVoiceId()
        val speechRate = settings.getSmsReadingRate()
        val volume = settings.getSmsReadingVolume()

        val content = body ?: ""
        // خصوصية قفل الشاشة: عند القفل يُنطق المصدر فقط
        // دون المحتوى (حماية OTP).
        val privacyLocked = settings.isLockScreenPrivacyEnabled()
                && settings.isDeviceScreenLocked()
        val effectiveMode =
            if (privacyLocked) SmsReadingReceiver.MODE_SOURCE else smsMode

        val dynamicText = "$displayAddress $content"
        val useArabicVoice = !dynamicText.any { it.isLetter() } ||
            LocaleUtils.containsArabic(dynamicText)

        val template = settings.getSmsAnnouncementTemplate()
        // فلتر رمز التحقق (OTP): لا يُنطق الرمز نفسه في الأماكن العامة.
        val isOtp = LocaleUtils.containsOtp(content)
        val smsFrom = LocaleUtils.stringForSpeech(
            applicationContext,
            if (useArabicVoice) LanguageCode.AR.tag else LanguageCode.EN.tag,
            R.string.sms_from,
            R.string.sms_from
        ).replace("{name}", displayAddress)

        val text = when {
            privacyLocked -> smsFrom
            isOtp -> LocaleUtils.stringForSpeech(
                applicationContext,
if (useArabicVoice) LanguageCode.AR.tag
                else LanguageCode.EN.tag,
                R.string.sms_otp_safe,
                R.string.sms_otp_safe
            ).replace("{name}", displayAddress)
            template.isNotBlank() -> template
                .replace("{name}", displayAddress)
                .replace("{message}", content.ifBlank { displayAddress })
            content.isBlank() -> smsFrom
            effectiveMode == SmsReadingReceiver.MODE_SOURCE -> smsFrom
            else -> "$smsFrom، $content"
        }

        val isArabic = LocaleUtils.containsArabic(text)
        val locale =
            if (isArabic) Locale.forLanguageTag(LanguageCode.AR.tag)
            else Locale.forLanguageTag(LanguageCode.EN.tag)

        Log.d(TAG, "SMS via NLS: ${text.length} chars")

        val speech = AnnouncementSpeaker.getInstance(applicationContext)
        speech.resetVoice(voiceId)
        speech.speak(text, locale, speechRate, 1.0f, volume)
    }

    private fun buildSpeechText(
        appName: String,
        title: String?,
        text: String?,
        privacyLocked: Boolean
    ): String {
        // لغة النطق من محتوى الإشعار (اسم التطبيق/العنوان/النص)
        // لا من لغة الواجهة
        val dynamicText = "$appName ${title.orEmpty()} ${text.orEmpty()}"
        val isArabic = !dynamicText.any { it.isLetter() } ||
            LocaleUtils.containsArabic(dynamicText)
        val lang = if (isArabic) LanguageCode.AR.tag else LanguageCode.EN.tag
        // عند قفل الشاشة نكتفي باسم التطبيق دون أي مضمون.
        if (privacyLocked) {
            return LocaleUtils.stringForSpeech(
                applicationContext, lang, R.string.notif_new, R.string.notif_new
            ).replace("{app}", appName)
        }
        return when {
            !title.isNullOrBlank() && !text.isNullOrBlank() ->
                LocaleUtils.stringForSpeech(
                    applicationContext, lang,
                    R.string.notif_from_title_text,
                    R.string.notif_from_title_text
                ).replace("{app}", appName)
                .replace("{title}", title)
                .replace("{text}", text)
            !title.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                applicationContext, lang,
                R.string.notif_from_title,
                R.string.notif_from_title
            ).replace("{app}", appName).replace("{title}", title)
            !text.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                applicationContext, lang,
                R.string.notif_from_text,
                R.string.notif_from_text
            ).replace("{app}", appName).replace("{text}", text)
            else -> LocaleUtils.stringForSpeech(
                applicationContext, lang, R.string.notif_new, R.string.notif_new
            ).replace("{app}", appName)
        }
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> getString(R.string.app_whatsapp)
            "com.whatsapp.w4b" -> getString(R.string.app_whatsapp_business)
            "org.telegram.messenger",
            "org.telegram.messenger.web" -> getString(R.string.app_telegram)
            "com.facebook.orca" -> getString(R.string.app_messenger)
            "com.instagram.android" -> getString(R.string.app_instagram)
            else -> packageName.substringAfterLast('.')
        }
    }
}
