package com.aymankhattab.nateq.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/**
 * شاشة إرشادات منح إذن WRITE_SECURE_SETTINGS عبر أداة ADB.
 * توفر نسخ الأمر وفتح خيارات المطور وإعدادات النطق في النظام.
 */
class WriteSecureSettingsGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_write_secure_settings_guide)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }

        val adbCommand = "adb shell pm grant $packageName " +
            "android.permission.WRITE_SECURE_SETTINGS"

        val tvAdbCommand = findViewById<TextView>(R.id.tv_adb_command)
        tvAdbCommand.text = adbCommand

        findViewById<MaterialButton>(R.id.btn_copy_command).setOnClickListener {
            copyToClipboard(adbCommand)
        }

        findViewById<MaterialButton>(R.id.btn_open_dev_options)
            .setOnClickListener {
                openDeveloperOptions()
            }

        findViewById<MaterialButton>(R.id.btn_open_tts_settings)
            .setOnClickListener {
                openTtsSettings()
            }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
        val clip = ClipData.newPlainText("ADB Command", text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(
            this,
            R.string.guide_toast_command_copied,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openDeveloperOptions() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            )
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open development settings", t)
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {}
        }
    }

    private fun openTtsSettings() {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open TTS_SETTINGS directly", t)
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private const val TAG = "NATEQ_TTS"
    }
}
