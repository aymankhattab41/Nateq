package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context

/**
 * معلن النوافذ المنبثقة والإشعارات اللحظية (ع٢٧-41).
 * يعلن عن الرسائل التحذيرية أو مربعات الحوار الجديدة فور ظهورها.
 */
object PopupAnnouncer {

    fun announcePopup(
        context: Context,
        title: String?,
        message: String?
    ) {
        val cleanTitle = title?.trim().orEmpty()
        val cleanMsg = message?.trim().orEmpty()

        val text = when {
            cleanTitle.isNotEmpty() && cleanMsg.isNotEmpty() ->
                "تنبيه: $cleanTitle، $cleanMsg"
            cleanTitle.isNotEmpty() ->
                "تنبيه: $cleanTitle"
            cleanMsg.isNotEmpty() ->
                "تنبيه: $cleanMsg"
            else -> return
        }

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = text,
            isInterruptAllowed = false
        )
    }
}
