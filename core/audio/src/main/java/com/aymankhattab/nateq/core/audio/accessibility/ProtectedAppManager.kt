package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.announcement.NateqNotificationListener
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    const val ACTION_A11Y_CONNECTED =
        "com.aymankhattab.nateq.action.A11Y_CONNECTED"
    const val ACTION_A11Y_DISCONNECTED =
        "com.aymankhattab.nateq.action.A11Y_DISCONNECTED"
    private const val ALARM_REQUEST_CODE = 7003

    private val isRestoring = AtomicBoolean(false)
    @Volatile private var nudgedThisSuspension = false
    @Volatile private var lastNudgeTimeMs = 0L
    @Volatile private var consecutiveFailures = 0
    @Volatile private var nonProtectedObservations = 0

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

    /** فحص اتصال الخدمة عبر العمليات (الأمر 1 - البند 1). */
    fun isServiceConnected(context: Context): Boolean {
        if (AccessibilityBridge.activeService() != null) return true
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ) ?: return false
        val ourPkg = context.packageName
        return enabled.any {
            val sInfo = it.resolveInfo?.serviceInfo
            sInfo?.packageName == ourPkg &&
                (sInfo.name == NateqAccessibilityService::class.java.name ||
                    sInfo.name.endsWith("NateqAccessibilityService"))
        }
    }

    /** فحص وجود الخدمة في Settings.Secure (الأمر 1 - البند 1). */
    fun isServiceEnabledInSettings(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            current.split(":")
                .filter { it.isNotBlank() }
                .any { isOurService(it) }
        } catch (_: Exception) {
            false
        }
    }

    fun resetStateAfterRestore() {
        nudgedThisSuspension = false
        consecutiveFailures = 0
        nonProtectedObservations = 0
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

    /** نطق رسائل Lord النظامية عبر مسار الأحداث (الأمر 3 - البند 2). */
    fun speakLordSystem(context: Context, text: String) {
        runCatching {
            val speaker = AnnouncementSpeaker.getInstance(context)
            val settings = SettingsRepository.create(context)
            val cat = SettingsRepository.VOICE_CATEGORY_LORD_SYSTEM
            val voiceId = settings.getPreferredVoiceIdForCategory(cat)
                ?: settings.getPreferredVoiceIdForCategory(
                    SettingsRepository.VOICE_CATEGORY_DEFAULT
                )
            val rate = settings.getSpeechRateForCategory(cat)
            val pitch = settings.getPitchForCategory(cat)
            val volume = settings.getVolumeForCategory(cat)
            val engine = settings.getEngineForCategory(cat)
                ?: settings.getEngineForCategory(
                    SettingsRepository.VOICE_CATEGORY_DEFAULT
                )
            if (voiceId != null) {
                speaker.resetVoice(voiceId)
            }
            speaker.speak(
                text = text,
                locale = java.util.Locale.getDefault(),
                speechRate = rate,
                pitch = pitch,
                volume = volume,
                engineOverride = engine,
                category = cat
            )
        }.onFailure {
            Log.w(TAG, "speakLordSystem failed", it)
        }
    }

    /** تحصين أندرويد 17 أثناء جلسة التطبيق المحمي (الأمر 2 - البند 5). */
    fun applyAndroid17Hardening(context: Context) {
        NateqNotificationListener.unbindSafely()
        AnnouncementSchedulerService.stopService(context)
    }

    /** استعادة ما عُطّل بتحصين أندرويد 17 (الأمر 2 - البند 5). */
    fun revertAndroid17Hardening(context: Context) {
        NateqNotificationListener.rebindSafely(context)
        AnnouncementSchedulerService.startIfNeeded(context)
    }

    /** إعادة المحاولة التلقائية للتطبيق مرة واحدة (الأمر 2 - البند 4). */
    fun scheduleAppAutoRetry(context: Context, targetPkg: String) {
        CoroutineScope(Dispatchers.Default).launch {
            var retried = false
            delay(4000L)
            val fg4s = getForegroundPackage(context)
            if (isHomeLauncher(context, fg4s) && isSuspended(context)) {
                retried = true
                relaunchProtectedAppDirectly(
                    context, targetPkg, "العودة للمشغل خلال 4s"
                )
                return@launch
            }
            delay(2000L)
            val fg6s = getForegroundPackage(context)
            if (fg6s != targetPkg && isSuspended(context) && !retried) {
                relaunchProtectedAppDirectly(
                    context, targetPkg, "لم تظهر النافذة خلال 6s"
                )
            }
        }
    }

    private fun relaunchProtectedAppDirectly(
        context: Context,
        targetPkg: String,
        reason: String
    ) {
        ProtectedAppTimelineTracker.recordWarning(
            targetPkg, "إعادة فتح تلقائية واحدة: $reason"
        )
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(targetPkg) ?: return
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        runCatching { context.startActivity(launchIntent) }
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
        ProtectedAppTimelineTracker.recordDeviceFingerprint(
            context,
            app.packageName
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
            speakLordSystem(
                context = context,
                text = "تعليق جزئي لإمكانية الوصول من أجل ${app.label}"
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
            speakLordSystem(
                context = context,
                text = "تعذر الإيقاف الكامل لنقص الأذونات: " +
                    "تحويل إلى تعليق جزئي"
            )
            isPartiallySuspended = true
            partiallySuspendedPkg = app.packageName
            service?.applyServiceInfo()
            return
        }

        // إيقاف كامل: حفظ في التخزين المحمي بالجهاز وفي المستودع
        val now = System.currentTimeMillis()
        ForegroundTracker.reset(app.packageName, now)
        nonProtectedObservations = 0
        ProtectedAppSuspensionStore.save(
            context = context,
            packageName = app.packageName,
            timestamp = now,
            mode = app.mode
        )
        settings.setSuspendedProtectedApp(app.packageName, now)

        speakLordSystem(
            context = context,
            text = "تم تعطيل إمكانية الوصول لفتح ${app.label}"
        )

        // تطبيق تحصين أندرويد 17 أثناء الجلسة (الأمر 2 - البند 5)
        if (settings.isAndroid17BankingHardeningEnabled()) {
            applyAndroid17Hardening(context)
        }

        // تشغيل مراقب الخروج والمنبه الاحتياطي
        ProtectedAppWatcherService.startForApp(
            context = context,
            pkg = app.packageName,
            label = app.label
        )
        scheduleBackupAlarm(context)

        // الأمر 2 - البند 1: تعطيل حتمي متزامن يسبق أي شيء
        disableAccessibilityServiceDirectly(context)
        if (service != null) {
            service.disableSelf()
        } else {
            requestSelfDisable(context)
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
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        runCatching { context.startActivity(launch) }
            .onFailure {
                Log.w(TAG, "Relaunch failed for ${app.packageName}", it)
            }
    }

    /**
     * متتبع التطبيق الأمامي بحالة تراكمية مستمرة (الجولة 53 - الأمر 1).
     * يمنع خطأ نافذة 10 ثوانٍ التي كانت تعيد null وتسبب استرجاعاً مبكراً.
     */
    object ForegroundTracker {
        @Volatile private var currentForegroundPkg: String? = null
        @Volatile private var lastQueryEndMs: Long = 0L
        @Volatile private var isTemporarilyUnknown: Boolean = false

        fun reset(
            initialPkg: String? = null,
            startTimeMs: Long = System.currentTimeMillis()
        ) {
            currentForegroundPkg = initialPkg
            lastQueryEndMs = startTimeMs
            isTemporarilyUnknown = false
        }

        fun getForegroundPackage(context: Context): String? {
            val bridgePkg = AccessibilityBridge.foregroundPackage()
            if (!bridgePkg.isNullOrBlank()) {
                currentForegroundPkg = bridgePkg
                isTemporarilyUnknown = false
                return bridgePkg
            }
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return currentForegroundPkg

            val now = System.currentTimeMillis()
            val start = if (lastQueryEndMs > 0L) {
                maxOf(lastQueryEndMs - 1000L, now - 300_000L)
            } else {
                now - 10_000L
            }
            lastQueryEndMs = now

            val events = runCatching {
                usage.queryEvents(start, now)
            }.getOrNull()

            if (events != null) {
                val ev = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    when (ev.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            currentForegroundPkg = ev.packageName
                            isTemporarilyUnknown = false
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            if (ev.packageName == currentForegroundPkg) {
                                isTemporarilyUnknown = true
                            }
                        }
                    }
                }
            }
            return currentForegroundPkg
        }

        fun isForeground(pkg: String): Boolean =
            currentForegroundPkg == pkg && !isTemporarilyUnknown

        fun currentPackage(): String? = currentForegroundPkg
        fun isUnknown(): Boolean = isTemporarilyUnknown

        @androidx.annotation.VisibleForTesting
        fun setForTesting(pkg: String?, isUnknown: Boolean = false) {
            currentForegroundPkg = pkg
            isTemporarilyUnknown = isUnknown
        }
    }

    /**
     * استعلام التطبيق الأمامي الحالي عبر ForegroundTracker أو Bridge.
     */
    fun getForegroundPackage(context: Context): String? =
        ForegroundTracker.getForegroundPackage(context)

    /**
     * الدالة الموحدة idempotent لكشف الخروج وإعادة تفعيل الخدمة
     * (الجولة 51 والجولة 52 - الأمر 1).
     */
    suspend fun checkAndRestore(
        context: Context,
        reason: String,
        pollIntervalMs: Long = 100L,
        maxWaitMs: Long = 3000L,
        nudgeWaitMs: Long = 250L,
        targetPkg: String? = null
    ): Boolean {
        if (!isRestoring.compareAndSet(false, true)) {
            Log.i(TAG, "checkAndRestore already running; skipping.")
            return false
        }
        try {
            val suspension = ProtectedAppSuspensionStore.load(context)
            val suspendedPkg = suspension?.packageName
                ?: SettingsRepository.create(context).getSuspendedProtectedApp()
                ?: targetPkg
            if (suspendedPkg.isNullOrBlank()) {
                cancelBackupAlarm(context)
                ProtectedAppWatcherService.stopService(context)
                resetStateAfterRestore()
                return true
            }

            val connectedBefore = isServiceConnected(context)
            val enabledBefore = isServiceEnabledInSettings(context)

            // أول سطر في checkAndRestore (الأمر 1 - البند 5):
            // إن كانت الخدمة متصلة ومفعّلة في الإعدادات فامسح حالة الإيقاف
            // وألغِ المنبّه وأوقف المراقب واخرج دون كتابة على Settings.Secure
            if (connectedBefore && enabledBefore) {
                RestoreDiagnosticsTracker.record(
                    context = context,
                    reason = reason,
                    readinessSummary = "Already connected & enabled",
                    writeResult = false,
                    serviceConnected = true,
                    nudgeAttempts = 0,
                    fallbackReason = null,
                    connectedBefore = true,
                    enabledBefore = true,
                    connectedAfter = true,
                    enabledAfter = true
                )
                ProtectedAppSuspensionStore.clear(context)
                SettingsRepository.create(context)
                    .setSuspendedProtectedApp(null)
                cancelBackupAlarm(context)
                ProtectedAppWatcherService.stopService(context)
                absentSinceMap.remove(suspendedPkg)
                screenOffSinceMs = 0L
                resetStateAfterRestore()
                if (SettingsRepository.create(context)
                        .isAndroid17BankingHardeningEnabled()
                ) {
                    revertAndroid17Hardening(context)
                }
                return true
            }

            val now = System.currentTimeMillis()
            val isForce = reason in setOf(
                "force", "user", "boot", "settings", "exit"
            )

            var tExitDetected = 0L
            var tWriteDone = 0L
            var tConnected = 0L

            if (!isForce) {
                val pm = context.getSystemService(Context.POWER_SERVICE)
                    as? PowerManager
                val isScreenInteractive = pm?.isInteractive ?: true
                val currentFg = getForegroundPackage(context)

                // الأمر 1 - البند 2: منع الاسترجاع ما دام التطبيق
                // المحمي هو الأمامي
                if (isScreenInteractive &&
                    ForegroundTracker.isForeground(suspendedPkg)
                ) {
                    absentSinceMap.remove(suspendedPkg)
                    screenOffSinceMs = 0L
                    nonProtectedObservations = 0
                    return false
                }

                var conditionMet = false

                // الأمر 1 - البند 4: انتقال فوري عند
                // الانتقال إلى المشغل (Home Launcher)
                if (isHomeLauncher(context, currentFg)) {
                    conditionMet = true
                } else if (currentFg != suspendedPkg) {
                    nonProtectedObservations++
                    val absentStart =
                        absentSinceMap.getOrPut(suspendedPkg) { now }
                    // الأمر 1 - البند 3: خفض شرط الغياب إلى 1200ms
                    // مع ملاحظتين متتاليتين
                    if (now - absentStart >= 1200L &&
                        nonProtectedObservations >= 2
                    ) {
                        conditionMet = true
                    }
                } else {
                    nonProtectedObservations = 0
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
                tExitDetected = System.currentTimeMillis()
                ProtectedAppTimelineTracker.recordRestorePhase(
                    "تحقق شرط الخروج",
                    suspendedPkg,
                    "fg=$currentFg, reason=$reason"
                )
            } else {
                tExitDetected = System.currentTimeMillis()
            }

            // التحقق من الإذن وإعادة التفعيل
            val readiness = checkReadiness(context)
            if (!readiness.hasWriteSecure) {
                handleRestoreFailure(
                    context = context,
                    suspendedPkg = suspendedPkg,
                    reason = reason,
                    readiness = readiness,
                    connectedBefore = connectedBefore,
                    enabledBefore = enabledBefore,
                    connectedAfter = false,
                    enabledAfter = enabledBefore,
                    nudgeCount = 0,
                    nudgeReason = null,
                    error = "WRITE_SECURE_SETTINGS missing",
                    tExitDetected = tExitDetected,
                    tWriteDone = tWriteDone,
                    tConnected = tConnected
                )
                return false
            }

            var writePerformed = false
            var connected = false
            var nudgeCount = 0
            var nudgeReasonTag: String? = null

            if (!enabledBefore) {
                // الأمر 1 - البند 7: حالة غير موجودة في الإعدادات
                val writeOk = enableAccessibilityServiceDirectly(context)
                tWriteDone = System.currentTimeMillis()
                writePerformed = true
                ProtectedAppTimelineTracker.recordRestorePhase(
                    "كتابة Settings.Secure",
                    suspendedPkg,
                    "writeOk=$writeOk"
                )
                if (!writeOk) {
                    handleRestoreFailure(
                        context = context,
                        suspendedPkg = suspendedPkg,
                        reason = reason,
                        readiness = readiness,
                        connectedBefore = connectedBefore,
                        enabledBefore = false,
                        connectedAfter = false,
                        enabledAfter = false,
                        nudgeCount = 0,
                        nudgeReason = null,
                        error = "enableAccessibilityServiceDirectly failed",
                        tExitDetected = tExitDetected,
                        tWriteDone = tWriteDone,
                        tConnected = tConnected
                    )
                    return false
                }
                // انتظر الاتصال (استطلاع) بلا تحريك قبل ذلك
                connected = waitForActiveService(
                    context, maxWaitMs, pollIntervalMs
                )
                if (connected) {
                    tConnected = System.currentTimeMillis()
                    ProtectedAppTimelineTracker.recordRestorePhase(
                        "اتصال الخدمة",
                        suspendedPkg,
                        "delay=${tConnected - tWriteDone}ms"
                    )
                }
            } else {
                // الخدمة موجودة في الإعدادات ولكن غير متصلة
                tWriteDone = System.currentTimeMillis()
                connected = waitForActiveService(
                    context, maxWaitMs, pollIntervalMs
                )
                if (connected) {
                    tConnected = System.currentTimeMillis()
                } else {
                    // الأمر 1 - البند 5: «التحريك» فوراً بعد 3s
                    val currentFg = getForegroundPackage(context)
                    val fgOk = currentFg != suspendedPkg
                    val reasonOk = reason in setOf(
                        "exit", "alarm", "force", "watcher"
                    )
                    val timeOk = now - lastNudgeTimeMs >= 60_000L
                    val countOk = !nudgedThisSuspension

                    if (fgOk && reasonOk && timeOk && countOk) {
                        nudgeCount = 1
                        nudgedThisSuspension = true
                        lastNudgeTimeMs = now
                        nudgeReasonTag = "مكتوبة وغير متصلة بعد 3s ($reason)"
                        disableAccessibilityServiceDirectly(context)
                        delay(nudgeWaitMs)
                        enableAccessibilityServiceDirectly(context)
                        tWriteDone = System.currentTimeMillis()
                        writePerformed = true
                        ProtectedAppTimelineTracker.recordRestorePhase(
                            "تحريك الخدمة",
                            suspendedPkg,
                            "nudgeWait=${nudgeWaitMs}ms"
                        )
                        connected = waitForActiveService(
                            context, 4000L, 100L
                        )
                        if (connected) {
                            tConnected = System.currentTimeMillis()
                        }
                    }
                }
            }

            val connectedAfter = isServiceConnected(context)
            val enabledAfter = isServiceEnabledInSettings(context)

            if (connected || connectedAfter) {
                if (tConnected == 0L) tConnected = System.currentTimeMillis()
                consecutiveFailures = 0
                resetStateAfterRestore()
                ProtectedAppSuspensionStore.clear(context)
                SettingsRepository.create(context)
                    .setSuspendedProtectedApp(null)
                cancelBackupAlarm(context)
                ProtectedAppWatcherService.stopService(context)
                absentSinceMap.remove(suspendedPkg)
                screenOffSinceMs = 0L
                nonProtectedObservations = 0

                if (SettingsRepository.create(context)
                        .isAndroid17BankingHardeningEnabled()
                ) {
                    revertAndroid17Hardening(context)
                }

                RestoreDiagnosticsTracker.record(
                    context = context,
                    reason = reason,
                    readinessSummary = readiness.summaryString(),
                    writeResult = writePerformed,
                    serviceConnected = true,
                    nudgeAttempts = nudgeCount,
                    fallbackReason = null,
                    connectedBefore = connectedBefore,
                    enabledBefore = enabledBefore,
                    connectedAfter = true,
                    enabledAfter = enabledAfter,
                    nudgeReason = nudgeReasonTag,
                    failureAnnounced = false,
                    tExitDetected = tExitDetected,
                    tWriteDone = tWriteDone,
                    tConnected = tConnected
                )

                if (reason != "screen") {
                    speakLordSystem(context, "عادت إمكانية الوصول")
                }
                return true
            } else {
                val errMsg = if (nudgeCount > 0) {
                    "لم تتصل الخدمة بعد $nudgeCount تحريك"
                } else "لم تتصل الخدمة بعد الكتابة"
                handleRestoreFailure(
                    context = context,
                    suspendedPkg = suspendedPkg,
                    reason = reason,
                    readiness = readiness,
                    connectedBefore = connectedBefore,
                    enabledBefore = enabledBefore,
                    connectedAfter = false,
                    enabledAfter = enabledAfter,
                    nudgeCount = nudgeCount,
                    nudgeReason = nudgeReasonTag,
                    error = errMsg,
                    tExitDetected = tExitDetected,
                    tWriteDone = tWriteDone,
                    tConnected = tConnected
                )
                return false
            }
        } finally {
            isRestoring.set(false)
        }
    }

    private fun handleRestoreFailure(
        context: Context,
        suspendedPkg: String,
        reason: String,
        readiness: AutoRestoreReadiness,
        connectedBefore: Boolean,
        enabledBefore: Boolean,
        connectedAfter: Boolean,
        enabledAfter: Boolean,
        nudgeCount: Int,
        nudgeReason: String?,
        error: String,
        tExitDetected: Long = 0L,
        tWriteDone: Long = 0L,
        tConnected: Long = 0L
    ) {
        consecutiveFailures++
        val alreadyAnnounced =
            ProtectedAppSuspensionStore.isFailureAnnounced(context)
        var newlyAnnounced = false
        // الأمر 1 - البند 8 و9: إعلان الفشل مرة واحدة ولا نطق لأحداث الشاشة
        if (reason != "screen" && !alreadyAnnounced) {
            newlyAnnounced = true
            ProtectedAppSuspensionStore.setFailureAnnounced(context, true)
            postReenableNotification(context, suspendedPkg)
            speakLordSystem(
                context, "تعذر إعادة تفعيل الخدمة تلقائياً، اضغط زرّي الصوت"
            )
        }
        // بعد 3 محاولات فاشلة متتالية أوقف المنبه الاحتياطي
        if (consecutiveFailures >= 3) {
            cancelBackupAlarm(context)
        }
        RestoreDiagnosticsTracker.record(
            context = context,
            reason = reason,
            readinessSummary = readiness.summaryString(),
            writeResult = true,
            serviceConnected = false,
            nudgeAttempts = nudgeCount,
            fallbackReason = error,
            connectedBefore = connectedBefore,
            enabledBefore = enabledBefore,
            connectedAfter = connectedAfter,
            enabledAfter = enabledAfter,
            nudgeReason = nudgeReason,
            failureAnnounced = newlyAnnounced,
            tExitDetected = tExitDetected,
            tWriteDone = tWriteDone,
            tConnected = tConnected
        )
    }

    private suspend fun waitForActiveService(
        context: Context,
        maxWaitMs: Long,
        pollIntervalMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() <= deadline) {
            if (isServiceConnected(context)) {
                return true
            }
            if (pollIntervalMs > 0) {
                delay(pollIntervalMs)
            }
        }
        return isServiceConnected(context)
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
        kotlinx.coroutines.runBlocking {
            checkAndRestore(
                context = context,
                reason = "exit",
                pollIntervalMs = 50L,
                maxWaitMs = 200L,
                nudgeWaitMs = 50L,
                targetPkg = pkg
            )
        }
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
            ".NateqAccessibilityService" ||
            (norm.endsWith(".NateqAccessibilityService") &&
                norm.contains("aymankhattab.nateq"))
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
        nudgedThisSuspension = false
        lastNudgeTimeMs = 0L
        consecutiveFailures = 0
        nonProtectedObservations = 0
        ForegroundTracker.reset()
        isRestoring.set(false)
    }
}
