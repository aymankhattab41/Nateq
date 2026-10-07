package com.aymankhattab.nateq.core.audio.accessibility

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * جدولة واستئناف الإيقاف المؤقت لخدمات الشاشة (ع٢٧-14).
 * يدعم مدد: 15 دقيقة، ساعة، 3 ساعات، حتى منتصف الليل، ويدوي.
 */
object A11yPauseScheduler {

    const val DURATION_15_MINUTES = 15 * 60 * 1000L
    const val DURATION_1_HOUR = 60 * 60 * 1000L
    const val DURATION_3_HOURS = 3 * 60 * 60 * 1000L
    const val DURATION_MANUAL = 0L

    const val ACTION_RESUME_A11Y =
        "com.aymankhattab.nateq.action.A11Y_RESUME"

    private const val REQUEST_CODE = 9011

    /**
     * حساب وقت منتصف الليل القادم بالمللي ثانية (Epoch)
     */
    fun calculateNextMidnightEpoch(
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 24)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /**
     * جدولة منبّه الاستئناف التلقائي عبر AlarmManager
     */
    fun scheduleResumeAlarm(context: Context, resumeEpochMs: Long) {
        if (resumeEpochMs <= System.currentTimeMillis()) return
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return

        val intent = Intent(context, A11yResumeReceiver::class.java).apply {
            action = ACTION_RESUME_A11Y
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            flags
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    resumeEpochMs,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    resumeEpochMs,
                    pendingIntent
                )
            }
        } catch (t: Throwable) {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                resumeEpochMs,
                pendingIntent
            )
        }
    }

    /**
     * إلغاء منبّه الاستئناف التلقائي
     */
    fun cancelResumeAlarm(context: Context) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return

        val intent = Intent(context, A11yResumeReceiver::class.java).apply {
            action = ACTION_RESUME_A11Y
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            flags
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
