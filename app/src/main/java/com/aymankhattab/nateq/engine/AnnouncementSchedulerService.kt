package com.aymankhattab.nateq.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.providers.SystemVoiceProvider
import com.aymankhattab.nateq.receivers.BatteryAnnouncementReceiver
import com.aymankhattab.nateq.settings.SettingsActivity
import com.aymankhattab.nateq.settings.SettingsRepository

/**
 * الخدمة الأمامية الخاصة بالإعلانات (إعلان الوقت الدوري + مستوى البطارية).
 *
 * لماذا خدمة أمامية بدل العمل دون خدمة؟
 * - جدولة ساعة مضمونة: تعيش في عملية التطبيق الرئيسية طوال الجلسة ويحميها
 *   النظام من القتل عند الخمول (Doze) بشرط إشعار دائم.
 * - استقبال أحداث البطارية: لا يمكن تسجيل BATTERY_CHANGED في الـ manifest
 *   (ممنوع من أندرويد 8+)؛ الخدمة الأمامية تضمن بقاء عملية التطبيق حية
 *   ليلتقط بها مستقبل البطارية المسجَّل ديناميكياً.
 * - معالجة حديثة للإعدادات: تعمل في نفس عملية الواجهة، فلا مشكلة تشارك
 *   SharedPreferences بين العمليات (كانت سبب تجمّد إعدادات عملية :tts).
 *
 * تُعاد جدولتها بعد الإقلاع عبر [AnnouncementBootReceiver]، وتُفتح من شاشة
 * الإعدادات عند تفعيل أي إعلان، ويمكن إيقافها من زر الإشعار.
 */
class AnnouncementSchedulerService : Service() {

    companion object {
        private const val TAG = "NATEQ_ANNOUNCE"
        private const val CHANNEL_ID = "announcements"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "nateq_announce_svc"
        private const val KEY_USER_STOPPED = "stopped_by_user"

        private const val ACTION_START = "com.aymankhattab.nateq.action.ANNOUNCE_START"
        private const val ACTION_ANNOUNCE_NOW = "com.aymankhattab.nateq.action.ANNOUNCE_NOW"
        private const val ACTION_STOP = "com.aymankhattab.nateq.action.ANNOUNCE_STOP"

        // هل الخدمة الأمامية قائمة الآن؟ يستخدمها AnnouncementSpeaker ليقرر إن
        // كان يشغّلها قبل النطق من الخلفية (شرط أندرويد 15+ لصوت الخلفية).
        @Volatile
        @JvmStatic
        var isRunning = false
            private set

        /** تشغيل الخدمة من الواجهة (يفسح إيقاف المستخدم السابق). */
        @JvmStatic
        fun requestStart(context: Context) {
            clearUserStopped(context)
            startSafely(context, ACTION_START)
        }

        /** تشغيل الخدمة فقط إن كان أي إعلان مفعلاً ولم يوقفها المستخدم يدوياً.
         *  @return true إذا شُغّلت الخدمة */
        @JvmStatic
        fun startIfNeeded(context: Context): Boolean {
            if (wasUserStopped(context)) return false
            val settings = try {
                SettingsRepository(context)
            } catch (t: Throwable) {
                null
            } ?: return false
            val anyEnabled =
                settings.isTimeAnnouncementEnabled() ||
                    settings.isBatteryAnnouncementEnabled() ||
                    settings.isCallerAnnouncementEnabled() ||
                    settings.getSmsReadingMode() != "off" ||
                    settings.isNotificationReadingEnabled()
            if (anyEnabled) {
                startSafely(context, ACTION_START)
                return true
            }
            return false
        }

        private fun startSafely(context: Context, action: String) {
            try {
                val intent = Intent(context, AnnouncementSchedulerService::class.java)
                    .setAction(action)
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "startForegroundService failed", t)
            }
        }

        private fun wasUserStopped(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_STOPPED, false)

