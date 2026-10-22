package com.aymankhattab.nateq.core.audio.accessibility

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * متتبع الخط الزمني وفترة الصمت التام للتطبيقات المحمية (الأمر 6).
 */
object ProtectedAppTimelineTracker {

    data class TimelineEvent(
        val eventName: String,
        val packageName: String,
        val timestamp: Long,
        val elapsedFromStartMs: Long,
        val details: String = ""
    )

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
        events.clear()
        events.add(
            TimelineEvent(
                eventName = "النقر على أيقونة الإطلاق",
                packageName = pkg,
                timestamp = now,
                elapsedFromStartMs = 0L
            )
        )
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
        events.add(
            TimelineEvent(
                eventName = name,
                packageName = pkg,
                timestamp = now,
                elapsedFromStartMs = elapsed,
                details = details
            )
        )
    }

    @Synchronized
    fun getTimelineReport(): String {
        if (events.isEmpty()) {
            return "لا يوجد خط زمني مسجّل حتى الآن."
        }
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        sb.appendLine("=== الخط الزمني لاستجابة التطبيقات المحمية ===")
        events.forEachIndexed { i, ev ->
            val timeStr = sdf.format(Date(ev.timestamp))
            val detailStr = if (ev.details.isNotBlank()) {
                " (${ev.details})"
            } else ""
            sb.appendLine(
                "${i + 1}. [+$ev.elapsedFromStartMs ms | $timeStr] " +
                    "${ev.eventName} - ${ev.packageName}$detailStr"
            )
        }
        return sb.toString().trimEnd()
    }

    @Synchronized
    fun getEvents(): List<TimelineEvent> = ArrayList(events)

    @Synchronized
    fun clear() {
        events.clear()
        startTimestamp = 0L
        quietUntilUptimeMs = 0L
        activeProtectedPkg = null
    }
}
