package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context

/**
 * خدمة «أين أنا؟» (ع٢٧-44).
 * تعلن عن اسم التطبيق الحالي والنشاط والعنصر المركّز عليه للمستخدم الكفيف.
 */
object WhereAmIController {

    fun announceCurrentLocation(context: Context) {
        val pkg = AccessibilityBridge.foregroundPackage()
        val activity = AccessibilityBridge.foregroundActivity()
        val focusNode = AccessibilityBridge.focusSnapshot()

        val appName = if (!pkg.isNullOrBlank()) {
            val pm = context.packageManager
            runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(pkg)
        } else {
            "تطبيق غير معروف"
        }

        val focusedText = focusNode?.readableText()?.takeIf { it.isNotBlank() }
        val text = if (focusedText != null) {
            "أنت في $appName، العنصر الحالي: $focusedText"
        } else {
            "أنت في $appName"
        }

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = text,
            isInterruptAllowed = true
        )
    }
}
