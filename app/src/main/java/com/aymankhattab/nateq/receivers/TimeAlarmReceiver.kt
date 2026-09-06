package com.aymankhattab.nateq.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.aymankhattab.nateq.engine.TimeAnnouncementManager

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
 *   SCHEDULE_EXACT_ALARM مع بديل setAlarmClock (يعمل في Doze بلا إذن).
 */
class TimeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NATEQ_ALARM"

        const val ACTION_TICK = "com.aymankhattab.nateq.action.TIME_ANNOUNCE_TICK"

        /** requestCode ثابت ليكون PendingIntent واحداً (أي استدعاء لاحق يستبدله). */
        private const val REQUEST_CODE = 3701

        /**
         * جداولة الفاصل التالي عبر AlarmManager.
         * @param triggerAtMillis نقطة الزمن المطلقة لإطلاق المنبه (حسب الوقت الحقيقي)
         */
        @JvmStatic
        fun scheduleNext(context: Context, triggerAtMillis: Long) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE)
                    as? AlarmManager ?: return
                val pendingIntent = buildPendingIntent(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !alarmManager.canScheduleExactAlarms()
                ) {
                    // بدون إذن المنبهات الدقيقة على أندرويد 12+: نستخدم
                    // setAlarmClock — يعمل في Doze ويوقظ دقيقاً بلا إذن زائد.
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAtMillis, null),
                        pendingIntent
                    )
                } else {
                    // منبه دقيق يستيقظ من Doze (متاح من API 23، minSdk 24 هنا).
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
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE)
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TICK) return
        if (intent.component?.packageName != context.packageName) return
        try {
            // المدير المشترك (نفس كائن الودجت/الأداة) ينطق ويرسب الفاصل التالي.
            TimeAnnouncementManager.shared(context.applicationContext).onAlarmTick()
        } catch (t: Throwable) {
            Log.e(TAG, "alarm tick failed", t)
        }
    }
}