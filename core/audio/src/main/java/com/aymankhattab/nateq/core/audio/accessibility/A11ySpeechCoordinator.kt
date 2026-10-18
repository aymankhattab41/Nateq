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

    @Volatile
    var isSuppressed: Boolean = false

    /**
     * هل يُسمح بالنطق الفوري دون تعارض مع قارئ الشاشة؟
     */
    fun canSpeak(context: Context, isInterruptAllowed: Boolean): Boolean {
        if (isSuppressed) return false
        val isScreenReaderActive =
            AccessibilityUtils.isScreenReaderEnabled(context)
        if (!isScreenReaderActive) return true
        return isInterruptAllowed
    }

    /**
     * نطق إعلان خدمات الشاشة عبر مكبر الإعلانات المركزي المنسق.
     * يمكن تمرير تجاوزات صوتية (ع٤١-ب10/ب11): صوتٌ ومحركٌ وسرعة/نبرة/مستوى
     * لجهة الاتصال أو ملف التطبيق؛ والقيم null تبقى على إعدادات الفئة.
     */
    fun speakAnnouncement(
        context: Context,
        text: String,
        isInterruptAllowed: Boolean = false,
        voiceId: String? = null,
        engineOverride: String? = null,
        speechRate: Float? = null,
        pitch: Float? = null,
        volume: Float? = null
    ) {
        if (!canSpeak(context, isInterruptAllowed)) return
        val speaker = AnnouncementSpeaker.getInstance(context)
        if (voiceId != null) speaker.resetVoice(voiceId)
        speaker.speak(
            text = text,
            locale = Locale.getDefault(),
            speechRate = speechRate ?: 1.0f,
            pitch = pitch ?: 1.0f,
            volume = volume ?: 1.0f,
            engineOverride = engineOverride,
            category = SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )
    }
}
