package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * واصف صور المنشورات من وصفها البديل (ع٥٦-أمر 25).
 * ينطق الوصف البديل (Alt text) للصور في منصات التواصل الاجتماعي بعد تنظيفه.
 */
object AltTextReader {

    private val SOCIAL_PACKAGES = setOf(
        "com.facebook.katana",
        "com.facebook.lite",
        "com.instagram.android",
        "com.twitter.android",
        "com.twitter.android.lite"
    )

    private val PREFIXES_TO_CLEAN = listOf(
        "قد تحتوي الصورة على:",
        "قد تحتوي الصورة على",
        "image may contain:",
        "image may contain",
        "photo by",
        "صورة بواسطة"
    )

    fun isSocialMediaImage(
        packageName: String?,
        node: AccessibilityNodeInfo?
    ): Boolean {
        if (packageName !in SOCIAL_PACKAGES) return false
        if (node == null) return false

        val cls = node.className?.toString()?.lowercase().orEmpty()
        val isImageClass = cls.contains("image") || cls.contains("photo")
        val hasNoVisibleText = node.text.isNullOrBlank()
        val hasDescription = !node.contentDescription.isNullOrBlank()

        return (isImageClass || hasNoVisibleText) && hasDescription
    }

    fun cleanAltText(raw: String): String {
        var cleaned = raw.trim()
        val lower = cleaned.lowercase()

        for (prefix in PREFIXES_TO_CLEAN) {
            if (lower.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length).trim()
                break
            }
        }
        return cleaned.take(200)
    }

    fun inspectAndAnnounce(
        context: Context,
        packageName: String?,
        node: AccessibilityNodeInfo?
    ): Boolean {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_alt_text")) return false
        if (!isSocialMediaImage(packageName, node)) return false

        val desc = node?.contentDescription?.toString().orEmpty()
        val cleaned = cleanAltText(desc)
        if (cleaned.isBlank()) return false

        val msg = context.getString(R.string.image_alt_text, cleaned)
        A11ySpeechCoordinator.speakAnnouncement(context, msg)
        return true
    }
}
