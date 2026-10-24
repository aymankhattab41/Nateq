package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * متتبع الخط الزمني وفترة الصمت التام للتطبيقات المحمية (الأمر 6 و52).
 */
object ProtectedAppTimelineTracker {

    data class TimelineEvent(
        val eventName: String,
        val packageName: String,
        val timestamp: Long,
        val elapsedFromStartMs: Long,
        val details: String = ""
    )

    data class Session(
        val packageName: String,
        val startTimeMs: Long,
        val events: MutableList<TimelineEvent> = mutableListOf()
    )

    private val sessions = mutableListOf<Session>()
    private const val MAX_SESSIONS = 3

    private val events = mutableListOf<TimelineEvent>()
    private var startTimestamp = 0L

    @Volatile
    private var quietUntilUptimeMs = 0L

    @Volatile
    private var activeProtectedPkg: String? = null

    private const val QUIET_PERIOD_WINDOW_MS = 10_000L

    private val BIOMETRIC_PACKAGES = setOf(
        "com.android.systemui",
        "com.samsung.android.biometrics",
        "com.google.android.systemui"
    )

    @Synchronized
    fun recordLauncherClick(pkg: String) {
        val now = System.currentTimeMillis()
        startTimestamp = now
        val session = Session(
            packageName = pkg,
            startTimeMs = now
        )
        if (sessions.size >= MAX_SESSIONS) {
            sessions.removeAt(0)
        }
        sessions.add(session)
        events.clear()
        val firstEvent = TimelineEvent(
            eventName = "النقر على أيقونة الإطلاق",
            packageName = pkg,
            timestamp = now,
            elapsedFromStartMs = 0L
        )
        events.add(firstEvent)
        session.events.add(firstEvent)
        startQuietPeriod(pkg)
    }

    @Synchronized
    fun recordLordDetected(pkg: String) {
        recordInternal("اكتشاف Lord للتطبيق المحمي", pkg)
        startQuietPeriod(pkg)
    }

    @Synchronized
    fun recordDisableSelfCalled(pkg: String) {
        recordInternal("استدعاء disableSelf", pkg)
    }

    @Synchronized
    fun recordDisabledConfirmed(pkg: String, pollElapsedMs: Long) {
        recordInternal(
            "تأكيد التوقف الفعلي للخدمة",
            pkg,
            "استغرق التحقق: ${pollElapsedMs}ms"
        )
    }

    @Synchronized
    fun recordWarning(pkg: String, warning: String) {
        recordInternal("تحذير", pkg, warning)
    }

    @Synchronized
    fun recordRestorePhase(name: String, pkg: String, details: String = "") {
        recordInternal(name, pkg, details)
    }

    @Synchronized
    fun recordAppWindowAppeared(pkg: String) {
        recordInternal("ظهور أول نافذة للتطبيق", pkg)
        extendQuietPeriod(pkg)
    }

    @Synchronized
    fun recordBiometricWindowAppeared(pkg: String) {
        recordInternal("ظهور نافذة البصمة", pkg)
        // تمديد فترة الصمت 10 ثوانٍ من ظهور البصمة
        quietUntilUptimeMs = SystemClock.uptimeMillis() +
            QUIET_PERIOD_WINDOW_MS
    }

    @Synchronized
    fun recordBiometricFinished(pkg: String, success: Boolean) {
        val res = if (success) "نجاح" else "فشل/إلغاء"
        recordInternal("نتيجة البصمة ($res)", pkg)
    }

    @Synchronized
    fun recordDeviceFingerprint(context: Context, pkg: String = "system") {
        val cr = context.contentResolver
        val a11yEnabled = runCatching {
            Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        }.getOrDefault(0)

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager
        val a11yServices = runCatching {
            am?.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.joinToString(", ") { info ->
                val p = info.resolveInfo?.serviceInfo?.packageName ?: "?"
                val isTool = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    info.isAccessibilityTool
                } else false
                "$p(tool=$isTool)"
            }
        }.getOrNull() ?: "none"

        val touchExploration = am?.isTouchExplorationEnabled ?: false

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        val imes = runCatching {
            imm?.enabledInputMethodList?.map { it.id }?.joinToString(", ")
        }.getOrNull() ?: "none"

        val notifListeners = runCatching {
            Settings.Secure.getString(cr, "enabled_notification_listeners")
        }.getOrNull() ?: "none"

        val devOptions = runCatching {
            Settings.Global.getInt(
                cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            )
        }.getOrDefault(0)

        val adb = runCatching {
            Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
        }.getOrDefault(0)

