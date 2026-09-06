package com.aymankhattab.nateq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.aymankhattab.nateq.NateqApplication
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.TimeAnnouncementManager
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
            // يُفضَّل الحقل المحقون عبر Hilt (كائن مشترك)، وإلا يُبنى محلياً —
            // AppWidgetProvider لا يُحقن تلقائياً من Hilt (يُنشئه النظام مباشرة).
            val settings = (appContext as? NateqApplication)?.settingsRepository
                ?: SettingsRepository(appContext)
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
            // لنطق تطابق تماماً إعلان «أعلن الآن» في التطبيق والخدمة. نستخدم المثيل
            // المشترك عبر العملية (نفس كائن مستقبل المنبه) فلا يتسرب نطاق ولا
            // يتضاعف المحرك.
            TimeAnnouncementManager.shared(appContext).announceNow()
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