package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * أصوات أدوار عناصر الواجهة (الجولة 56 - إضافات 5 - أمر 2).
 * يشغل نغمات صوتية مميزة لكل دور عنصر قبل النطق مع حد 80ms.
 */
object RoleEarcons {

    private const val MIN_INTERVAL_MS = 80L

    @Volatile
    private var lastPlayUptimeMs = 0L

    @Volatile
    private var lastFocusedNodeHash: Int? = null

    enum class ElementRole(val toneIndex: Int) {
        BUTTON(1),
        HEADING(8),
        LINK(6),
        CHECKBOX_SWITCH(3),
        INPUT(10),
        IMAGE(0),
        LIST_ITEM(4)
    }

    /**
     * تصفير الحالة للاختبارات.
     */
    fun resetForTesting() {
        lastPlayUptimeMs = 0L
        lastFocusedNodeHash = null
    }

    /**
     * تحديد دور العنصر من خصائص العقدة.
     */
    fun determineRole(node: AccessibilityNodeInfo): ElementRole? {
        val className = node.className?.toString().orEmpty()

        // 1. عناوين
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading) {
            return ElementRole.HEADING
        }
        if (className.contains("Heading", ignoreCase = true)) {
            return ElementRole.HEADING
        }

        // 2. حقول الإدخال
        if (node.isEditable ||
            className.contains("EditText", ignoreCase = true)
        ) {
            return ElementRole.INPUT
        }

        // 3. الخانات ومفاتيح التبديل
        if (node.isCheckable ||
            className.contains("CheckBox", ignoreCase = true) ||
            className.contains("Switch", ignoreCase = true) ||
            className.contains("ToggleButton", ignoreCase = true) ||
            className.contains("RadioButton", ignoreCase = true)
        ) {
            return ElementRole.CHECKBOX_SWITCH
        }

        // 4. أزرار صريحة
        if (className.contains("Button", ignoreCase = true)) {
            return ElementRole.BUTTON
        }

        // 5. روابط
        if (className.contains("Link", ignoreCase = true) ||
            (className.contains("TextView", ignoreCase = true) &&
                node.isClickable &&
                (node.text?.contains("http") == true ||
                    node.text?.contains(".com") == true))
        ) {
            return ElementRole.LINK
        }

        // 6. عناصر القوائم
        if (node.collectionItemInfo != null) {
            return ElementRole.LIST_ITEM
        }

        // 7. صور
        if (className.contains("ImageView", ignoreCase = true) &&
            !node.isClickable
        ) {
            return ElementRole.IMAGE
        }

        // 8. عناصر قابلة للنقر عامة
        if (node.isClickable) {
            return ElementRole.BUTTON
        }

        return null
    }

    /**
     * معالجة تركيز العقدة وتشغيل النغمة المناسبة.
     */
    fun onNodeFocused(
        context: Context,
        packageName: String?,
        node: AccessibilityNodeInfo
    ): Boolean {
        if (AppPolicy.isSensitiveApp(packageName)) {
            return false
        }

        val settings = SettingsRepository.create(context)
        if (!settings.isExtraEnabled("extra_role_earcons")) {
            return false
        }

        val nodeHash = computeNodeHash(node)
        if (nodeHash == lastFocusedNodeHash) {
            return false
        }

        val now = SystemClock.uptimeMillis()
        if (lastPlayUptimeMs != 0L &&
            now - lastPlayUptimeMs < MIN_INTERVAL_MS
        ) {
            return false
        }

        val role = determineRole(node) ?: return false

        // التحقق من تفعيل الدور المحدد
        val roleKey = "extra_role_earcons_${role.name.lowercase()}"
        val roleEnabled = settings.getExtraBoolean(roleKey, true)
        if (!roleEnabled) return false

        lastFocusedNodeHash = nodeHash
        lastPlayUptimeMs = now

        val volPercent = settings.getExtraInt(
            "extra_role_earcons_volume_percent",
            50
        )
        val volume = (volPercent / 100f).coerceIn(0.1f, 1.0f)
        ScrollCuePlayer.playTone(role.toneIndex, volume)
        return true
    }

    private fun computeNodeHash(node: AccessibilityNodeInfo): Int {
        var result = node.className?.hashCode() ?: 0
        result = 31 * result + (node.text?.hashCode() ?: 0)
        result = 31 * result + (node.contentDescription?.hashCode() ?: 0)
        result = 31 * result + (node.viewIdResourceName?.hashCode() ?: 0)
        return result
    }
}
