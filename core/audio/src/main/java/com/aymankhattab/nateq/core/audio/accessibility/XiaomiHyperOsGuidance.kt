package com.aymankhattab.nateq.core.audio.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * إرشاد صوتي متوافق مع قارئ الشاشة لضبط صلاحيات أجهزة Xiaomi:
 * يفتح كل شاشة مباشرة عبر Intent مع إعلان صوتي لقارئ الشاشة،
 * ويتراجع لإعدادات التطبيق العامة عند الفشل.
 */
object XiaomiHyperOsGuidance {

    private const val TAG = "NATEQ_TTS"

    private fun speak(context: Context, text: String) {
        try {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = text,
                isInterruptAllowed = true
            )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Failed to speak guidance announcement (len=${text.length})",
                t
            )
        }
    }

    private fun launchIntent(
        context: Context,
        intent: Intent,
        voiceText: String
    ): Boolean {
        speak(context, voiceText)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val success = runCatching {
            context.startActivity(intent)
        }.isSuccess
        if (!success) {
            openAppDetails(context)
        }
        return success
    }

    /** فتح إعداد التنبيهات الدقيقة SCHEDULE_EXACT_ALARM */
    fun openScheduleExactAlarm(context: Context): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        }
        return launchIntent(
            context, intent, "فتح إعداد التنبيهات الدقيقة"
        )
    }

    /** فتح وصول الإشعارات ACTION_NOTIFICATION_LISTENER_SETTINGS */
    fun openNotificationListener(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        return launchIntent(
            context, intent, "فتح إعداد وصول الإشعارات"
        )
    }

    /**
     * فتح استثناء توفير البطارية
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     */
    fun openBatteryOptimization(context: Context): Boolean {
        speak(context, "فتح إعداد استثناء توفير البطارية")
        val pkg = context.packageName
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$pkg")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val success = runCatching {
            context.startActivity(intent)
        }.isSuccess
        if (!success) {
            val fallback = Intent(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val fallbackSuccess = runCatching {
                context.startActivity(fallback)
            }.isSuccess
            if (!fallbackSuccess) {
                openAppDetails(context)
                return false
            }
        }
        return true
    }

    /** فتح إعدادات إمكانية الوصول ACTION_ACCESSIBILITY_SETTINGS */
    fun openAccessibilitySettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        return launchIntent(
            context, intent, "فتح إعدادات إمكانية الوصول"
        )
    }

    /**
     * فتح شاشة التشغيل التلقائي على شاومي
     * (AutoStartManagementActivity)
     */
    fun openXiaomiAutostart(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart" +
                        ".AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START").apply {
                setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart" +
                            ".AutoStartManagementActivity"
                    )
                )
            }
        )
        speak(context, "فتح شاشة التشغيل التلقائي")
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val success = runCatching {
                context.startActivity(intent)
            }.isSuccess
            if (success) return true
        }
        openAppDetails(context)
        return false
    }

    /**
     * فتح شاشة توفير الطاقة للتطبيق على شاومي
     * (HiddenAppsConfigActivity)
     */
    fun openXiaomiPowerSaving(context: Context): Boolean {
        val intents = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            ).apply {
                putExtra("package_name", context.packageName)
                putExtra("package_label", "Lord TTS")
            },
            Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                putExtra("package_name", context.packageName)
            },
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                )
            )
        )
        speak(context, "فتح شاشة توفير الطاقة للتطبيق")
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val success = runCatching {
                context.startActivity(intent)
            }.isSuccess
            if (success) return true
        }
        openAppDetails(context)
        return false
    }

    /** فتح إعدادات التطبيق العامة عند فشل أي Intent */
    fun openAppDetails(context: Context): Boolean {
        speak(
            context,
            "تعذر فتح الشاشة المباشرة، تم فتح إعدادات التطبيق"
        )
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
