package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * حالة جهة الاتصال داخل المحادثة (ع٥٦-أمر 24).
 * يكتشف حالة الاتصال (متصل، آخر ظهور، يكتب…)
 * وينطقها مرة واحدة عند دخول المحادثة.
 */
object ContactStatusAnnouncer {

    private const val DEBOUNCE_INTERVAL_MS = 60000L
    private var lastStatusTime = 0L
    private var lastStatusText: String? = null

    private val STATUS_PATTERNS = listOf(
        "متصل",
        "online",
        "آخر ظهور",
        "last seen",
        "يكتب…",
        "typing…"
    )

    fun onWindowStateChanged(
        context: Context,
        packageName: String?,
        rootNode: AccessibilityNodeInfo?
    ): Boolean {
        if (packageName != "com.whatsapp" &&
            packageName != "com.whatsapp.w4b" &&
            packageName != "org.telegram.messenger"
        ) {
            return false
        }

        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_contact_status")) return false
        if (rootNode == null) return false

        val status = findStatusText(rootNode) ?: return false

        val now = SystemClock.uptimeMillis()
        if (lastStatusTime != 0L &&
            now - lastStatusTime < DEBOUNCE_INTERVAL_MS &&
            lastStatusText == status
        ) {
            return false
        }

        lastStatusTime = now
        lastStatusText = status

        A11ySpeechCoordinator.speakAnnouncement(context, status)
        return true
    }

    fun findStatusText(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()?.trim()
        val cd = node.contentDescription?.toString()?.trim()
        val combined = "$t $cd".lowercase()

        for (pattern in STATUS_PATTERNS) {
            if (combined.contains(pattern)) {
                return t ?: cd
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findStatusText(child)
            if (found != null) return found
        }
        return null
    }

    fun resetForTesting() {
        lastStatusTime = 0L
        lastStatusText = null
    }
}
