package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * متخطي إعلانات الفيديو (ع٥٦-أمر 20).
 * يكتشف زر تخطي الإعلانات في يوتيوب وينقر عليه آلياً عند توفره.
 */
object AdSkipper {

    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val MIN_SKIP_INTERVAL_MS = 5000L
    private var lastSkipTime = 0L

    private val SKIP_TEXTS = setOf(
        "تخطي الإعلانات",
        "تخطي الإعلان",
        "تخطي",
        "skip ads",
        "skip ad",
        "skip"
    )

    fun onWindowContentChanged(
        context: Context,
        packageName: String?,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        if (packageName != YOUTUBE_PACKAGE) return false
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_skip_ads")) return false
        if (rootNode == null) return false

        val now = SystemClock.uptimeMillis()
        if (lastSkipTime != 0L && now - lastSkipTime < MIN_SKIP_INTERVAL_MS) {
            return false
        }

        val skipBtn = findSkipButton(rootNode) ?: return false
        if (!skipBtn.isClickable) return false

        val success = skipBtn.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        )
        if (success) {
            lastSkipTime = now
            val msg = context.getString(R.string.ad_skipped)
            A11ySpeechCoordinator.speakAnnouncement(context, msg)
            return true
        }
        return false
    }

    private fun findSkipButton(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase()
        val cd = node.contentDescription?.toString()?.trim()?.lowercase()
        val viewId = node.viewIdResourceName?.lowercase()

        val isTextMatch = (text != null && SKIP_TEXTS.contains(text)) ||
            (cd != null && SKIP_TEXTS.contains(cd))
        val isIdMatch = viewId != null &&
            (viewId.contains("skip_ad") || viewId.contains("skip_button"))

        if (isTextMatch || isIdMatch) {
            if (node.isClickable) return node
            val parent = node.parent
            if (parent != null && parent.isClickable) return parent
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSkipButton(child)
            if (found != null) return found
        }
        return null
    }

    fun resetForTesting() {
        lastSkipTime = 0L
    }
}
