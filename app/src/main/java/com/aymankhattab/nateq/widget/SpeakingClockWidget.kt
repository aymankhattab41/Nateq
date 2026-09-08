package com.aymankhattab.nateq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import com.aymankhattab.nateq.NateqApplication
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.TimeAnnouncementManager
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.AnnouncementSpeaker
import com.aymankhattab.nateq.util.LanguageCode
import java.util.Locale

/**
 * أداة الساعة الناطقة على الشاشة الرئيسية.
 * عند الضغط عليها تُعلن الوقت فوراً بصوت ناطق (عربي/إنجليزي حسب إعدادات
 * النطق والصيغة المختارة). يعمل اللمس من أي حالة (قفل شاشة/سطح المكتب)
 * عبر البث إلى الـ AppWidgetProvider مباشرة.
 *
 * ملاحظة: نص الأداة ثابت («الساعة») لأن الهدف نطق الوقت لا عرضه بصرياً —
 * هذا ما وُعِدت به الأداة في التقرير (ودجت ساعة ناطقة).
 */
class SpeakingClockWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_SPEAK = "com.aymankhattab.nateq.action.WIDGET_SPEAK"

        // مهلة أمان قصوى لبقاء goAsync/WakeLock: حتى لو لم يُستدعَ خطاف اكتمال
        // النطق (فشل تهيئة محرك TTS أو محرك لا يردّ) لا يبقى قفلاً ولا عنصر
        // معالجة معلّقاً يتجاوز هذه المدة.
        private const val SPEAK_TIMEOUT_MS = 20_000L
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_SPEAK) return
        // تحقق صارم أن البث موجّه لمكوّننا (حزمة + صف) وليس لحزمة تحمل اسمنا
        // فقط — أي تطبيق خارجي قد يعيّن ComponentName صراحةً بحزمة تطبيقنا
        // فيتجاوز فحص اسم الحزمة وحده. رفض أي مكوّن غير مطابق تماماً.
        val cn = intent.component
        if (cn?.packageName != context.packageName) return
        if (cn.className != SpeakingClockWidget::class.java.name) return

        // Android 14+ يجمد العملية فور عودة onReceive قبل اكتمال تهيئة محرك
        // TTS فيصمت الودجت. goAsync() يُبقي العملية محاسبةً، وWakeLock مؤقت
        // يُبقي المعالج نشطاً حتى يُنطق الوقت فعلياً (ثم نحرر العنصرين).
        handleSpeak(context, goAsync())
    }

    private fun handleSpeak(context: Context, pendingResult: BroadcastReceiver.PendingResult?) {
        val appContext = context.applicationContext
        var wakeLock: PowerManager.WakeLock? = null
        var finished = false
        val mainHandler = Handler(Looper.getMainLooper())

        val finish = {
            if (!finished) {
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                AnnouncementSpeaker.getInstance(appContext).onSpeechComplete = null
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
                runCatching { pendingResult?.finish() }
            }
        }
        val safety = Runnable { finish() }
        mainHandler.postDelayed(safety, SPEAK_TIMEOUT_MS)

        try {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nateq:WidgetSpeak")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(SPEAK_TIMEOUT_MS)

            // تحرير goAsync فور اكتمال آخر جملة (onDone/onError) من المتحدث
            // المشترك — النطق لا يقتصر على «الوقت» فقط بل قد يكون رسالة التعطيل.
            val speaker = AnnouncementSpeaker.getInstance(appContext)
            speaker.onSpeechComplete = { finish() }

            // المفتاح الموضعي للأداة (من شاشة إعلان الوقت) يقرر إن كانت تنطق عند اللمس.
            val settings = (appContext as? NateqApplication)?.settingsRepository
                ?: SettingsRepository(appContext)
            if (!settings.isClockWidgetEnabled()) {
                val language = runCatching { settings.getAppLanguage() }.getOrNull()
                    ?: Locale.getDefault().language
                val tag = if (LanguageCode.isArabic(language)) LanguageCode.AR.tag else LanguageCode.EN.tag
                speaker.speak(
                    appContext.getString(R.string.widget_clock_disabled),
                    Locale.forLanguageTag(tag), 1.0f, 1.0f, 1.0f
                )
                return
            }

            // نعتمد نفس إعلان الوقت (النص والصيغة والصوت) عبر TimeAnnouncementManager
            // لنطق تطابق تماماً إعلان «أعلن الآن» في التطبيق والخدمة. نستخدم المثيل
            // المشترك عبر العملية (نفس كائن مستقبل المنبه) فلا يتسرب نطاق ولا
            // يتضاعف المحرك.
            TimeAnnouncementManager.shared(appContext).announceNow()
        } catch (t: Throwable) {
            Log.e("NATEQ_TTS", "widget speak failed", t)
            finish()
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_speaking_clock)
        val speakIntent = Intent(context, SpeakingClockWidget::class.java)
            .setAction(ACTION_SPEAK)
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            speakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_clock_root, pending)
        // الأصل الوصلي: الجذر هو عنصر النقر الواحد لقارئ الشاشة (الأطفال معطَّلون)،
        // فيقرأ "اضغط للسماع الوقت" ويفعّل النطق بنقرتين مزدوجتين من TalkBack.
        views.setContentDescription(
            R.id.widget_clock_root,
            context.getString(R.string.widget_clock_tap_to_speak)
        )
        return views
    }
}