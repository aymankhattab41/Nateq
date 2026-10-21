package com.aymankhattab.nateq.core.audio.accessibility

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
 * مراقب التطبيقات المحمية (الجولة 48 - الأمر 7).
 *
 * له وضعان:
 * - **تفاعلي (افتراضي):** يُشغَّل عند دخول تطبيقٍ محمي لمراقبة الخروج
 *   فقط، ثم يتوقف.
 * - **استباقي (اختياري):** يعمل باستمرارٍ ما دام الخيار مفعّلاً فيُعطّل
 *   خدمة إمكانية الوصول **فور بدء** التطبيق المحمي — قبل أن يقرأ
 *   حالته ويسقط في onCreate. يكسب هذا السباق لأن `UsageStats` ترى
 *   التطبيق قبل وصوله لشاشة القرار بنحو ثانيتين (مُقاس حيّاً).
 *   ثمنُه استهلاكُ البطارية من الاستطلاع الدوري، فمعطّلٌ افتراضياً.
 */
class ProtectedAppWatcherService : Service() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var watchingPackage: String? = null
    private var watchingLabel: String = "تطبيق محمي"
    private var startedAt: Long = 0L
    private var proactive: Boolean = false

    /** آخر حزمة محمية رُصد دخولها في الوضع الاستباقي. */
    private var activeProtectedPkg: String? = null

    companion object {
        private const val TAG = "ProtectedAppWatcher"
        private const val NOTIFICATION_ID = 7002
        private const val CHANNEL_ID = "protected_app_watcher"
        private const val EXTRA_PKG = "extra_pkg"
        private const val EXTRA_LABEL = "extra_label"
        private const val EXTRA_PROACTIVE = "extra_proactive"
        private const val TWO_HOURS_MS = 2 * 60 * 60 * 1000L

        /**
         * فاصلُ الاستطلاع في الوضع الاستباقي. 250ms هامشٌ مريح داخل
         * نافذة الـ~2s المتاحة قبل شاشة القرار، مع استهلاكٍ معقول.
         */
        const val PROACTIVE_POLL_MS = 250L

        /** نافذةُ الاستعلام عن أحداث الاستخدام بالمللي ثانية. */
        private const val USAGE_WINDOW_MS = 2_000L

        /** تشغيل المراقب التفاعلي (خروج فقط) عند دخول تطبيق. */
        fun startForApp(context: Context, pkg: String, label: String) {
            val intent = Intent(
                context, ProtectedAppWatcherService::class.java
            ).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PROACTIVE, false)
            }
            startSafely(context, intent)
        }

        /**
         * تشغيل المراقب الاستباقي (دخول وخروج) — يُستدعى ما دام الخيار
         * مفعّلاً، ويعمل باستمرار حتى يُوقف صراحةً.
         */
        fun startProactive(context: Context) {
            val intent = Intent(
                context, ProtectedAppWatcherService::class.java
            ).apply {
                putExtra(EXTRA_PROACTIVE, true)
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        // مزجٌ متعقّل: وصول intent استباقي أثناء عملنا لا يحوّلنا تفاعلياً.
        val incomingProactive = intent?.getBooleanExtra(
            EXTRA_PROACTIVE, false
        ) == true
        proactive = proactive || incomingProactive
        watchingPackage = intent?.getStringExtra(EXTRA_PKG) ?: watchingPackage
        watchingLabel = intent?.getStringExtra(EXTRA_LABEL) ?: watchingLabel
        startedAt = System.currentTimeMillis()

        startForegroundNotification()
        if (proactive) {
            startProactiveLoop()
        } else {
            startReactiveLoop()
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "مراقبة التطبيقات المحمية",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "مراقبة التطبيقات المحمية لضبط إمكانية الوصول"
            }
            nm?.createNotificationChannel(channel)
        }

        val title = if (proactive) {
            "المراقبة الاستباقية للتطبيقات المحمية"
        } else {
            "مراقبة $watchingLabel"
        }
        val text = if (proactive) {
            "تُعطَّل إمكانية الوصول عند فتح تطبيق محمي (تستهلك البطارية)"
        } else {
            "سيتم استعادة إمكانية الوصول فور الخروج من التطبيق"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /** الوضع التفاعلي: مراقبة خروج تطبيقٍ واحدٍ ثم التوقف. */
    private fun startReactiveLoop() {
        val targetPkg = watchingPackage ?: return
        val targetLabel = watchingLabel

        scope.launch {
            val settings =
                SettingsRepository.create(this@ProtectedAppWatcherService)

            while (isActive) {
                delay(1000L)

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

                if (hasExited(targetPkg)) {
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
     * الوضع الاستباقي: استطلاعٌ سريع يكشف **دخول** أي تطبيقٍ محمي
     * فيعطّله قبل شاشة القرار، ويكشف **خروجه** فيعيد تفعيل الخدمة.
     * يبقى يعمل حتى يُوقف صراحةً أو يُلغى الخيار.
     */
    private fun startProactiveLoop() {
        scope.launch {
            val settings =
                SettingsRepository.create(this@ProtectedAppWatcherService)

            while (isActive) {
                delay(PROACTIVE_POLL_MS)

                // الخيار أُلغي أثناء العمل: أوقف الخدمة وأعد التفعيل.
                if (!settings.isProactiveProtectedWatchEnabled()) {
                    Log.i(TAG, "Proactive watch disabled. Stopping.")
                    val previous = activeProtectedPkg
                    if (previous != null) {
                        ProtectedAppManager.handleAppExit(
                            this@ProtectedAppWatcherService,
                            previous,
                            watchingLabel
                        )
                        activeProtectedPkg = null
                    }
                    stopSelf()
                    break
                }

                val current = currentForegroundProtectedApp(settings)
                val active = activeProtectedPkg

                if (current != null && current.packageName != active) {
                    // دخول تطبيقٍ محمي: عطّل فوراً قبل شاشة القرار.
                    Log.i(
                        TAG,
                        "Proactive entry: ${current.packageName}"
                    )
                    ProtectedAppManager.handleAppEntry(
                        this@ProtectedAppWatcherService,
                        null,
                        current
                    )
                    activeProtectedPkg = current.packageName
                    watchingLabel = current.label
                } else if (current == null && active != null) {
                    // خروج: أعد التفعيل.
                    Log.i(TAG, "Proactive exit detected: $active")
                    ProtectedAppManager.handleAppExit(
                        this@ProtectedAppWatcherService,
                        active,
                        watchingLabel
                    )
                    activeProtectedPkg = null
                }
            }
        }
    }

    /**
     * التطبيق المحمي الذي صار أماماً الآن (أو null). يجمع حزم
     * أحداث الرسم من `UsageStats` ثم يحسمها بالدالة النقية.
     */
    private fun currentForegroundProtectedApp(
        settings: SettingsRepository
    ): com.aymankhattab.nateq.core.data.ProtectedApp? {
        val now = System.currentTimeMillis()
        val usageManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val events = usageManager?.queryEvents(now - USAGE_WINDOW_MS, now)
            ?: return null
        val resumed = mutableListOf<String>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName.isNullOrBlank()) continue
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                resumed.add(event.packageName)
            }
        }
        return ProtectedAppManager.pickForegroundProtectedApp(
            resumedPackages = resumed,
            protectedApps = settings.getProtectedApps(),
            ownPackage = packageName
        )
    }

    /**
     * هل خرج تطبيقٌ محمي؟ يستعلم أحداث الاستخدام، فإن تعذّرت فحص
     * النشاط الأمامي. يُبقي قراءةً واحدةً لا حلقة استقصاء.
     */
    private fun hasExited(targetPkg: String): Boolean {
        val now = System.currentTimeMillis()
        val usageManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val events = usageManager?.queryEvents(now - 3000L, now)
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
        serviceJob.cancel()
        super.onDestroy()
    }
}