        private fun clearUserStopped(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USER_STOPPED, false).apply()
        }

        fun markUserStopped(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_USER_STOPPED, true).apply()
        }
    }

    private lateinit var settings: SettingsRepository
    private var timeManager: TimeAnnouncementManager? = null
    private var batteryReceiver: BatteryAnnouncementReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startAsForeground(buildNotification())

        settings = SettingsRepository(this)
        val providers = listOf(SystemVoiceProvider(this))
        val catalog = VoiceCatalog(providers)
        val requestHandler = SynthesisRequestHandler(catalog, settings)
        val manager = TimeAnnouncementManager(this, settings, catalog, requestHandler)
        timeManager = manager

        // مزامنة أولية: تبدأ إعلان الوقت إن كان مفعلاً، وتُسجّل مستقبل
        // البطارية/الشحن (لا يُسجَّل من الـ manifest؛ BATTERY_CHANGED ممنوع
        // هناك)، ويُعاد استدعاؤها من onStartCommand عند كل START لتسري
        // تغييرات الإعدادات فوراً.
        try {
            syncWithSettings()
        } catch (t: Throwable) {
            Log.e(TAG, "initial sync failed", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANNOUNCE_NOW -> announceNow()
            ACTION_STOP -> {
                markUserStopped(this)
                stopInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START (من الواجهة/الإقلاع) أو إعادة إنشاء النظام (STICKY):
                // نعيد مزامنة الإعلانات مع الإعدادات الحالية حتى تسري التغييرات
                // فوراً دون الحاجة لإعادة بناء الخدمة (بند 8ب في التقرير).
                try {
                    syncWithSettings()
                } catch (t: Throwable) {
                    Log.e(TAG, "syncWithSettings failed", t)
                }
            }
        }
        // إعادة إنشاء الخدمة إن قتلها النظام (STICKY)، لتعود الجدولة والإعلانات.
        return START_STICKY
    }

    /** يزامن مكوّنات الإعلان (الوقت/البطارية) مع الإعدادات الحالية في كل START. */
    private fun syncWithSettings() {
        val manager = timeManager
            ?: return
        val timeEnabled = try {
            settings.isTimeAnnouncementEnabled()
        } catch (t: Throwable) {
            false
        }
        if (timeEnabled) {
            try {
                manager.start()
            } catch (t: Throwable) {
                Log.e(TAG, "time manager start failed", t)
            }
        } else {
            manager.stop()
        }

        // مستقبل البطارية يُسجَّل مرة واحدة فقط بإطار يمتد لأحداث الشحن أيضاً.
        if (batteryReceiver == null) {
            try {
                val receiver = BatteryAnnouncementReceiver()
                batteryReceiver = receiver
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(receiver, filter)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "battery receiver registration failed", t)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        stopInternal()
        super.onDestroy()
    }

    private fun stopInternal() {
        timeManager?.stop()
        timeManager = null
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (t: Throwable) {
                // قد يكون أُلغي مسبقاً أثناء إعادة الإنشاء
            }
        }
        batteryReceiver = null
    }

    /** نطق الوقت فوراً (من زر "أعلن الآن") — يتجاوز ساعات الهدوء عمداً. */
    private fun announceNow() {
        val manager = timeManager
        if (manager == null) return
        try {
            manager.announceNow()
        } catch (t: Throwable) {
            Log.e(TAG, "announce now failed", t)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.announce_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.announce_service_channel_description)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openSettings = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val announceNow = PendingIntent.getService(
            this,
            0,
            Intent(this, AnnouncementSchedulerService::class.java).setAction(ACTION_ANNOUNCE_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            0,
            Intent(this, AnnouncementSchedulerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.announce_service_notification_title))
            .setContentText(getString(R.string.announce_service_notification_text))
            .setSmallIcon(R.drawable.ic_number_reading)
            .setContentIntent(openSettings)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                getString(R.string.announce_action_speak_now),
                announceNow
            )
            .addAction(0, getString(R.string.announce_action_stop), stopService)
            .build()
    }

    private fun startAsForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                // الإصدارات الأقدم: النوع يأتي من إعلان الـ manifest تلقائياً
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }
}