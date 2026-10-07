package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.util.AccessibilityUtils
import com.google.android.material.button.MaterialButton

/**
 * شاشة تفعيل خدمة إمكانية الوصول وقراءة الشاشة (ع٢٧-4).
 * - مصممة لتكون صديقة تماماً لقارئات الشاشة (TalkBack).
 * - تشرح بإيجاز ما تفعله الخدمة وما لا تفعله.
 * - تتعامل مع «الإعدادات المقيّدة» على أندرويد 13 فما فوق.
 */
class AccessibilityOnboardingActivity : AppCompatActivity() {

    private var wasEnabledBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_onboarding)

        title = getString(R.string.a11y_onboarding_title)

        wasEnabledBefore =
            AccessibilityUtils.isNateqAccessibilityServiceEnabled(this)

        val layoutRestricted =
            findViewById<View>(R.id.layout_restricted_settings)
        val btnAppDetails =
            findViewById<MaterialButton>(R.id.btn_open_app_details)
        val btnEnable =
            findViewById<MaterialButton>(R.id.btn_enable_accessibility)

        // على أندرويد 13+ نعرض خطوة الإعدادات المقيّدة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            layoutRestricted.visibility = View.VISIBLE
            btnAppDetails.setOnClickListener {
                openAppDetailsSettings()
            }
        } else {
            layoutRestricted.visibility = View.GONE
        }

        btnEnable.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        val isNowEnabled =
            AccessibilityUtils.isNateqAccessibilityServiceEnabled(this)
        if (isNowEnabled && !wasEnabledBefore) {
            Toast.makeText(
                this,
                getString(R.string.a11y_service_enabled_announcement),
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
    }

    private fun openAccessibilitySettings() {
        // فحص وجود ACTION_ACCESSIBILITY_DETAILS_SETTINGS على الإصدارات الحديثة
        if (Build.VERSION.SDK_INT >= 34) {
            val detailsIntent = Intent(
                "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
            ).apply {
                putExtra(
                    Intent.EXTRA_COMPONENT_NAME,
                    "${packageName}/" +
                    "com.aymankhattab.nateq.core.audio.accessibility." +
                    "NateqAccessibilityService"
                )
            }
            if (detailsIntent.resolveActivity(packageManager) != null) {
                runCatching {
                    startActivity(detailsIntent)
                    return
                }
            }
        }

        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        runCatching { startActivity(intent) }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(
                context,
                AccessibilityOnboardingActivity::class.java
            )
            context.startActivity(intent)
        }
    }
}
