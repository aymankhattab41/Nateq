package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aymankhattab.nateq.core.audio.announcement.NateqNotificationListener
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * مدير التطبيقات المحمية (إنستاباي والتطبيقات المصرفية) —
 * الجولة 48 و50 و51 (الأمر 1).
 */
object ProtectedAppManager {

    private const val TAG = "ProtectedAppManager"
    const val NOTIFICATION_ID_REENABLE = 7001
    const val CHANNEL_ID_PROTECTED = "protected_app_channel_v2"
    const val ACTION_RESTORE_ALARM =
        "com.aymankhattab.nateq.action.RESTORE_ALARM"
    private const val ALARM_REQUEST_CODE = 7003

    private val absentSinceMap = ConcurrentHashMap<String, Long>()
    @Volatile private var screenOffSinceMs = 0L

    data class AutoRestoreReadiness(
        val hasWriteSecure: Boolean,
        val hasUsageStats: Boolean,
        val isBatteryOptimizationsIgnored: Boolean,
        val hasNotificationPermission: Boolean,
        val isNotificationListenerEnabled: Boolean,
        val isAutostartGuided: Boolean
    ) {
        val canEnableFullDisable: Boolean
            get() = hasWriteSecure && hasUsageStats

        fun summaryString(): String =
            "WS=$hasWriteSecure, US=$hasUsageStats, " +
                "Bat=$isBatteryOptimizationsIgnored, " +
                "Notif=$hasNotificationPermission, " +
                "NL=$isNotificationListenerEnabled"
    }

    @androidx.annotation.VisibleForTesting
    @Volatile
    var readinessOverrideForTesting: AutoRestoreReadiness? = null

