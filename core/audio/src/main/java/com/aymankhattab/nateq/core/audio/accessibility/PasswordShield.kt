package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * درع كلمات المرور (ع٥٦-أمر 16).
 * يكتم صدى الكتابة في حقول كلمات المرور ويتيح نطق عدد الرموز فقط دون كشفها.
 */
object PasswordShield {

    fun isPasswordNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return node.isPassword
    }

    fun shouldMuteTypingEcho(
        context: Context,
        isPassword: Boolean
    ): Boolean {
        if (!isPassword) return false
        val repo = SettingsRepository.create(context)
        return repo.isExtraEnabled("extra_password_shield")
    }

    fun announceCharacterCount(
        context: Context,
        node: AccessibilityNodeInfo?
    ): Boolean {
        if (node == null) return false
        val text = node.text?.toString().orEmpty()
        val count = text.length

        val msg = if (count == 0) {
            context.getString(R.string.password_field_empty)
        } else {
            context.getString(R.string.password_char_count, count)
        }
        A11ySpeechCoordinator.speakAnnouncement(context, msg)
        return true
    }
}
