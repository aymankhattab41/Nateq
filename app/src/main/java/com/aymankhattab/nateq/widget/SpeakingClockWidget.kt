package com.aymankhattab.nateq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.engine.TimeAnnouncementManager
import com.aymankhattab.nateq.engine.VoiceCatalog
import com.aymankhattab.nateq.providers.SystemVoiceProvider
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.AnnouncementSpeaker
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

        // مثيل واحد مشترك لمدير إعلان الوقت عبر عملية التطبيق، يُعوَّض مرة واحدة
        // ويُعاد استخدامه لكل ضغطة على الأداة. كان إنشاء مدير جديد عند كل نقرة
        // يُطلق CoroutineScope جديداً بلا إغلاق فيتسرب تدريجياً عبر عمر الودجت.
        @Volatile
        private var timeManager: TimeAnnouncementManager? = null

        @Synchronized
        private fun sharedTimeManager(context: Context): TimeAnnouncementManager {
            timeManager?.let { return it }
            val appContext = context.applicationContext
            val settings = SettingsRepository(appContext)
            val providers = listOf(SystemVoiceProvider(appContext))
            val catalog = VoiceCatalog(providers)
            val handler = SynthesisRequestHandler(catalog, settings)
            return TimeAnnouncementManager(appContext, settings, catalog, handler)
                .also { timeManager = it }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_SPEAK) {
            // تحقق أن البث موجّه لمكوّننا أصلاً (يمنع الإطلاق العرضي من مصادر أخرى)
            if (intent.component?.packageName != context.packageName) return
            handleSpeak(context)
        }
    }

    private fun handleSpeak(context: Context) {
        try {
            val appContext = context.applicationContext
            val settings = SettingsRepository(appContext)
            // المفتاح الموضعي للأداة (من شاشة إعلان الوقت) يقرر إن كانت تنطق عند اللمس.
            if (!settings.isClockWidgetEnabled()) {
                // نُعلم المستخدم بمعطّلية النطق بدل الصمت.
                AnnouncementSpeaker.getInstance(appContext).speak(
                    appContext.getString(R.string.widget_clock_disabled),
                    Locale.forLanguageTag("ar"), 1.0f, 1.0f, 1.0f
                )
                return
            }

            // نعتمد نفس إعلان الوقت (النص والصيغة والصوت) عبر TimeAnnouncementManager
            // لنطق تطابق تماماً إعلان «أعلن الآن» في التطبيق والخدمة.
            sharedTimeManager(appContext).announceNow()
        } catch (t: Throwable) {
            android.util.Log.e("NATEQ_TTS", "widget speak failed", t)
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