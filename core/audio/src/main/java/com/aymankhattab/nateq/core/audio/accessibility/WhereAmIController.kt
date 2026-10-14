package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context

/**
 * خدمة «أين أنا؟» (ع٢٧-44، ع٤١-ب3).
 * تعلن عن اسم التطبيق والشاشة والعنصر المركّز وحالته، بترتيبٍ ثابت.
 * مقيّدة بمفتاح [FeatureFlags.FEATURE_WHERE_AM_I].
 */
object WhereAmIController {

    /** @return هل أُعلن الموقع؟ (false إن كانت الميزة معطّلة). */
    fun announceCurrentLocation(context: Context): Boolean {
        val active = FeatureFlags.isActive(
            FeatureFlags.FEATURE_WHERE_AM_I,
            context = context
        )
        if (!active) {
            A11ySpeechCoordinator.speakAnnouncement(
                context = context,
                text = "ميزة أين أنا غير مفعّلة",
                isInterruptAllowed = true
            )
            return false
        }

        val appName = resolveAppName(context)
        val screenLabel = WhereAmIComposer.screenLabel(
            AccessibilityBridge.foregroundActivity()
        )
        val node = AccessibilityBridge.focusSnapshot()
        val text = WhereAmIComposer.compose(appName, screenLabel, node)

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = text,
            isInterruptAllowed = true
        )
        return true
    }

    private fun resolveAppName(context: Context): String {
        val pkg = AccessibilityBridge.foregroundPackage()
        if (pkg.isNullOrBlank()) return "تطبيق غير معروف"
        val pm = context.packageManager
        return runCatching {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkg)
    }
}
