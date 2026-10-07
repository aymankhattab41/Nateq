package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context

/**
 * معلن حالة أجهزة البلوتوث (ع٢٧-48).
 * يعلن صوتياً عند اتصال أو فصل سماعات أو أجهزة البلوتوث مع ذكر اسمها.
 */
object BluetoothAnnouncer {

    fun announceDeviceState(
        context: Context,
        deviceName: String?,
        isConnected: Boolean
    ) {
        val name = deviceName?.trim()?.takeIf { it.isNotBlank() }
            ?: "جهاز بلوتوث"
        val text = if (isConnected) {
            "تم اتصال $name"
        } else {
            "تم فصل $name"
        }

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = text,
            isInterruptAllowed = true
        )
    }
}
