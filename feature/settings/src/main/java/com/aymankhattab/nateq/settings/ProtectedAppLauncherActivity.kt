package com.aymankhattab.nateq.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.accessibility.A11ySpeechCoordinator
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * نشاط شفاف لفتح التطبيق المحمي بعد ضمان تعطيل إمكانية الوصول (الأمر 6).
 */
class ProtectedAppLauncherActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        private const val POLL_INTERVAL_MS = 80L
        private const val MAX_WAIT_MS = 1500L

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

        // إعلان صوتي فوري للكفيف
        A11ySpeechCoordinator.speakAnnouncement(
            context = this,
            text = "جارٍ فتح $label",
            isInterruptAllowed = true
        )

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

        // حلقة فحص كل 80ms بحد أقصى 1500ms
        val startTime = SystemClock.uptimeMillis()
        val handler = Handler(Looper.getMainLooper())

        fun launchTarget() {
            val launchIntent = packageManager
                .getLaunchIntentForPackage(targetPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
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
                    launchTarget()
                } else if (elapsed >= MAX_WAIT_MS) {
                    ProtectedAppTimelineTracker.recordWarning(
                        targetPkg,
                        "لم تتوقف الخدمة خلال 1500ms (انقضى ${elapsed}ms)"
                    )
                    launchTarget()
                } else {
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }

        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun isLordServiceActive(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        val enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ) ?: return false
        val ourPkg = context.packageName
        return enabled.any {
            it.resolveInfo?.serviceInfo?.packageName == ourPkg
        }
    }
}
