package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * نشاط شفاف لفتح التطبيق المحمي بعد ضمان تعطيل إمكانية الوصول (الأمر 7).
 */
class ProtectedAppLauncherActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"

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

        if (app != null) {
            ProtectedAppManager.handleAppEntry(this, null, app)
        } else {
            val defApp = ProtectedApp(
                packageName = targetPkg,
                label = label
            )
            ProtectedAppManager.handleAppEntry(this, null, defApp)
        }

        Handler(Looper.getMainLooper()).postDelayed({
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
        }, 400L)
    }
}
