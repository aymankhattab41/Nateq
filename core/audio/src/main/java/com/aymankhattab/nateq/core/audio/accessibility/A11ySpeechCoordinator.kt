package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.AccessibilityUtils
import java.util.Locale

/**
 * منسق النطق مع قارئ الشاشة TalkBack (ع٢٧-10).
 * يضمن عدم تداخل الصوت بين خدمات الشاشة وقارئ الشاشة.
 */
object A11ySpeechCoordinator {

    /**
     * هل يُسمح بالنطق الفوري دون تعارض مع قارئ الشاشة؟
     */
    fun canSpeak(context: Context, isInterruptAllowed: Boolean): Boolean {
        val isScreenReaderActive =
            AccessibilityUtils.isScreenReaderEnabled(context)
        if (!isScreenReaderActive) return true
        return isInterruptAllowed
    }

    /**
     * نطق إعلان خدمات الشاشة عبر مكبر الإعلانات المركزي المنسق
     */
    fun speakAnnouncement(
        context: Context,
        text: String,
        isInterruptAllowed: Boolean = false
    ) {
        if (!canSpeak(context, isInterruptAllowed)) return
        val speaker = AnnouncementSpeaker.getInstance(context)
        speaker.speak(
            text = text,
            locale = Locale.getDefault(),
            speechRate = 1.0f,
            pitch = 1.0f,
            volume = 1.0f,
            category = SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )
    }
}
