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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.receivers.BatteryAnnouncementReceiver
import com.aymankhattab.nateq.settings.SettingsActivity
import com.aymankhattab.nateq.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * الخدمة الأمامية الخاصة بالإعلانات (إعلان الوقت الدوري + مستوى البطارية).
 *
 * لماذا خدمة أمامية بدل العمل دون خدمة؟
 * - استقبال أحداث البطارية: لا يمكن تسجيل BATTERY_CHANGED في الـ manifest
 *   (ممنوع من أندرويد 8+)؛ الخدمة الأمامية تضمن بقاء عملية التطبيق حية
 *   ليلتقط بها مستقبل البطارية المسجَّل ديناميكياً.
 * - معالجة حديثة للإعدادات: تعمل في نفس عملية الواجهة، فلا مشكلة تشارك
 *   SharedPreferences بين العمليات (كانت سبب تجمّد إعدادات عملية :tts).
 *
 * جدولة إعلان الوقت أصبحت عبر مستقبل [TimeAlarmReceiver] بآلية AlarmManager
 * (بند 9)، فتنجو من قتل النظام للعملية وتجمّد Doze دون الحاجة لبقاء الخدمة
 * حية. الخدمة تبقى هنا مستضيفةً مستقبل البطارية وكذلك "أعلن الآن" وزر الإيقاف.
 *
 * تُعاد جدولتها بعد الإقلاع عبر [AnnouncementBootReceiver]، وتُفتح من شاشة
 * الإعدادات عند تفعيل أي إعلان، ويمكن إيقافها من زر الإشعار.
 */
