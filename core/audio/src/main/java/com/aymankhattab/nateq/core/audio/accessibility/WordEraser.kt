package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.R

/**
 * إعلان ما تم مسحه عند الحذف (ع٥٦-أمر 11).
 * ينطق النص المحذوف عند مسح كلمتين أو أكثر أو نص طويل (> 1 حرف).
 */
object WordEraser {

    const val DEFAULT_MIN_CHARS = 2
    const val MAX_ANNOUNCED_CHARS = 40

    fun calculateDeletedText(
        beforeText: CharSequence?,
        currentText: CharSequence?
    ): String? {
        val b = beforeText?.toString().orEmpty()
        val c = currentText?.toString().orEmpty()

        if (b.length <= c.length) return null

        val removedCount = b.length - c.length
        var prefixLen = 0
        while (prefixLen < c.length && b[prefixLen] == c[prefixLen]) {
            prefixLen++
        }

        val deleted = b.substring(prefixLen, prefixLen + removedCount)
        return deleted.ifEmpty { null }
    }

    fun formatDeletedAnnouncement(
        context: Context,
        deletedText: String,
        minChars: Int = DEFAULT_MIN_CHARS
    ): String? {
        if (deletedText.length < minChars) return null

        val display = if (deletedText.length > MAX_ANNOUNCED_CHARS) {
            deletedText.take(MAX_ANNOUNCED_CHARS) + "…"
        } else {
            deletedText
        }
        return context.getString(R.string.deleted_announcement, display)
    }
}
