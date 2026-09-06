package com.aymankhattab.nateq.receivers

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.AnnouncementSpeaker
import com.aymankhattab.nateq.util.LocaleUtils
import java.util.Locale

/**
 * خدمة الاستماع للإشعارات — تقرأ إشعارات التطبيقات المهمة (واتساب، تلجرام، إلخ) بالصوت.
 * يجب منح الإذن يدوياً من: الإعدادات ← التطبيقات الخاصة ← الوصول للإشعارات.
 */
class NateqNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NATEQ_NOTIF"

        /** التحقق مما إذا كان التطبيق لديه إذن الاستماع للإشعارات. */
        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, NateqNotificationListener::class.java)
            return flat.contains(cn.flattenToString())
        }
    }

    private var lastNotifTime = 0L
    private val minIntervalMs = 3000L // الحد الأدنى بين إشعارين متتاليين

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val pkg = sbn.packageName ?: return

            val settings = SettingsRepository(applicationContext)
            if (!settings.isNotificationReadingEnabled()) return
            // المفتاح الرئيسي يُوقف كل الإعلانات دفعة واحدة.
            if (!settings.isAllAnnouncementsEnabled()) return
            // قراءة التطبيقات المختارة فقط (للمستخدم حرية اختيار قائمتها).
            if (!settings.shouldReadNotificationApp(pkg)) return

            // تجنّب النطق المزدوج: إن كان الإشعار من تطبيق الرسائل النصية
            // الافتراضي وقراءة SMS مفعّلة، فسيَنطقه مستقبل الرسائل بنفسه.
            val defaultSmsApp = runCatching {
                android.provider.Telephony.Sms.getDefaultSmsPackage(applicationContext)
            }.getOrNull()
            if (pkg == defaultSmsApp && settings.getSmsReadingMode() != SmsReadingReceiver.MODE_OFF) return

            // تجنب تكرار الإشعارات المتتالية بشكل سريع
            val now = System.currentTimeMillis()
            if (now - lastNotifTime < minIntervalMs) return
            lastNotifTime = now

            val notification = sbn.notification ?: return
            val extras = notification.extras

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

            if (title.isNullOrBlank() && text.isNullOrBlank()) return

            val appName = getAppName(pkg)
            val speechText = buildSpeechText(appName, title, text)
            val isArabic = LocaleUtils.containsArabic(speechText)
            val locale = if (isArabic) Locale.forLanguageTag("ar") else Locale.forLanguageTag("en")

            // سجلّ مجرّد من مضمون الإشعار (قد يحوي OTP/حساسيات) — الطول والحزمة فقط.
            Log.d(TAG, "Notification from $pkg: ${speechText.length} chars")

            // احترام إعدادات فئة "صوت الإشعارات" (سرعته/نبرته/مستواه) بدل ثوابت 1.0
            val speechRate = settings.getSpeechRateForCategory(SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS)
            val pitch = settings.getPitchForCategory(SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS)
            val volume = settings.getVolumeForCategory(SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS)
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
        Log.w(TAG, "NotificationListener disconnected")
    }

    private fun buildSpeechText(appName: String, title: String?, text: String?): String {
        // لغة النطق من محتوى الإشعار (اسم التطبيق/العنوان/النص) لا من لغة الواجهة
        val dynamicText = "$appName ${title.orEmpty()} ${text.orEmpty()}"
        val isArabic = !dynamicText.any { it.isLetter() } || LocaleUtils.containsArabic(dynamicText)
        val lang = if (isArabic) "ar" else "en"
        return when {
            !title.isNullOrBlank() && !text.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                applicationContext, lang, R.string.notif_from_title_text, R.string.notif_from_title_text
            ).replace("{app}", appName).replace("{title}", title).replace("{text}", text)
            !title.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                applicationContext, lang, R.string.notif_from_title, R.string.notif_from_title
            ).replace("{app}", appName).replace("{title}", title)
            !text.isNullOrBlank() -> LocaleUtils.stringForSpeech(
                applicationContext, lang, R.string.notif_from_text, R.string.notif_from_text
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
            "org.telegram.messenger", "org.telegram.messenger.web" -> getString(R.string.app_telegram)
            "com.facebook.orca" -> getString(R.string.app_messenger)
            "com.instagram.android" -> getString(R.string.app_instagram)
            else -> packageName.substringAfterLast('.')
        }
    }
}
