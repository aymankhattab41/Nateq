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
        val text = buildText(deviceName, isConnected)
        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = text,
            isInterruptAllowed = true
        )
    }

    /**
     * يبني نص الإعلان (منطق نقي — قابل للاختبار بلا Context).
     * @param deviceName اسم الجهاز أو null/فراغ ⟹ «جهاز بلوتوث».
     * @param isConnected true = اتصال، false = فصل.
     */
    internal fun buildText(deviceName: String?, isConnected: Boolean): String {
        val name = deviceName?.trim()?.takeIf { it.isNotBlank() }
            ?: "جهاز بلوتوث"
        return if (isConnected) "تم اتصال $name" else "تم فصل $name"
    }
}
