package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.media.AudioManager

/**
 * حالة الصوت المحيطة في لحظة الإعلان التلقائي للوقت — تُقرأ من
 * AudioManager دون أي أذونات: مكالمة نشطة / تشغيل وسائط / وضع صامت.
 */
data class AudioSceneConditions(
    val inCall: Boolean,
    val mediaActive: Boolean,
    val silent: Boolean
)

/** قراءة حالة الصوت المحيطة من النظام وقت الإعلان التلقائي.
 *  فشلُ القراءة يعيد مشهداً محايداً (لا سيناريو مفعّلاً) فلا نُعلّق. */
internal fun detectAudioScene(context: Context): AudioSceneConditions {
    return runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE)
            as? AudioManager
        if (audio == null) {
            AudioSceneConditions(false, false, false)
        } else {
            AudioSceneConditions(
                inCall = audio.mode == AudioManager.MODE_IN_CALL ||
                    audio.mode == AudioManager.MODE_IN_COMMUNICATION,
                mediaActive = audio.isMusicActive,
                silent = audio.ringerMode == AudioManager.RINGER_MODE_SILENT ||
                    audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE
            )
        }
    }.getOrDefault(AudioSceneConditions(false, false, false))
}

/**
 * القرار: هل يُعلَّق الإعلان التلقائي للوقت بسبب السيناريو الحالي؟
 * السيناريو الموجود مع تعطيل مفتاح السماح المقابل له = تعليق.
 */
internal fun shouldSuppressTimeAnnouncement(
    conditions: AudioSceneConditions,
    allowDuringCalls: Boolean,
    allowDuringMedia: Boolean,
    allowDuringSilent: Boolean
): Boolean {
    if (conditions.inCall && !allowDuringCalls) return true
    if (conditions.mediaActive && !allowDuringMedia) return true
    if (conditions.silent && !allowDuringSilent) return true
    return false
}