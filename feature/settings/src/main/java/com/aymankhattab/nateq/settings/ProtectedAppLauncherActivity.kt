package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppSuspensionStore
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppWatcherService
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * نشاط شفاف لفتح التطبيق المحمي بعد ضمان تعطيل إمكانية الوصول
 * (الجولة 51 والجولة 52 - الأمر 2).
 */
class ProtectedAppLauncherActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        private const val POLL_INTERVAL_MS = 80L
        private const val MAX_WAIT_MS = 3000L
        private const val SETTLE_DELAY_MS = 300L

        fun createIntent(context: Context, targetPkg: String): Intent {
            return Intent(
                context, ProtectedAppLauncherActivity::class.java
            ).apply {
                putExtra(EXTRA_TARGET_PACKAGE, targetPkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPkg = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (targetPkg.isNullOrBlank()) {
            finish()
            return
        }

        val settings = SettingsRepository.create(this)
        val app = settings.getProtectedApp(targetPkg)
        val label = app?.label ?: runCatching {
            val pm = packageManager
            val info = pm.getApplicationInfo(targetPkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull() ?: targetPkg

        // إعلان صوتي بنظام Lord (مسار الأحداث)
        ProtectedAppManager.speakLordSystem(this, "جارٍ فتح $label")

        // تسجيل البداية في الخط الزمني وبدء فترة الصمت التام
        ProtectedAppTimelineTracker.recordLauncherClick(targetPkg)
        ProtectedAppTimelineTracker.recordLordDetected(targetPkg)

        // استدعاء التعطيل
        ProtectedAppTimelineTracker.recordDisableSelfCalled(targetPkg)
        val targetApp = app ?: ProtectedApp(
            packageName = targetPkg,
            label = label
        )
        ProtectedAppManager.handleAppEntry(this, null, targetApp)

        // حلقة فحص كل 80ms بحد أقصى 3000ms
        val startTime = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())

        fun launchTarget() {
            val launchIntent = packageManager
                .getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(launchIntent)
                ProtectedAppManager.scheduleAppAutoRetry(
                    applicationContext, targetPkg
                )
            } else {
                Toast.makeText(
                    this,
                    "تعذر فتح التطبيق: $label",
                    Toast.LENGTH_SHORT
                ).show()
            }
            finish()
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - startTime
                val active = isLordServiceActive(
                    this@ProtectedAppLauncherActivity
                )
                if (!active) {
                    ProtectedAppTimelineTracker.recordDisabledConfirmed(
                        targetPkg, elapsed
                    )
                    handler.postDelayed({ launchTarget() }, SETTLE_DELAY_MS)
                } else if (elapsed >= MAX_WAIT_MS) {
                    ProtectedAppTimelineTracker.recordWarning(
                        targetPkg,
                        "لم تتوقف الخدمة خلال 3000ms — إلغاء الفتح"
                    )
                    ProtectedAppManager.speakLordSystem(
                        this@ProtectedAppLauncherActivity,
                        "تعذر تعطيل إمكانية الوصول، لن أفتح التطبيق"
                    )
                    ProtectedAppSuspensionStore.clear(
                        this@ProtectedAppLauncherActivity
                    )
                    SettingsRepository.create(
                        this@ProtectedAppLauncherActivity
                    ).setSuspendedProtectedApp(null)
                    ProtectedAppManager.cancelBackupAlarm(
                        this@ProtectedAppLauncherActivity
                    )
                    ProtectedAppWatcherService.stopService(
                        this@ProtectedAppLauncherActivity
                    )
                    finish()
                } else {
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }

        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun isLordServiceActive(context: Context): Boolean =
        ProtectedAppManager.isServiceConnected(context)
}
