package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.aymankhattab.nateq.core.audio.phone.CallRecordManager
import com.aymankhattab.nateq.core.audio.phone.LordInCallService

/**
 * سياسة بيئة الصوت: رصد المكالمات وتسجيل الرسائل الصوتية لحظر الأصوات المزعجة.
 */
object AudioEnvironmentPolicy {

    @Volatile
    var testInCall: Boolean? = null

    @Volatile
    var testRecordingVoiceNote: Boolean? = null

    /**
     * هل توجد مكالمة هاتفية أو مكالمة عبر برامج التواصل (VoIP) نشطة؟
     */
    fun isInCall(context: Context): Boolean {
        testInCall?.let { return it }
        val am =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.mode ?: AudioManager.MODE_NORMAL
        val isModeInCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        val isLordCall = LordInCallService.currentCall != null
        return isModeInCall || isLordCall
    }

    /**
     * هل يجري تسجيل رسالة صوتية أو تسجيل صوتي عبر الميكروفون؟
     */
    fun isRecordingVoiceNote(context: Context): Boolean {
        testRecordingVoiceNote?.let { return it }
        if (CallRecordManager.isRecording()) return true
        val am =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (am.activeRecordingConfigurations.isNotEmpty()) return true
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * هل يجب كتم أصوات التقدم والتدرج (مكالمة هاتفية أو تواصل أو تسجيل)؟
     */
    fun shouldMuteCues(context: Context): Boolean =
        isInCall(context) || isRecordingVoiceNote(context)
}
