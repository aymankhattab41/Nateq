package com.aymankhattab.nateq.core.audio.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * مراقب التطبيقات المحمية (الجولة 50 - الأمر 2).
 * وضع تفاعلي حصري: يُشغَّل عند دخول تطبيق محمي لمراقبة خروجه ثم يتوقف.
 * استطلاع متدرج عبر UsageStats مع إيقاف كامل عند إطفاء الشاشة.
 */
class ProtectedAppWatcherService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var watchingPackage: String? = null
    private var watchingLabel: String = "تطبيق محمي"
    private var startedAt: Long = 0L
    @Volatile private var isScreenOn: Boolean = true
    private var screenReceiver: BroadcastReceiver? = null

    @androidx.annotation.VisibleForTesting
    fun isScreenOnForTesting(): Boolean = isScreenOn

    @androidx.annotation.VisibleForTesting
    fun getScreenReceiverForTesting(): BroadcastReceiver? = screenReceiver

    companion object {
        private const val TAG = "ProtectedAppWatcher"
        private const val NOTIFICATION_ID = 7002
        private const val CHANNEL_ID = "protected_app_watcher_v2"
        private const val EXTRA_PKG = "extra_pkg"
        private const val EXTRA_LABEL = "extra_label"
        private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L

        /** تشغيل المراقب التفاعلي عند دخول تطبيق محمي. */
        fun startForApp(context: Context, pkg: String, label: String) {
            val intent = Intent(
                context, ProtectedAppWatcherService::class.java
            ).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_LABEL, label)
            }
            startSafely(context, intent)
        }

        private fun startSafely(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ProtectedAppWatcherService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerScreenReceiver()
        BatteryDiagnosticsTracker.recordServiceStarted(
            "ProtectedAppWatcherService"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        watchingPackage = intent?.getStringExtra(EXTRA_PKG) ?: watchingPackage
        watchingLabel = intent?.getStringExtra(EXTRA_LABEL) ?: watchingLabel
        startedAt = System.currentTimeMillis()

        startForegroundNotification()
        startReactiveLoop()

        return START_NOT_STICKY
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOn = false
                        Log.d(TAG, "Screen OFF — pausing polling")
                    }
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        isScreenOn = true
                        Log.d(TAG, "Screen ON — resuming polling")
                    }
                }
            }
        }
        screenReceiver = receiver
        registerReceiver(receiver, filter)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.deleteNotificationChannel("protected_app_watcher")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مراقبة التطبيقات المحمية",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مراقبة التطبيقات المحمية لضبط إمكانية الوصول"
                enableVibration(false)
                vibrationPattern = null
            }
            nm?.createNotificationChannel(channel)
        }

        val title = "مراقبة $watchingLabel"
        val text = "سيتم استعادة إمكانية الوصول فور الخروج من التطبيق"

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setVibrate(null)
            .setDefaults(0)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /** الوضع التفاعلي: استطلاع متدرج لخروج التطبيق المحمي ثم التوقف. */
    private fun startReactiveLoop() {
        val targetPkg = watchingPackage ?: return
        val targetLabel = watchingLabel

        scope.launch {
            val settings =
                SettingsRepository.create(this@ProtectedAppWatcherService)
            var lastQueryTime = startedAt

            while (isActive) {
                // أثناء إطفاء الشاشة: انتظر بلا استطلاع لحفظ البطارية
                while (!isScreenOn && isActive) {
                    delay(1000L)
                }

                val elapsed = System.currentTimeMillis() - startedAt
                val pollIntervalMs = when {
                    elapsed < 20_000L -> 1500L
                    elapsed < 60_000L -> 3000L
                    else -> 5000L
                }

                delay(pollIntervalMs)

                if (System.currentTimeMillis() - startedAt > TWO_HOURS_MS) {
                    Log.w(TAG, "Safety timeout reached (2 hours). Stopping.")
                    stopSelf()
                    break
                }

                if (!settings.isProtectedApp(targetPkg)) {
                    Log.i(TAG, "App $targetPkg unlisted. Restoring now.")
                    ProtectedAppManager.handleAppExit(
                        this@ProtectedAppWatcherService,
                        targetPkg,
                        targetLabel
                    )
                    stopSelf()
                    break
                }

                val now = System.currentTimeMillis()
                val queryStart = maxOf(startedAt, lastQueryTime)
                lastQueryTime = now

                if (hasExited(targetPkg, queryStart, now)) {
                    Log.i(TAG, "Protected app $targetPkg exited. Restoring.")
                    ProtectedAppManager.handleAppExit(
                        this@ProtectedAppWatcherService,
                        targetPkg,
                        targetLabel
                    )
                    stopSelf()
                    break
                }
            }
        }
    }

    /**
     * هل خرج تطبيقٌ محمي؟ يستعلم أحداث الاستخدام بنافذة تزايدية.
     */
    private fun hasExited(
        targetPkg: String,
        startTime: Long,
        endTime: Long
    ): Boolean {
        BatteryDiagnosticsTracker.recordUsageStatsPoll()
        val usageManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val window = maxOf(endTime - startTime, 1000L)
        val events = usageManager?.queryEvents(endTime - window, endTime)
        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName == targetPkg) {
                    if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                        event.eventType ==
                        UsageEvents.Event.ACTIVITY_STOPPED
                    ) {
                        return true
                    }
                } else if (event.eventType ==
                    UsageEvents.Event.ACTIVITY_RESUMED &&
                    event.packageName != packageName
                ) {
                    return true
                }
            }
        }
        return false
    }

    override fun onDestroy() {
        BatteryDiagnosticsTracker.recordServiceStopped(
            "ProtectedAppWatcherService"
        )
        screenReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        serviceJob.cancel()
        super.onDestroy()
    }
}