@AndroidEntryPoint
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
        private const val ACTION_TEMPORARY_START = "com.aymankhattab.nateq.action.ANNOUNCE_TEMPORARY_START"

        /** مدة النافذة العابرة: تغطي نطق الوقت القصير وأي بداية بطيئة للمحرك. */
        private const val TEMPORARY_LIFETIME_MS = 30_000L

        // هل الخدمة الأمامية قائمة الآن؟ يستخدمها AnnouncementSpeaker ليقرر إن
        // كان يشغّلها قبل النطق من الخلفية (شرط أندرويد 15+ لصوت الخلفية).
        @Volatile
        @JvmStatic
        var isRunning = false
            private set

        /** تشغيل الخدمة من الواجهة (يفسح إيقاف المستخدم السابق). إن كان إعلان
         *  الوقت هو الوحيد المفعل لا تُشغَّل خدمة أمامية (مستقل بمستقبل المنبه)
         *  وتُجدول منبه الوقت مباشرةً عبر المدير المشترك — بند 16.2. */
        @JvmStatic
        fun requestStart(context: Context) {
            clearUserStopped(context)
            val settings = try {
                SettingsRepository(context)
            } catch (t: Throwable) {
                null
            }
            if (needsForegroundService(settings)) {
                startSafely(context, ACTION_START)
            } else if (settings?.isTimeAnnouncementEnabled() == true) {
                try {
                    TimeAnnouncementManager.shared(context.applicationContext).start()
                } catch (t: Throwable) {
                    Log.w(TAG, "direct time schedule failed", t)
                }
            }
        }

        /** تشغيل الخدمة فقط إن استوجب أي إعلان مفعّل خدمة أمامية (بطارية/
         *  متصل/رسائل/إشعارات) ولم يوقفها المستخدم يدوياً. إعلان الوقت وحده
         *  لا يستوجبها: مستقبل المنبه المستقل يجدول/ينطق بلا خدمة (بند 16.2).
         *  @return true إذا شُغّلت الخدمة */
        @JvmStatic
        fun startIfNeeded(context: Context): Boolean {
            if (wasUserStopped(context)) return false
            // قراءة لحظية (في اقلاع/فتح واجهة قد لا يكون Hilt مهيأ بعد الإقلاع):
            // تُبنى مرجع خفيف للتحقق فقط ولا يُحفظ إلا داخل المدير عند حاجة.
            val settings = try {
                SettingsRepository(context)
            } catch (t: Throwable) {
                null
            } ?: return false
            if (!needsForegroundService(settings)) return false
            startSafely(context, ACTION_START)
            return true
        }

        /** هل الإعلانات الحالية تستوجب بقاء خدمة أمامية؟ («null» أو فشل قراءة
         *  يُرجع true — نبقي الخدمة احتياطاً ولا نخاطر بفقد إعلان). */
        private fun needsForegroundService(settings: SettingsRepository?): Boolean {
            if (settings == null) return true
            return settings.isBatteryAnnouncementEnabled() ||
                settings.isCallerAnnouncementEnabled() ||
                settings.getSmsReadingMode() != "off" ||
                settings.isNotificationReadingEnabled()
        }

        /** يعيد تقييم الحاجة للخدمة دون تشغيلها: إن كانت قائمة ثم لم يعد أي
         *  إعلان يستوجبها (تعطيل البطارية/المتصل/الرسائل/الإشعارات مع بقاء
         *  الوقت فقط) تُوقف — منبه إعلان الوقت مستقل فلا يُلغى. بلا خدمة
         *  قائمة لا تفعل شيئاً. */
        @JvmStatic
        fun syncIfRunning(context: Context) {
            if (!isRunning) return
            val settings = try {
                SettingsRepository(context)
            } catch (t: Throwable) {
                null
            } ?: return
            if (!needsForegroundService(settings)) {
                try {
                    context.stopService(Intent(context, AnnouncementSchedulerService::class.java))
                } catch (t: Throwable) {
                    Log.w(TAG, "sync stop failed", t)
                }
            }
        }

        /** يضمن بقاء منبه إعلان الوقت مجدوولاً بعد أي انقطاع (إقلاع/إعادة
         *  فتح) دون إلزام خدمة أمامية: يُجدول مباشرةً إن لم تكن الخدمة قائمة
         *  ولم يوقفها المستخدم، وبلا تأثير عندما تكون قائمة (تزامنها يغطيه).
         *  [start] لا ينطق إلا أول تفعيل، فالإعادة الجدولة هنا آمنة. */
        @JvmStatic
        fun ensureTimeAlarm(context: Context) {
            if (isRunning) return
            if (wasUserStopped(context)) return
            try {
                // شبكة أمان بند 16.2 أولاً (إقلاع/إعادة فتح = سياق خلفي): بدء
                // عابر للخدمة الأمامية يغطي نطق أول تفعيل بأمان صوت الخلفية،
                // ثم يُجدول منبه الوقت ويعيد نطق أول تفعيل عبر المدير المشترك.
                startForSpeech(context)
                TimeAnnouncementManager.shared(context.applicationContext).start()
            } catch (t: Throwable) {
                Log.w(TAG, "ensure time alarm failed", t)
            }
        }

        /** شبكة أمان بند 16.2: ضمان نطق من الخلفية (أندرويد 15+/سامسونج) عبر
         *  خدمة أمامية بلا استبقاء 24/7. إن كان أي إعلان يستوجب خدمة دائمة
         *  تُبدأ دائمة كبوابتها المعتادة؛ وإن كان إعلان الوقت وحده تُبدأ عابرة
         *  لفترة النطق ([ACTION_TEMPORARY_START]) ثم توقف نفسها بمؤقت أمان. */
        @JvmStatic
        fun startForSpeech(context: Context) {
            if (isRunning) return
            if (wasUserStopped(context)) return
            val settings = try {
                SettingsRepository(context)
            } catch (t: Throwable) {
                null
            }
            startSafely(
                context,
                if (needsForegroundService(settings)) ACTION_START else ACTION_TEMPORARY_START
            )
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

    /** مصدر الإعدادات المحقون — يصبح الكائن الوحيد المشترك عبر عملية الواجهة. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private lateinit var settings: SettingsRepository
    private var timeManager: TimeAnnouncementManager? = null
    private var batteryReceiver: BatteryAnnouncementReceiver? = null

    /** جدولة زمنية على خيط الواجهة — تُستخدم لمؤقت النافذة العابرة حصراً. */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startAsForeground(buildNotification())

        // دفاعية: عند بدء النظام للخدمة مباشرة (STICKY) قد تكون الحقول المحقونة
        // غير جاهزة؛ نبني مرجعاً محلياً عندها (نمط NateqTtsService).
        settings = if (::settingsRepository.isInitialized) settingsRepository
        else SettingsRepository(applicationContext)
        // المدير المشترك عبر العملية (نفس كائن الودجت ومستقبل المنبه) — تُبنى
        // مكوناته مرة واحدة ويُستخدم لبدء/إيقاف منبه الوقت و"أعلن الآن".
        timeManager = TimeAnnouncementManager.shared(this, settings)
        // المزامنة الفعلية (الوقت/مستقبل البطارية) تتم في onStartCommand وفق
        // نوع الطلب: «START» وSTICKY يزامنوان، و«العابر» لا يزامن (بند 16.2).
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // أي أمر جديد يُبطل مؤقت إيقاف النافذة العابرة (قد يصبح فترة دائمة).
        mainHandler.removeCallbacksAndMessages(null)
        when (intent?.action) {
            ACTION_ANNOUNCE_NOW -> announceNow()
            ACTION_STOP -> {
                markUserStopped(this)
                // إيقاف المستخدم الصريح: نُلغي منبه إعلان الوقت أيضاً حتى لا
                // يستمر المستقبل المستقل بالنطق بعد أن طلب المستخدم الإيقاف.
                try {
                    timeManager?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "time manager stop failed", t)
                }
                stopInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEMPORARY_START -> {
                // نافذة نطق عابرة (شبكة أمان بند 16.2 عند إعلان الوقت فقط):
                // لا نُزامن ولا نُسجّل مستقبل البطارية — الـ tick الجاري يتولى
                // الجدولة، وتُوقف الخدمة نفسها بعد نافذة الأمان.
                armTemporarySelfStop()
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

        // بند 16.2: إن لم يبقَ إعلان يستوجب خدمة أمامية (تعطيل كل فئات
        // البطارية/المتصل/الرسائل/الإشعارات مع بقاء «الوقت» أو بدونه) تُوقف
        // الخدمة ذاتياً؛ منبه إعلان الوقت مستقل عبر مستقبل المنبه ولا يتأثر.
        // يغطي إعادة البناء STICKY بعد تغيّر الإعدادات خارج المتحكمات.
        if (!needsForegroundService()) {
            try {
                stopInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (t: Throwable) {
                Log.w(TAG, "self stop failed", t)
            }
        }
    }

    /** هل تبقى الحاجة للخدمة الأمامية؟ (الوقت وحده لا يستوجبها — بند 16.2) */
    private fun needsForegroundService(): Boolean {
        return try {
            settings.isBatteryAnnouncementEnabled() ||
                settings.isCallerAnnouncementEnabled() ||
                settings.getSmsReadingMode() != "off" ||
                settings.isNotificationReadingEnabled()
        } catch (t: Throwable) {
            true // فشل قراءة الإعدادات: نبقي الخدمة احتياطاً
        }
    }

    /** نافذة النطق العابرة (شبكة أمان بند 16.2): بعد [TEMPORARY_LIFETIME_MS]
     *  تُوقف الخدمة نفسها — الـ tick الجاري جدول التالي عبر مستقبل المنبه
     *  المستقل فلا يتأثر إعلان الوقت بإيقافها. */
    private fun armTemporarySelfStop() {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            try {
                stopInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (t: Throwable) {
                Log.w(TAG, "temporary stop failed", t)
            }
        }, TEMPORARY_LIFETIME_MS)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        isRunning = false
        stopInternal()
        super.onDestroy()
    }

    private fun stopInternal() {
        // لا نُلغي منبه إعلان الوقت هنا عمداً: مستقبل TIME_ALARM مستقل ويُعاود
        // جدولة نفسه، ولا يجب قتله عند كشف النظام للخدمة (قتل الخدمة ≠ إيقاف
        // مستخدم). الإنعاش العائد عبر startIfNeeded/STICKY عليه هو ما يعيد
        // الزامن، والمدير نفسه يتأكد من الإعدادات في كل tick.
        timeManager = null
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (t: Throwable) {
                // قد يكون أُلغي مسبقاً أثناء إعادة الإنشاء
            }
        }
        batteryReceiver = null
        // إغلاق محرك TTS وإبطال كل مؤقتات النطق المعلّقة عند خروج الخدمة
        // حتى لا تبقى موقتات/Hوandler معلّقة تشغّل النطق بعد أكبر عمراً
        // (بند [7]) — المتحدث المشترك يُعاد بناؤه عند الحاجة لاحقاً.
        try {
            com.aymankhattab.nateq.util.AnnouncementSpeaker.getInstance(this).shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "announcement speaker shutdown failed", t)
        }
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