    /** فحص جاهزية الاسترجاع التلقائي (الجولة 51 - الأمر 1، البند 1). */
    fun checkReadiness(context: Context): AutoRestoreReadiness {
        readinessOverrideForTesting?.let { return it }
        val hasWriteSecure = context.checkSelfPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        val hasUsageStats = hasUsageStatsPermission(context)
        val pm = context.getSystemService(Context.POWER_SERVICE)
            as? PowerManager
        val isBatteryIgnored = pm?.isIgnoringBatteryOptimizations(
            context.packageName
        ) == true
        val notifGranted = NotificationManagerCompat.from(context)
            .areNotificationsEnabled()
        val notifListener = NateqNotificationListener.isPermissionGranted(
            context
        )
        return AutoRestoreReadiness(
            hasWriteSecure = hasWriteSecure,
            hasUsageStats = hasUsageStats,
            isBatteryOptimizationsIgnored = isBatteryIgnored,
            hasNotificationPermission = notifGranted,
            isNotificationListenerEnabled = notifListener,
            isAutostartGuided = true
        )
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isSuspended(context: Context): Boolean {
        val fromStore = ProtectedAppSuspensionStore.load(context)
        if (fromStore != null && fromStore.packageName.isNotBlank()) return true
        val fromRepo = SettingsRepository.create(context)
            .getSuspendedProtectedApp()
        return !fromRepo.isNullOrBlank()
    }

    /** جدولة المنبه الاحتياطي كل دقيقتين (البند 7). */
    fun scheduleBackupAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE)
            as? AlarmManager ?: return
        val intent = Intent(context, ProtectedAppRestoreReceiver::class.java)
            .apply { action = ACTION_RESTORE_ALARM }
        val pi = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAtMillis = System.currentTimeMillis() + 2 * 60 * 1000L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAtMillis, pi
            )
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    /** إلغاء المنبه الاحتياطي فور اكتمال الاسترجاع (البند 7). */
    fun cancelBackupAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE)
            as? AlarmManager ?: return
        val intent = Intent(context, ProtectedAppRestoreReceiver::class.java)
            .apply { action = ACTION_RESTORE_ALARM }
        val pi = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    /**
     * بثٌّداخليٌّ (من حزمتنا فقط) يطلب من الخدمة تعطيل نفسها — يُستخدم
     * حين لا يكون مثيل الخدمة معنا، كمسار «تشغيل عبر لورد».
     */
    const val ACTION_DISABLE_SELF =
        "com.aymankhattab.nateq.action.DISABLE_A11Y_SELF"

    /**
     * طلب تعطيل خدمة إمكانية الوصول من خارج الخدمة نفسها.
     */
    fun requestSelfDisable(context: Context) {
        val intent = Intent(ACTION_DISABLE_SELF)
            .setPackage(context.packageName)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "requestSelfDisable failed", it) }
    }

    /**
     * نافذة اعتماد علامة التعطيل: أحداثُ نافذةٍ أوائلية بعد disableSelf
     * تصل قبل فك الارتباط، فلا يُعاد التعطيل ولا يُعاد إطلاق التطبيق.
     */
    private const val FRESH_SUSPENSION_WINDOW_MS = 5_000L

    @Volatile
    var isPartiallySuspended: Boolean = false
        private set

    @Volatile
    var partiallySuspendedPkg: String? = null
        private set

    /**
     * حزمة مشغّل الشاشة الرئيسية الافتراضية إن وُجدت.
     */
    fun homeLauncherPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
    }

    /**
     * هل الحزمة تنتمي لمشغل الشاشة الرئيسية (Launcher / Home)؟
     */
    fun isHomeLauncher(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
        if (pkg == homeLauncherPackage(context)) return true

        val allHomes = runCatching {
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        if (allHomes.contains(pkg)) return true

        val lower = pkg.lowercase()
        return lower.contains("launcher") || lower.contains("home")
    }

    /**
     * اسم التطبيق كما يحمله حدث النقرة في المشغّل: نصُّ العقدة أو
     * وصفُها.
     */
    fun launcherCandidateLabel(event: AccessibilityEvent): String? {
        val fromText = event.text?.joinToString(" ")?.trim().orEmpty()
        if (fromText.isNotBlank()) return fromText
        val fromDesc = event.contentDescription?.toString()?.trim()
        if (!fromDesc.isNullOrBlank()) return fromDesc
        val source = runCatching { event.source }.getOrNull()
            ?: return null
        return nodeLabel(source, depth = 0)
    }

    private const val MAX_LABEL_NODE_DEPTH = 3

    internal fun nodeLabel(
        node: AccessibilityNodeInfo?,
        depth: Int
    ): String? {
        if (node == null || depth > MAX_LABEL_NODE_DEPTH) return null
        val ownText = node.text?.toString()?.trim().orEmpty()
        if (ownText.isNotBlank()) return ownText
        val ownDesc =
            node.contentDescription?.toString()?.trim().orEmpty()
        if (ownDesc.isNotBlank()) return ownDesc
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull()
            val label = nodeLabel(child, depth + 1)
            if (!label.isNullOrBlank()) return label
        }
        return null
    }

    fun effectiveEventPackages(
        allowed: Set<String>?,
        protectedApps: List<ProtectedApp>,
        homeLauncher: String?
    ): Set<String> {
        val base = (allowed ?: emptySet()).toMutableSet()
        if (protectedApps.isEmpty()) return base
        if (!homeLauncher.isNullOrBlank()) base += homeLauncher
        base += protectedApps.map { it.packageName }
        return base
    }

    fun matchProtectedApp(context: Context, text: String?): ProtectedApp? {
        if (text.isNullOrBlank()) return false.let { null }
        val settings = SettingsRepository.create(context)
        val protectedApps = settings.getProtectedApps()
        if (protectedApps.isEmpty()) return null

        val normText = TtsSettingsAutomation.normalizeForMatch(text)
        for (app in protectedApps) {
            val normLabel = TtsSettingsAutomation.normalizeForMatch(app.label)
            val normPkg =
                TtsSettingsAutomation.normalizeForMatch(app.packageName)
            if (normText == normLabel ||
                normText == normPkg ||
                normText.contains(normLabel) ||
                normLabel.contains(normText)
            ) {
                return app
            }
        }
        return null
    }

    fun pickForegroundProtectedApp(
        resumedPackages: List<String>,
        protectedApps: List<ProtectedApp>,
        ownPackage: String?
    ): ProtectedApp? {
        val foreground = resumedPackages.lastOrNull { it.isNotBlank() }
            ?: return null
        if (ownPackage != null && foreground == ownPackage) return null
        return protectedApps.firstOrNull {
            it.packageName == foreground
        }
    }

    /**
     * معالجة دخول التطبيق المحمي (تعليق كامل أو جزئي).
     */
    fun handleAppEntry(
        context: Context,
        service: NateqAccessibilityService?,
        app: ProtectedApp,
        relaunchIfForeground: Boolean = false
    ) {
        val settings = SettingsRepository.create(context)
        Log.i(
            TAG,
            "handleAppEntry: pkg=${app.packageName}, mode=${app.mode}"
        )

        val suspendedAt = settings.getSuspendedProtectedAppTimestamp()
        val isFreshSuspension =
            settings.getSuspendedProtectedApp() == app.packageName &&
                System.currentTimeMillis() - suspendedAt <
                FRESH_SUSPENSION_WINDOW_MS
        if (isFreshSuspension) {
            Log.i(TAG, "Already suspended for ${app.packageName}; ignored.")
            return
        }

        if (app.mode == ProtectedApp.MODE_PARTIAL_SUSPEND) {
            isPartiallySuspended = true
            partiallySuspendedPkg = app.packageName
            service?.applyServiceInfo()
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "تعليق جزئي لإمكانية الوصول من أجل ${app.label}",
                isInterruptAllowed = true
            )
            return
        }

        // فحص جاهزية الإيقاف الكامل (الجولة 51 - الأمر 1، البند 1)
        val readiness = checkReadiness(context)
        if (!readiness.canEnableFullDisable) {
            Log.w(
                TAG,
                "Cannot full disable: missing permissions. Falling back."
            )
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "تعذر الإيقاف الكامل لنقص الأذونات: " +
                    "تحويل إلى تعليق جزئي",
                isInterruptAllowed = true
            )
            isPartiallySuspended = true
            partiallySuspendedPkg = app.packageName
            service?.applyServiceInfo()
            return
        }

        // إيقاف كامل: حفظ في التخزين المحمي بالجهاز وفي المستودع
        val now = System.currentTimeMillis()
        ProtectedAppSuspensionStore.save(
            context = context,
            packageName = app.packageName,
            timestamp = now,
            mode = app.mode
        )
        settings.setSuspendedProtectedApp(app.packageName, now)

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = "تم تعطيل إمكانية الوصول لفتح ${app.label}",
            isInterruptAllowed = true
        )

        // تشغيل مراقب الخروج والمنبه الاحتياطي
        ProtectedAppWatcherService.startForApp(
            context = context,
            pkg = app.packageName,
            label = app.label
        )
        scheduleBackupAlarm(context)

        val disabledDirectly =
            disableAccessibilityServiceDirectly(context)
        if (!disabledDirectly) {
            if (service != null) {
                service.disableSelf()
            } else {
                requestSelfDisable(context)
            }
        }

        if (relaunchIfForeground) {
            relaunchProtectedApp(context, app)
        }
    }

    private fun relaunchProtectedApp(
        context: Context,
        app: ProtectedApp
    ) {
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(
                app.packageName
            )
        }.getOrNull()
        if (launch == null) {
            Log.w(TAG, "No launch intent for ${app.packageName}")
            return
        }
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        runCatching { context.startActivity(launch) }
            .onFailure {
                Log.w(TAG, "Relaunch failed for ${app.packageName}", it)
            }
    }

    /**
     * استعلام التطبيق الأمامي الحالي عبر UsageStatsManager أو Bridge.
     */
    fun getForegroundPackage(context: Context): String? {
        val bridgePkg = AccessibilityBridge.foregroundPackage()
        if (!bridgePkg.isNullOrBlank()) return bridgePkg
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = runCatching {
            usage.queryEvents(now - 10_000L, now)
        }.getOrNull() ?: return null
        var lastResumed: String? = null
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumed = ev.packageName
            }
        }
        return lastResumed
    }

    /**
     * الدالة الموحدة idempotent لكشف الخروج وإعادة تفعيل الخدمة
     * (الجولة 51 - الأمر 1، البنود 2، 3، 5، 6).
     */
    fun checkAndRestore(
        context: Context,
        reason: String,
        pollIntervalMs: Long = 200L,
        maxWaitMs: Long = 4000L,
        nudgeWaitMs: Long = 400L,
        targetPkg: String? = null
    ): Boolean {
        val suspension = ProtectedAppSuspensionStore.load(context)
        val suspendedPkg = suspension?.packageName
            ?: SettingsRepository.create(context).getSuspendedProtectedApp()
            ?: targetPkg
        if (suspendedPkg.isNullOrBlank()) {
            cancelBackupAlarm(context)
            ProtectedAppWatcherService.stopService(context)
            return true
        }

        val now = System.currentTimeMillis()
        val isForce = reason in setOf(
            "force", "user", "boot", "settings", "exit"
        )

        if (!isForce) {
            val pm = context.getSystemService(Context.POWER_SERVICE)
                as? PowerManager
            val isScreenInteractive = pm?.isInteractive ?: true
            val currentFg = getForegroundPackage(context)

            // إن كان المستخدم لا يزال داخل التطبيق والشاشة مضاءة: لا يُعاد
            if (isScreenInteractive && currentFg == suspendedPkg) {
                absentSinceMap.remove(suspendedPkg)
                screenOffSinceMs = 0L
                return false
            }

            var conditionMet = false

            // قاعدة (أ): غاب التطبيق 3 ثوانٍ متصلة
            if (currentFg != suspendedPkg) {
                val absentStart = absentSinceMap.getOrPut(suspendedPkg) { now }
                if (now - absentStart >= 3000L) {
                    conditionMet = true
                }
            } else {
                absentSinceMap.remove(suspendedPkg)
            }

            // قاعدة (ب): أطفئت الشاشة 20 ثانية متصلة
            if (!isScreenInteractive) {
                if (screenOffSinceMs == 0L) screenOffSinceMs = now
                if (now - screenOffSinceMs >= 20_000L) {
                    conditionMet = true
                }
            } else {
                screenOffSinceMs = 0L
            }

            // قاعدة (ج): سقف مطلق قابل للضبط منذ الإيقاف
            val suspendedAt = suspension?.timestamp
                ?: SettingsRepository.create(context)
                    .getSuspendedProtectedAppTimestamp()
            val timeoutMinutes = SettingsRepository.create(context)
                .getProtectedAppAutoRestoreTimeoutMinutes()
            val timeoutMs = timeoutMinutes * 60_000L
            if (suspendedAt > 0L && now - suspendedAt >= timeoutMs) {
                conditionMet = true
            }

            if (!conditionMet) {
                return false
            }
        }

        // التحقق من الإذن وإعادة التفعيل
        val readiness = checkReadiness(context)
        if (!readiness.hasWriteSecure) {
            RestoreDiagnosticsTracker.record(
                context = context,
                reason = reason,
                readinessSummary = readiness.summaryString(),
                writeResult = false,
                serviceConnected = false,
                nudgeAttempts = 0,
                fallbackReason = "WRITE_SECURE_SETTINGS missing"
            )
            postReenableNotification(context, suspendedPkg)
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "انتهيت من استخدام التطبيق، اضغط زرّي الصوت",
                isInterruptAllowed = true
            )
            return false
        }

        val initialWrite = enableAccessibilityServiceDirectly(context)
        if (!initialWrite) {
            RestoreDiagnosticsTracker.record(
                context = context,
                reason = reason,
                readinessSummary = readiness.summaryString(),
                writeResult = false,
                serviceConnected = false,
                nudgeAttempts = 0,
                fallbackReason = "enableAccessibilityServiceDirectly failed"
            )
            postReenableNotification(context, suspendedPkg)
            return false
        }

        // تحقق الاتصال بعد الكتابة مع انتظار حتى 4 ثوانٍ (البند 5)
        var connected = waitForActiveService(maxWaitMs, pollIntervalMs)
        var nudgeCount = 0

        if (!connected) {
            // تحريك حتى مرتين: حذف، انتظار 400ms، إعادة إضافة، انتظار 4s
            for (attempt in 1..2) {
                nudgeCount = attempt
                disableAccessibilityServiceDirectly(context)
                if (nudgeWaitMs > 0) {
                    try {
                        Thread.sleep(nudgeWaitMs)
                    } catch (_: InterruptedException) {}
                }
                enableAccessibilityServiceDirectly(context)
                connected = waitForActiveService(maxWaitMs, pollIntervalMs)
                if (connected) break
            }
        }

        if (connected) {
            RestoreDiagnosticsTracker.record(
                context = context,
                reason = reason,
                readinessSummary = readiness.summaryString(),
                writeResult = true,
                serviceConnected = true,
                nudgeAttempts = nudgeCount,
                fallbackReason = null
            )
            ProtectedAppSuspensionStore.clear(context)
            SettingsRepository.create(context).setSuspendedProtectedApp(null)
            cancelBackupAlarm(context)
            ProtectedAppWatcherService.stopService(context)
            absentSinceMap.remove(suspendedPkg)
            screenOffSinceMs = 0L

            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "عادت إمكانية الوصول",
                isInterruptAllowed = true
            )
            return true
        } else {
            RestoreDiagnosticsTracker.record(
                context = context,
                reason = reason,
                readinessSummary = readiness.summaryString(),
                writeResult = true,
                serviceConnected = false,
                nudgeAttempts = nudgeCount,
                fallbackReason = "لم تتصل الخدمة بعد $nudgeCount تحريك"
            )
            postReenableNotification(context, suspendedPkg)
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "تعذر إعادة تفعيل الخدمة تلقائياً، اضغط زرّي الصوت",
                isInterruptAllowed = true
            )
            return false
        }
    }

    private fun waitForActiveService(
        maxWaitMs: Long,
        pollIntervalMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() <= deadline) {
            if (AccessibilityBridge.activeService() != null) {
                return true
            }
            if (pollIntervalMs > 0) {
                try {
                    Thread.sleep(pollIntervalMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return AccessibilityBridge.activeService() != null
    }

    /**
     * معالجة الخروج من التطبيق المحمي واستعادة الخدمة.
     */
    fun handleAppExit(context: Context, pkg: String, label: String) {
        Log.i(TAG, "handleAppExit: pkg=$pkg, label=$label")

        if (isPartiallySuspended && partiallySuspendedPkg == pkg) {
            isPartiallySuspended = false
            partiallySuspendedPkg = null
            return
        }

        ProtectedAppWatcherService.stopService(context)
        checkAndRestore(context, "exit", targetPkg = pkg)
    }

    /**
     * إعادة تفعيل خدمة إمكانية الوصول عبر Settings.Secure
     * مع الحفاظ على الخدمات الأخرى دون حذف (الأمر 7).
     */
    fun enableAccessibilityServiceDirectly(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val serviceName = OUR_SERVICE_NAME
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val services = current.split(":")
                .filter { it.isNotBlank() }
                .toMutableList()
            if (services.none { isOurService(it) }) {
                services.add(serviceName)
            }
            val newString = services.joinToString(":")
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newString
            )
            Settings.Secure.putInt(
                cr,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            val verified = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            verified.split(":").any { isOurService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "enableAccessibilityServiceDirectly failed", e)
            false
        }
    }

    private const val OUR_SERVICE_NAME =
        "com.aymankhattab.nateq/.core.audio.accessibility" +
            ".NateqAccessibilityService"

    private fun isOurService(entry: String): Boolean {
        val norm = entry.replace('/', '.').trim()
        val ours = OUR_SERVICE_NAME.replace('/', '.')
        return norm == ours ||
            norm == "com.aymankhattab.nateq" +
            ".com.aymankhattab.nateq.core.audio.accessibility" +
            ".NateqAccessibilityService"
    }

    fun disableAccessibilityServiceDirectly(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val services = current.split(":")
                .filter { it.isNotBlank() && !isOurService(it) }
            val newString = services.joinToString(":")
            Settings.Secure.putString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newString
            )
            val verified = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            verified.split(":").none { isOurService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "disableAccessibilityServiceDirectly failed", e)
            false
        }
    }

    private fun postReenableNotification(context: Context, appLabel: String) {
        val nm = context.getSystemService(
            NotificationManager::class.java
        ) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.deleteNotificationChannel("protected_app_channel")
            val channel = NotificationChannel(
                CHANNEL_ID_PROTECTED,
                "إعادة تشغيل إمكانية الوصول",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "تنبيه لإعادة تشغيل الخدمة بعد الخروج من التطبيق المحمي"
                enableVibration(false)
                vibrationPattern = null
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_REENABLE,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(
            context,
            CHANNEL_ID_PROTECTED
        )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("انتهيت من $appLabel")
            .setContentText(
                "اضغط زرّي الصوت لإعادة تشغيل Lord أو اضغط لفتح الإعدادات"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(null)
            .setDefaults(0)
            .addAction(
                android.R.drawable.ic_menu_manage,
                "فتح الإعدادات",
                pendingIntent
            )

        nm.notify(NOTIFICATION_ID_REENABLE, builder.build())
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        isPartiallySuspended = false
        partiallySuspendedPkg = null
        absentSinceMap.clear()
        screenOffSinceMs = 0L
        readinessOverrideForTesting = null
    }
}
