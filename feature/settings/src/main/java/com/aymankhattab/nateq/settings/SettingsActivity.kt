package com.aymankhattab.nateq.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import com.aymankhattab.nateq.feature.settings.R
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSchedulerService
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.announceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * الشاشة الرئيسية للإعدادات. بسيطة ومباشرة عمدًا (بدون Nested navigation
 * معقّد) لتقليل عدد خطوات التنقل بـ TalkBack. تحتوي على أقسام:
 * 1) اختيار الأصوات لكل لغة
 * 2) إعدادات إعلان الوقت
 * 3) الملفات الشخصية
 * 4) قاموس النطق
 * 5) إعدادات عامة
 *
 * تطلب الأذونات عند أول استخدام: إشعارات فقط (وقراءة الرسائل عند تفعيلها).
 * لا تُفتح شاشات مقيّدة بالإذن المهمل REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
 * لا يطلب إمكانية الوصول أبداً (ناطق محرك TTS عادي وليس خدمة وصول).
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {

    /** مصدر الإعدادات المحقون (نفس سنجلتون التطبيق) لقراءة مفتاح
     *  اكتمال معالج الإعداد الأولي. */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            findViewById<View>(android.R.id.content).announceCompat(
                getString(R.string.permission_notifications_granted)
            )
        } else {
            showPermissionDeniedDialog(R.string.permission_notifications_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // أندرويد 15 يفرض edge-to-edge: نفعّله صراحة
        // (أشرطة شفافة وأيقونات مناسبة).
        // الوسائد (status bar/nav bar) تطبَّق يدوياً من الفصيل عبر
        // ViewCompat.setOnApplyWindowInsetsListener على جذر القائمة
        // القابلة للتمرير.
        enableEdgeToEdge()

        // معالج الإعداد الأولي: يُعرض مرة واحدة قبل استكشاف الواجهة.
        // «تخطّي» يُعلِّم الاكتمال في المخزن؛ لا يُفرض شيء على المستخدم.
        val isFirstRun = runCatching {
            !settingsRepository.isFirstRunSetupCompleted()
        }.getOrDefault(false)

        if (savedInstanceState == null && isFirstRun) {
            startActivity(Intent(this, FirstRunSetupActivity::class.java))
        }

        // عرض واجهة خفيفة فوراً، والفصيل الثقيل يُحمّل في الخلفية
        if (savedInstanceState == null) {
            showLoadingPlaceholder()
            // تأخير بسيط للسماح برسم الواجهة أولاً، ثم إنشاء الفصيل
            mainHandler.postDelayed({ attachSettingsFragment() }, 50L)
        }

        // طلب الأذونات عند أول تشغيل بعد اكتمال الإعداد الأولي
        if (!isFirstRun) {
            permissionsChecked = true
            checkAndRequestPermissions()
        }

        // إعادة تشغيل خدمة إعلانات الوقت/البطارية إن كان أي منها مفعّلاً
        // بعد إنجاز نظام الأندرويد (العمليات في الخلفية قد توقفت) دون أن يفتح
        // المستخدم أي إعداد.
        if (savedInstanceState == null) {
            val started = runCatching {
                AnnouncementSchedulerService.startIfNeeded(this)
            }.getOrDefault(false)
            // على أندرويد المحرك تُنفَّذ عمليات الخلفية بعد تأخر؛ إن لم تكن
            // خدمة الإعلانات مطلوبة كان الوقت هو الوحيد المفعّل (بند 16.2)
            // فنعيد جدولة منبه إعلان الوقت مباشرةً دون تشغيل خدمة أمامية.
            if (!started) {
                runCatching {
                    AnnouncementSchedulerService.ensureTimeAlarm(this)
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun showLoadingPlaceholder() {
        setContentView(R.layout.activity_settings_loading)
    }

    private fun attachSettingsFragment() {
        setContentView(R.layout.activity_settings)
        supportFragmentManager.commit {
            replace(R.id.settings_container, VoiceSelectionFragment())
        }

        // تهيئة المحركات في الخلفية (لا تمنع رسم الواجهة)
        lifecycleScope.launch(Dispatchers.IO) {
            EnginePicker.warmupCache(applicationContext)
        }

        // طلب الأذونات عند أول تشغيل بعد اكتمال الإعداد الأولي
        val isFirstRun = runCatching {
            !settingsRepository.isFirstRunSetupCompleted()
        }.getOrDefault(false)
        if (!isFirstRun) {
            permissionsChecked = true
            checkAndRequestPermissions()
        }

        // إعادة تشغيل خدمة إعلانات الوقت/البطارية
        val started = runCatching {
            AnnouncementSchedulerService.startIfNeeded(this)
        }.getOrDefault(false)
        if (!started) {
            runCatching {
                AnnouncementSchedulerService.ensureTimeAlarm(this)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        // 1. إذن الإشعارات (أندرويد 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(
                    android.Manifest.permission.POST_NOTIFICATIONS
                )
                return
            }
        }

        // انتهت سلسلة الأذونات — لا يوجد طلب إمكانية وصول.
        // (ناطق محرك TTS عادي، وليس خدمة وصول، فلا يطلبها.)
        // إذن RECEIVE_SMS لا يُطلب هنا على الإطلاق: يُطلب فقط عندما يفعّل
        // المستخدم قراءة الرسائل فعلياً من شاشة الإعدادات (مجدداً وعلى حاجة).
        // لا يُطلب إعفاء البطارية تلقائياً: شاشته الخاصة تتطلب إذناً مقيّداً
        // (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) ورمي SecurityException بدونه،
        // ويكفي أن يضبطه المستخدم يدوياً من إعدادات النظام إن أراد.
    }

    private fun showPermissionDeniedDialog(messageRes: Int) {
        permissionDeniedDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_denied_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.permission_open_settings) { _, _ ->
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts(
                            "package", packageName, null
                        )
                    }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        permissionDeniedDialog?.show()
    }

    private var permissionDeniedDialog: AlertDialog? = null
    private var permissionsChecked = false

    override fun onResume() {
        super.onResume()
        if (!permissionsChecked) {
            val isFirstRun = runCatching {
                !settingsRepository.isFirstRunSetupCompleted()
            }.getOrDefault(false)
            if (!isFirstRun) {
                permissionsChecked = true
                checkAndRequestPermissions()
            }
        }
    }

    override fun onDestroy() {
        permissionDeniedDialog?.let { runCatching { it.dismiss() } }
        permissionDeniedDialog = null
        super.onDestroy()
    }
}
