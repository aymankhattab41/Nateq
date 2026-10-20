package com.aymankhattab.nateq.core.audio.accessibility

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
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
 * خدمة مراقبة الخروج من التطبيق المحمي (الجولة 48 - الأمر 7).
 */
class ProtectedAppWatcherService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var watchingPackage: String? = null
    private var watchingLabel: String = "تطبيق محمي"
    private var startedAt: Long = 0L

    companion object {
        private const val TAG = "ProtectedAppWatcher"
        private const val NOTIFICATION_ID = 7002
        private const val CHANNEL_ID = "protected_app_watcher"
        private const val EXTRA_PKG = "extra_pkg"
        private const val EXTRA_LABEL = "extra_label"
        private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L

        fun startForApp(context: Context, pkg: String, label: String) {
            val intent = Intent(
                context, ProtectedAppWatcherService::class.java
            ).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_LABEL, label)
            }
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        watchingPackage = intent?.getStringExtra(EXTRA_PKG) ?: watchingPackage
        watchingLabel = intent?.getStringExtra(EXTRA_LABEL) ?: watchingLabel
        startedAt = System.currentTimeMillis()

        startForegroundNotification()
        startWatcherLoop()

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مراقبة التطبيقات المحمية",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مراقبة خروج التطبيق المحمي لإعادة تشغيل الخدمة"
            }
            nm?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("مراقبة $watchingLabel")
            .setContentText("سيتم استعادة إمكانية الوصول فور الخروج من التطبيق")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startWatcherLoop() {
        val targetPkg = watchingPackage ?: return
        val targetLabel = watchingLabel

        scope.launch {
            val settings =
                SettingsRepository.create(this@ProtectedAppWatcherService)
            val usageManager = getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager

            while (isActive) {
                delay(1000L)

                // 1) سلامة: إن لم يخرج المستخدم خلال ساعتين فلا تفعل شيئاً
                if (System.currentTimeMillis() - startedAt > TWO_HOURS_MS) {
                    Log.w(TAG, "Safety timeout reached (2 hours). Stopping.")
                    stopSelf()
                    break
                }

                // 2) سلامة: إن حُذف التطبيق أثناء الإيقاف فأعد التشغيل فوراً
                if (!settings.isProtectedApp(targetPkg)) {
                    Log.i(
                        TAG,
                        "App $targetPkg unlisted from protected apps."
                    )
                    ProtectedAppManager.handleAppExit(
                        this@ProtectedAppWatcherService,
                        targetPkg,
                        targetLabel
                    )
                    stopSelf()
                    break
                }

                // 3) فحص أحداث الاستخدام (UsageStatsManager)
                val now = System.currentTimeMillis()
                val events = usageManager?.queryEvents(now - 3000L, now)
                var hasExited = false

                if (events != null) {
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.packageName == targetPkg) {
                            if (event.eventType ==
                                UsageEvents.Event.ACTIVITY_PAUSED ||
                                event.eventType ==
                                UsageEvents.Event.ACTIVITY_STOPPED
                            ) {
                                hasExited = true
                                break
                            }
                        } else if (event.eventType ==
                            UsageEvents.Event.ACTIVITY_RESUMED &&
                            event.packageName != packageName
                        ) {
                            hasExited = true
                            break
                        }
                    }
                }

                // احتياط: إن لم تتوفر أحداث الاستخدام، فحص النشاط الأمامي
                if (!hasExited) {
                    val am = getSystemService(Context.ACTIVITY_SERVICE)
                        as? ActivityManager
                    val runningTasks = runCatching {
                        am?.getRunningTasks(1)
                    }.getOrNull()
                    val topPkg =
                        runningTasks?.firstOrNull()?.topActivity?.packageName
                    if (topPkg != null &&
                        topPkg != targetPkg &&
                        topPkg != packageName
                    ) {
                        hasExited = true
                    }
                }

                if (hasExited) {
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

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
