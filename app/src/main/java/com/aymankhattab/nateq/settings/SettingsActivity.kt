package com.aymankhattab.nateq.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.engine.AnnouncementSchedulerService

/**
 * الشاشة الرئيسية للإعدادات. بسيطة ومباشرة عمدًا (بدون Nested navigation
 * معقّد) لتقليل عدد خطوات التنقل بـ TalkBack. تحتوي على أقسام:
 * 1) اختيار الأصوات لكل لغة
 * 2) إعدادات إعلان الوقت
 * 3) الملفات الشخصية
 * 4) قاموس النطق
 * 5) إعدادات عامة
 *
 * تطلب الأذونات عند أول استخدام: إشعارات وقراءة الرسائل فقط.
 * لا تُفتح شاشات مقيّدة بالإذن المهمل REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
 * لا يطلب إمكانية الوصول أبداً (ناطق محرك TTS عادي وليس خدمة وصول).
 */
class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {

    private val requestNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            findViewById<View>(android.R.id.content).announceForAccessibility(getString(R.string.permission_notifications_granted))
        } else {
            showPermissionDeniedDialog(R.string.permission_notifications_denied)
        }
    }

    private val requestSmsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            findViewById<View>(android.R.id.content).announceForAccessibility(getString(R.string.permission_sms_granted))
        } else {
            showPermissionDeniedDialog(R.string.permission_sms_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // أندرويد 15 يفرض edge-to-edge: نفعّله صراحة لتُطبق الوسائد
        // (status bar/nav bar) على محتوى القائمة عبر fitsSystemWindows.
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.settings_container, VoiceSelectionFragment())
            }
        }

        // طلب الأذونات عند أول تشغيل
        checkAndRequestPermissions()

        // إعادة تشغيل خدمة إعلانات الوقت/البطارية إن كان أي منها مفعّلاً بعد إنجاز
        // نظام الأندرويد (العمليات في الخلفية قد توقفت) دون أن يفتح المستخدم أي إعداد.
        if (savedInstanceState == null) {
            runCatching { AnnouncementSchedulerService.startIfNeeded(this) }
        }
    }

    private fun checkAndRequestPermissions() {
        // 1. إذن الإشعارات (أندرويد 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 1.5 إذن قراءة الرسائل الواردة (RECEIVE_SMS)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestSmsPermission.launch(android.Manifest.permission.RECEIVE_SMS)
            return
        }
        // انتهت سلسلة الأذونات — لا يوجد طلب إمكانية وصول.
        // (ناطق محرك TTS عادي، وليس خدمة وصول، فلا يطلبها.)
        // لا يُطلب إعفاء البطارية تلقائياً: شاشته الخاصة تتطلب إذناً مقيّداً
        // (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) ورمي SecurityException بدونه،
        // ويكفي أن يضبطه المستخدم يدوياً من إعدادات النظام إن أراد.
    }

    private fun showPermissionDeniedDialog(messageRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_denied_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