        val wirelessAdb = runCatching {
            Settings.Global.getInt(cr, "adb_wifi_enabled", 0)
        }.getOrDefault(0)

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? DevicePolicyManager
        val devOwner = runCatching {
            dpm?.isDeviceOwnerApp(context.packageName) ?: false
        }.getOrDefault(false)

        val osRel = Build.VERSION.RELEASE
        val osSdk = Build.VERSION.SDK_INT
        val patch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH
        } else "N/A"

        val details = "A11yEnabled=$a11yEnabled | " +
            "TouchExploration=$touchExploration | " +
            "A11yServices=[$a11yServices] | IMEs=[$imes] | " +
            "NotifListeners=[$notifListeners] | " +
            "Dev=$devOptions, ADB=$adb, WirelessADB=$wirelessAdb, " +
            "DevOwner=$devOwner | " +
            "OS=Android $osRel (API $osSdk), SecurityPatch=$patch"

        recordInternal("بصمة الكشف على أندرويد 17", pkg, details)
    }

    @Synchronized
    fun startQuietPeriod(pkg: String) {
        activeProtectedPkg = pkg
        quietUntilUptimeMs = SystemClock.uptimeMillis() +
            QUIET_PERIOD_WINDOW_MS
    }

    @Synchronized
    fun extendQuietPeriod(pkg: String) {
        activeProtectedPkg = pkg
        quietUntilUptimeMs = SystemClock.uptimeMillis() +
            QUIET_PERIOD_WINDOW_MS
    }

    @Synchronized
    fun endQuietPeriod() {
        quietUntilUptimeMs = 0L
        activeProtectedPkg = null
    }

    fun isInQuietPeriod(): Boolean {
        return SystemClock.uptimeMillis() < quietUntilUptimeMs
    }

    fun isBiometricPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return BIOMETRIC_PACKAGES.contains(pkg) ||
            pkg.contains("biometric", ignoreCase = true)
    }

    fun isQuietPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val current = activeProtectedPkg
        return (current != null && pkg == current) ||
            isBiometricPackage(pkg)
    }

    private fun recordInternal(
        name: String,
        pkg: String,
        details: String = ""
    ) {
        val now = System.currentTimeMillis()
        if (startTimestamp == 0L) startTimestamp = now
        val elapsed = now - startTimestamp
        val ev = TimelineEvent(
            eventName = name,
            packageName = pkg,
            timestamp = now,
            elapsedFromStartMs = elapsed,
            details = details
        )
        events.add(ev)
        val currentSession = sessions.lastOrNull()
        if (currentSession != null) {
            currentSession.events.add(ev)
        } else {
            val session = Session(
                packageName = pkg,
                startTimeMs = startTimestamp,
                events = mutableListOf(ev)
            )
            sessions.add(session)
        }
    }

    @Synchronized
    fun getTimelineReport(): String {
        if (sessions.isEmpty() && events.isEmpty()) {
            return "لا يوجد خط زمني مسجّل حتى الآن."
        }
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        sb.appendLine(
            "=== الخط الزمني لاستجابة التطبيقات المحمية " +
                "(آخر ${sessions.size} جلسات) ==="
        )
        val list = if (sessions.isNotEmpty()) {
            sessions
        } else {
            listOf(Session("protected", startTimestamp, events))
        }
        list.forEachIndexed { sIdx, s ->
            val sessionTime = sdf.format(Date(s.startTimeMs))
            sb.appendLine(
                "--- الجلسة ${sIdx + 1} (${s.packageName} - $sessionTime) ---"
            )
            s.events.forEachIndexed { i, ev ->
                val timeStr = sdf.format(Date(ev.timestamp))
                val detailStr = if (ev.details.isNotBlank()) {
                    " (${ev.details})"
                } else ""
                sb.appendLine(
                    "${i + 1}. [+$ev.elapsedFromStartMs ms | $timeStr] " +
                        "${ev.eventName} - ${ev.packageName}$detailStr"
                )
            }
        }
        return sb.toString().trimEnd()
    }

    fun exportTimelineToFile(context: Context): Uri? {
        val report = getTimelineReport()
        val fileName = "lord_diagnostics_${System.currentTimeMillis()}.txt"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "Download/Lord TTS/diagnostics"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv
                ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(report.toByteArray(Charsets.UTF_8))
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ),
                    "Lord TTS/diagnostics"
                )
                dir.mkdirs()
                val file = File(dir, fileName)
                file.writeText(report, Charsets.UTF_8)
                Uri.fromFile(file)
            }
        }.getOrNull()
    }

    @Synchronized
    fun getEvents(): List<TimelineEvent> = ArrayList(events)

    @Synchronized
    fun clear() {
        events.clear()
        sessions.clear()
        startTimestamp = 0L
        quietUntilUptimeMs = 0L
        activeProtectedPkg = null
    }
}
