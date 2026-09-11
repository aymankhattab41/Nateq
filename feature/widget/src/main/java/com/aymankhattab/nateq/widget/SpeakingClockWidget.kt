package com.aymankhattab.nateq.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.RemoteViews
import com.aymankhattab.nateq.feature.widget.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementAppContext
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.announcement.TimeAnnouncementManager
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import java.util.Locale

/** هل البث موجّه لمكوّننا (حزمة + صف)؟ يرفض أي مكوّن غير مطابق تماماً، ومنها
 *  الحالة عندما يكون المكوّن غائباً (package/className يساويان null) — فيُرجع
 *  false ويُتجاهل البث بدل نطقٍ صامت. منطقٌ نقي قابل للاختبار. */
internal fun isComponentOurs(
    componentPackage: String?,
    componentClass: String?,
    selfPackage: String,
    selfClass: String
): Boolean =
    componentPackage == selfPackage && componentClass == selfClass

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
        private const val ACTION_SPEAK =
            "com.aymankhattab.nateq.action.WIDGET_SPEAK"

        // مهلة أمان قصوى لبقاء goAsync/WakeLock: حتى لو لم يُستدعَ خطاف اكتمال
        // النطق (فشل تهيئة محرك TTS أو محرك لا يردّ) لا يبقى قفلاً ولا عنصر
        // معالجة معلّقاً يتجاوز هذه المدة. جملة الساعة قصيرة (ثوانٍ معدودة)
        // فتُقيَّد المهلة بثمانٍ لتخفيف استهلاك البطارية.
        private const val SPEAK_TIMEOUT_MS = 8_000L
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
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
        // فيتجاوز فحص اسم الحزمة وحده. رفض أي مكوّن غير مطابق تماماً (ومنها
        // غياب المكوّن كلياً بدل تجاهل نقرٍ صامت).
        val cn = intent.component
        if (!isComponentOurs(
                cn?.packageName,
                cn?.className,
                context.packageName,
                SpeakingClockWidget::class.java.name
            )
        ) return

        // Android 14+ يجمد العملية فور عودة onReceive قبل اكتمال تهيئة محرك
        // TTS فيصمت الودجت. goAsync() يُبقي العملية محاسبةً، وWakeLock مؤقت
        // يُبقي المعالج نشطاً حتى يُنطق الوقت فعلياً (ثم نحرر العنصرين).
        handleSpeak(context, goAsync())
    }

    private fun handleSpeak(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult?,
    ) {
        val appContext = context.applicationContext
        // استجابة لمسية فورية عند الضغط على الأداة (قبل بدء النطق):
        // تأكيدٌ إتاحي للّمس لقارئ الشاشة ومستخدمي اللمس معاً.
        vibrateTap(appContext)
        var wakeLock: PowerManager.WakeLock? = null
        var finished = false
        val mainHandler = Handler(Looper.getMainLooper())
        val speaker = AnnouncementSpeaker.getInstance(appContext)
        var widgetListener: (() -> Unit)? = null

        val finish = {
            if (!finished) {
                finished = true
                mainHandler.removeCallbacksAndMessages(null)
                // نزيل مستمعنا فقط — لا نطمس مستمعين من دورات أخرى
                // (متصل/رسائل/منبه) بكتابة خانةِ خطافٍ وحيدة (بند [8]).
                widgetListener?.let(speaker::removeCompletionListener)
                runCatching {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
                runCatching { pendingResult?.finish() }
            }
        }
        val safety = Runnable { finish() }
        mainHandler.postDelayed(safety, SPEAK_TIMEOUT_MS)

        try {
            val powerManager = appContext
                .getSystemService(Context.POWER_SERVICE)
                as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Nateq:WidgetSpeak",
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(SPEAK_TIMEOUT_MS)

            // تحرير goAsync فور اكتمال آخر جملة (onDone/onError) من المتحدث
            // المشترك — النطق لا يقتصر على «الوقت» فقط بل قد يكون رسالة
            // التعطيل. نسجّل في قائمة المستمعين (بند [8]) لا في خانةٍ
            // وحيدة طامسةٍ لمستمعي دوراتٍ أخرى.
            widgetListener = { finish() }
            speaker.addCompletionListener(widgetListener!!)

            // المفتاح الموضعي للأداة (من شاشة إعلان الوقت) يقرر إن كانت
            // تنطق عند اللمس.
            // مصدر الإعدادات المحقون في التطبيق يُسترجع عبر عقد إتاحة
            // :core:audio
            // (تطبّقه NateqApplication) بدل الاعتماد المباشر على فئة
            // التطبيق من :app.
            val settings =
                (appContext as? AnnouncementAppContext)?.settingsRepository
                    ?: SettingsRepository(appContext)
            if (!settings.isClockWidgetEnabled()) {
                val language = runCatching { settings.getAppLanguage() }
                    .getOrNull()
                    ?: Locale.getDefault().language
                val tag = if (LanguageCode.isArabic(language)) {
                    LanguageCode.AR.tag
                } else {
                    LanguageCode.EN.tag
                }
                speaker.speak(
                    appContext.getString(R.string.widget_clock_disabled),
                    Locale.forLanguageTag(tag), 1.0f, 1.0f, 1.0f
                )
                return
            }

            // نعتمد نفس إعلان الوقت (النص والصيغة والصوت) عبر
            // TimeAnnouncementManager
            // لنطق تطابق تماماً إعلان «أعلن الآن» في التطبيق والخدمة. نستخدم
            // المثيل
            // المشترك عبر العملية (نفس كائن مستقبل المنبه) فلا يتسرب نطاق ولا
            // يتضاعف المحرك.
            TimeAnnouncementManager.shared(appContext).announceNow()
        } catch (t: Throwable) {
            Log.e("NATEQ_TTS", "widget speak failed", t)
            finish()
        }
    }

    /** اهتزاز نقرٍ قصير متوافق مع كل الإصدارات: تأثير مُعرّف مسبقاً (Q+) أو
     *  نبضة 30ms (O+) أو النداء المُهجَّر دون 26 — مع الإذن العادي VIBRATE
     *  في المانيفست. صامت تماماً في غياب الهزّاز أو الإذن. */
    private fun vibrateTap(context: Context) {
        // getSystemService(Class) متاح منذ API 23 (minSdk=24) ويرجع
        // null لو غاب الخداع — بلا الثابت المُهجَّر Context.VIBRATOR_SERVICE.
        val vibrator = context.getSystemService(Vibrator::class.java)
            ?: return
        if (!vibrator.hasVibrator()) return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                vibrator.vibrate(
                    VibrationEffect.createPredefined(
                        VibrationEffect.EFFECT_CLICK
                    )
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        30L, VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
            else -> {
                @Suppress("DEPRECATION")
                vibrator.vibrate(30L)
            }
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(
            context.packageName,
            R.layout.widget_speaking_clock,
        )
        val speakIntent = Intent(context, SpeakingClockWidget::class.java)
            .setAction(ACTION_SPEAK)
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            speakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_clock_root, pending)
        // الأصل الوصلي: الجذر هو عنصر النقر الواحد لقارئ الشاشة (الأطفال
        // معطَّلون)،
        // فيقرأ "اضغط للسماع الوقت" ويفعّل النطق بنقرتين مزدوجتين من TalkBack.
        views.setContentDescription(
            R.id.widget_clock_root,
            context.getString(R.string.widget_clock_tap_to_speak)
        )
        return views
    }
}