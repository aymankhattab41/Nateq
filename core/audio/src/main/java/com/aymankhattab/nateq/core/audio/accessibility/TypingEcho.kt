package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context

/**
 * صدى الكتابة (ع٢٧-31).
 * ينطق الحروف أو الكلمات المكتوبة أثناء تفاعل المستخدم مع لوحة المفاتيح.
 */
object TypingEcho {

    enum class EchoMode {
        OFF,
        CHARACTERS,
        WORDS,
        BOTH
    }

    /**
     * حساب النص المراد نطقه عند إضافة أو تعديل حرف
     */
    fun getEchoText(
        oldText: CharSequence?,
        newText: CharSequence?,
        mode: EchoMode
    ): String? {
        if (mode == EchoMode.OFF || newText.isNullOrEmpty()) return null
        val oldStr = oldText?.toString().orEmpty()
        val newStr = newText.toString()

        if (newStr.length <= oldStr.length) return null
        val added = newStr.substring(oldStr.length)

        return when (mode) {
            EchoMode.CHARACTERS -> added
            EchoMode.WORDS -> {
                if (added.contains(" ") || added.contains("\n")) {
                    oldStr.split(" ", "\n").lastOrNull()?.trim()
                } else null
            }
            EchoMode.BOTH -> {
                if (added.contains(" ") || added.contains("\n")) {
                    oldStr.split(" ", "\n").lastOrNull()?.trim()
                } else {
                    added
                }
            }
            EchoMode.OFF -> null
        }
    }
}
