package com.aymankhattab.nateq.core.audio.accessibility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * مدير التطبيقات المحمية (إنستاباي والتطبيقات المصرفية) — الجولة 48 (الأمر 7).
 */
object ProtectedAppManager {

    private const val TAG = "ProtectedAppManager"
    const val NOTIFICATION_ID_REENABLE = 7001
    const val CHANNEL_ID_PROTECTED = "protected_app_channel"

    @Volatile
    var isPartiallySuspended: Boolean = false
        private set

    @Volatile
    var partiallySuspendedPkg: String? = null
        private set

    /**
     * هل الحزمة تنتمي لمشغل الشاشة الرئيسية (Launcher / Home)؟
     */
    fun isHomeLauncher(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
        val defaultHome = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        if (pkg == defaultHome) return true

        val allHomes = runCatching {
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        if (allHomes.contains(pkg)) return true

        val lower = pkg.lowercase()
        return lower.contains("launcher") || lower.contains("home")
    }

    /**
     * مطابقة نص أو وصف العنصر المنقور في المشغل مع أي تطبيق محمي.
     */
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

    /**
     * معالجة دخول التطبيق المحمي (تعليق كامل أو جزئي).
     */
    fun handleAppEntry(
        context: Context,
        service: NateqAccessibilityService?,
        app: ProtectedApp
    ) {
        val settings = SettingsRepository.create(context)
        Log.i(
            TAG,
            "handleAppEntry: pkg=${app.packageName}, mode=${app.mode}"
        )

        if (app.mode == ProtectedApp.MODE_PARTIAL_SUSPEND) {
            isPartiallySuspended = true
            partiallySuspendedPkg = app.packageName
            service?.applyServiceInfo()
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "تعليق جزئي لإمكانية الوصول من أجل ${app.label}",
                isInterruptAllowed = true
            )
            return
        }

        // إيقاف كامل: disableSelf() وإعادة التشغيل عند الخروج
        settings.setSuspendedProtectedApp(
            app.packageName,
            System.currentTimeMillis()
        )

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = "تم تعطيل إمكانية الوصول لفتح ${app.label}",
            isInterruptAllowed = true
        )

        // تشغيل مراقب الخروج قبل التعطيل
        ProtectedAppWatcherService.startForApp(
            context = context,
            pkg = app.packageName,
            label = app.label
        )

        // إيقاف خدمة إمكانية الوصول
        service?.disableSelf()
    }

    /**
     * معالجة الخروج من التطبيق المحمي واستعادة الخدمة.
     */
    fun handleAppExit(context: Context, pkg: String, label: String) {
        val settings = SettingsRepository.create(context)
        Log.i(TAG, "handleAppExit: pkg=$pkg, label=$label")

        if (isPartiallySuspended && partiallySuspendedPkg == pkg) {
            isPartiallySuspended = false
            partiallySuspendedPkg = null
            return
        }

        ProtectedAppWatcherService.stopService(context)

        val hasWriteSecure = SystemTtsEngineController.hasPermission(context)
        if (hasWriteSecure) {
            val ok = enableAccessibilityServiceDirectly(context)
            if (ok) {
                A11ySpeechCoordinator.speakAnnouncement(
                    context = context,
                    text = "عادت إمكانية الوصول",
                    isInterruptAllowed = true
                )
                settings.setSuspendedProtectedApp(null)
                return
            }
        }

        // عند عدم توفر الإذن: إشعار عالي الأولوية واهتزاز وإعلان صوتي
        postReenableNotification(context, label)
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = "انتهيت من $label، اضغط زرّي الصوت لإعادة تشغيل Lord",
            isInterruptAllowed = true
        )
        settings.setSuspendedProtectedApp(null)
    }

    /**
     * إعادة تفعيل خدمة إمكانية الوصول عبر Settings.Secure
     * مع الحفاظ على الخدمات الأخرى دون حذف (الأمر 7).
     */
    fun enableAccessibilityServiceDirectly(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            val serviceName =
                "com.aymankhattab.nateq/.core.audio.accessibility" +
                    ".NateqAccessibilityService"
            val current = Settings.Secure.getString(
                cr,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val services = current.split(":")
                .filter { it.isNotBlank() }
                .toMutableList()
            if (!services.contains(serviceName)) {
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
            verified.contains(serviceName)
        } catch (e: Exception) {
            Log.e(TAG, "enableAccessibilityServiceDirectly failed", e)
            false
        }
    }

    private fun postReenableNotification(context: Context, appLabel: String) {
        val nm = context.getSystemService(
            NotificationManager::class.java
        ) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_PROTECTED,
                "إعادة تشغيل إمكانية الوصول",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description =
                    "تنبيه لإعادة تشغيل الخدمة بعد الخروج من التطبيق المحمي"
                enableVibration(true)
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
            .addAction(
                android.R.drawable.ic_menu_manage,
                "فتح الإعدادات",
                pendingIntent
            )

        nm.notify(NOTIFICATION_ID_REENABLE, builder.build())

        runCatching {
            val v = context.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(
                    VibrationEffect.createOneShot(
                        350L,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(350L)
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        isPartiallySuspended = false
        partiallySuspendedPkg = null
    }
}
