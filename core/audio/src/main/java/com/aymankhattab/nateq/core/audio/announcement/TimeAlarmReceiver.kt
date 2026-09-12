package com.aymankhattab.nateq.core.audio.announcement

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * مستقبل إعلان الوقت التلقائي المستقل — يُوقَظ عبر [AlarmManager] عند رأس
 * الفاصل (بدون الاعتماد على عملية الخدمة حية أو مؤقت داخل RAM)، فينطق الوقت
 * ثم يعيد جدولة الفاصل التالي بنفسه.
 *
 * لماذا مستقبل مستقل؟
 * - جدولة مؤقتات الـ RAM داخل الخدمة الأمامية تموت عند قتل النظام للعملية
 *   أو تجمّدها في Doze؛ المنبه المسجَّل في مرحلة النظام يوقظها موثوقاً.
 * - يستخدم [TimeAnnouncementManager] المشترك (نفس كائن الودجت) فلا يتضاعف
 *   المحرك أو تتعارض حالتان؛ والإذن المعلن في الـ manifest هو
 *   SCHEDULE_EXACT_ALARM مع بديل منبّه مرن (setAndAllowWhileIdle) يعمل بلا إذن
 *   ويُطلق في Doze العميق قرب الوقت المستهدف.
 */
class TimeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NATEQ_ALARM"

        const val ACTION_TICK =
            "com.aymankhattab.nateq.action.TIME_ANNOUNCE_TICK"

        /** سقفُ إبقاء بثّ goAsync حياً بانتظار اكتمال النطق (بند [4]) — أقل
         *  من مهلة نظام البث (~10 ثوانٍ) بهامش واضح (~6 ثوانٍ) فلا يصل
         *  goAsync حافتَها فيقع ANR عند تعلّق المحرك بلا onDone (كما رُصد
         *  على أجهزة فعلية). النطق السليم يُنهي البث مبكراً عبر مستمع
         *  الاكتمال؛ هذا السقف للعلّال فقط. */
        private const val ALARM_ASYNC_WINDOW_MS = 6_000L

        /** requestCode ثابت ليكون PendingIntent واحداً
         * (أي استدعاء لاحق يستبدله). */
        private const val REQUEST_CODE = 3701

        /**
         * جداولة الفاصل التالي عبر AlarmManager.
         * @param triggerAtMillis نقطة الزمن المطلقة لإطلاق المنبه
         * (حسب الوقت الحقيقي)
         */
        @JvmStatic
        fun scheduleNext(context: Context, triggerAtMillis: Long) {
            try {
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE)
                    as? AlarmManager ?: return
                val pendingIntent = buildPendingIntent(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    alarmManager.canScheduleExactAlarms()
                ) {
                    // الإذن ممنوح (Android 12+): منبه دقيق يستيقظ من Doze.
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // بدون إذن المنبهات الدقيقة على أندرويد 12+، لا يعمل
                    // setAlarmClock ولا setExact* (SecurityException). نستخدم
                    // منبهاً مرناً يطلق قرب الوقت المطلوب لكنه يُطلق حتى في
                    // Doze العميق ولا يحتاج أي إذن — فيبقى إعلان الوقت يعمل
                    // دائماً، ويتجاوز الدقة متى منح المستخدم الإذن (بند 13.3:
                    // واجهة طلب SCHEDULE_EXACT_ALARM في الإعدادات).
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    // النسخ الأقدم من أندرويد: منبه دقيق من Doze بلا إذن.
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "schedule next failed", t)
            }
        }

        /** إلغاء أي منبه معلّق لإعلان الوقت. */
        @JvmStatic
        fun cancel(context: Context) {
            try {
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE)
                    as? AlarmManager ?: return
                alarmManager.cancel(buildPendingIntent(context))
            } catch (t: Throwable) {
                Log.w(TAG, "cancel failed", t)
            }
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TimeAlarmReceiver::class.java)
                .setAction(ACTION_TICK)
                .setPackage(context.packageName)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** مدة نافذة WakeLock العابرة: تُحافظ على مساواة
         *  [ALARM_ASYNC_WINDOW_MS] (6 ثوانٍ) — فللقفل أن يغطي نافذة
         *  goAsync كاملةً (ربط المحرك وتهيئة الصوت والنطق) ولا ينتهي قبلها
         *  فينام المعالج والنطق ناقص (بند [4]). */
        private const val SHORT_WAKE_LOCK_MS = 6_000L

        /** نافذة WakeLock جزئية مؤقتة (6 ثوانٍ = نافذة البث): تُحرَّر تلقائياً
         *  بوتوقيتها (acquire(timeout)) فالتسريب المقيّد مقصود — بلا حاجة لـ
         *  release يدوي، ولا يستنزف البطارية (منبه كل 15-60 دقيقة لثوانٍ). */
        private fun acquireShortWakeLock(
            context: Context
        ): PowerManager.WakeLock? {
            return try {
                val pm = context.getSystemService(PowerManager::class.java)
                    ?: return null
                pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "$TAG:speech"
                ).apply {
                    setReferenceCounted(false)
                    acquire(SHORT_WAKE_LOCK_MS)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "wake lock acquire failed", t)
                null
            }
        }

        /**
         * دورة إبقاء بثّ goAsync حياً بانتظار النطق (مفصولة للاختبار):
         * - [startContext] تجهيز الخدمة الأمامية العابرة قبل النطق.
         * - [addCompletionListener]/[removeCompletionListener] ربط خطاف
         *   اكتمال النطق بالمدار المشترك وإزالته في النهاية.
         * - [tick] تشغيل مدار إعلان الوقت (يرسّب الفاصل التالي أيضاً).
         * - [finishPending] يُستدعى مرة واحدة بالضبط: عند اكتمال النطق أو
         *   انقضاء نافذة [windowMillis] أو أي خروجٍ غير متوقع.
         */
        internal suspend fun runTickFlow(
            startContext: () -> Unit,
            addCompletionListener: (() -> Unit) -> Unit,
            removeCompletionListener: (() -> Unit) -> Unit,
            tick: () -> Unit,
            finishPending: () -> Unit,
            windowMillis: Long = ALARM_ASYNC_WINDOW_MS
        ) {
            val finished = AtomicBoolean(false)
            val finishOnce: () -> Unit = {
                if (finished.compareAndSet(false, true)) {
                    finishPending()
                }
            }
            runCatching { startContext() }
            val listener = finishOnce
            addCompletionListener(listener)
            try {
                tick()
                delay(windowMillis)
            } finally {
                runCatching { removeCompletionListener(listener) }
                finishOnce()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TICK) return
        // تطابق صارم مع بثّنا (حزمة + صف) وليس حزمة تحمل اسمنا فقط: أي بثّ موجه
        // لمكوّن آخر داخل حزمتنا (أداة/مستقبل آخر) لا يُشغّل نطق الوقت خطأً.
        val cn = intent.component
        if (cn?.packageName != context.packageName) return
        if (cn.className != TimeAlarmReceiver::class.java.name) return

        // Doze: بثّ المنبه يوقظ المعالج لنافذة قصيرة فقط. goAsync يُبقي شعاع
        // البثّ حياً لإنهاء جدولة الـ tick، وWakeLock جزئي مؤقت (6 ثوانٍ)
        // يغطي نافذة النطق في الخلفية (ربط المحرك وتهيئة الصوت): كان إعلان
        // كامل معرضاً للضياع لو عاد المعالج للنوم قبل اكتمال التهيئة.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val appScope = (appContext as AnnouncementAppContext).appScope
        appScope.launch {
            val wakeLock = acquireShortWakeLock(appContext)
            // حارس إنهاء وحيد على مستوى onReceive: فُقدانُ خطافِ الاكتمال أو
            // فشل ربطه أو انقضاء سقف الأمان يُنهي البث هنا مرة واحدة أيضاً
            // (تشغيل التكرار ممنوع — finishٌ مكررٌ يرمي تحذيراً بلا لزوم).
            val finishedBroadcast = AtomicBoolean(false)
            fun finishOnce() {
                if (finishedBroadcast.compareAndSet(false, true)) {
                    pendingResult.finish()
                }
            }
            try {
                runTickFlow(
                    startContext = {
                        // شبكة أمان بند 16.2: تبدأ خدمة أمامية عابرة قبل
                        // النطق من سياق المنبه الخلفي وإن لم تكن خدمة
                        // الإعلانات قائمة، فتغطي نافذة النطق بأمان صوت
                        // الخلفية (أندرويد 15+/سامسونج) ثم توقف نفسها.
                        AnnouncementSchedulerService.startForSpeech(appContext)
                    },
                    addCompletionListener = { listener ->
                        // تحصين بند [4]: لا نُنهي البث فور إطلاق النطق
                        // اللاتزامني — بل نُبقي goAsync حياً حتى اكتمال النطق
                        // الفعلي لآخر جملة (أو مهلة الأمان في runTickFlow)
                        // فيُكمل المحركُ التهيئةَ والنطقَ رغم إيقاظ Doze.
                        AnnouncementSpeaker.getInstance(appContext)
                            .addCompletionListener(listener)
                    },
                    removeCompletionListener = { listener ->
                        AnnouncementSpeaker.getInstance(appContext)
                            .removeCompletionListener(listener)
                    },
                    tick = {
                        // المدار المشترك (نفس كائن الودجت/الأداة)
                        // ينطق ويرسب الفاصل التالي.
                        TimeAnnouncementManager.shared(appContext).onAlarmTick()
                    },
                    finishPending = ::finishOnce,
                    windowMillis = ALARM_ASYNC_WINDOW_MS
                )
            } catch (t: Throwable) {
                Log.e(TAG, "alarm tick failed", t)
            } finally {
                finishOnce()
            }
        }
    }
}