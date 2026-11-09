package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.audio.engine.SpeechEntryPoints
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
        if (ProtectedAppTimelineTracker.isInQuietPeriod()) return false
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
        volume: Float? = null,
        source: SpeechEntryPoints.Source = SpeechEntryPoints.Source.LORD_SYSTEM
    ) {
        if (!canSpeak(context, isInterruptAllowed)) return
        val prepared = SpeechEntryPoints.prepare(text, source, context)
        val speaker = AnnouncementSpeaker.getInstance(context)
        if (voiceId != null) speaker.resetVoice(voiceId)
        val settings = SettingsRepository.create(context)
        val notifCat = SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        val effRate = speechRate ?: runCatching {
            settings.getSpeechRateForCategory(notifCat)
        }.getOrDefault(1.0f)
        val effPitch = pitch ?: runCatching {
            settings.getPitchForCategory(notifCat)
        }.getOrDefault(1.0f)
        val effVolume = volume ?: runCatching {
            settings.getVolumeForCategory(notifCat)
        }.getOrDefault(1.0f)
        speaker.speak(
            text = prepared,
            locale = Locale.getDefault(),
            speechRate = effRate,
            pitch = effPitch,
            volume = effVolume,
            engineOverride = engineOverride,
            category = notifCat,
            source = source,
            isPrepared = true
        )
    }
}
