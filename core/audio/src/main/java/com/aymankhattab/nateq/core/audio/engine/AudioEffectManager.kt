package com.aymankhattab.nateq.core.audio.engine

import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log
import com.aymankhattab.nateq.core.engine.AudioExpansionLevels
import java.util.concurrent.ConcurrentHashMap

/**
 * مدير مؤثرات الصوت المدمجة في أندرويد (Virtualizer + PresetReverb) (بنود 2-3).
 *
 * يعمل مباشرة على AudioTrack.audioSessionId عبر واجهات النظام الرسمية
 * android.media.audiofx دون أي تبعيات أو مكتبات DSP خارجية تثقل الـ APK.
 * يعطي إحساساً باتساع مكاني خفيف ومريح عبر سماعات الأذن.
 */
class AudioEffectManager {

    companion object {
        private const val TAG = "NATEQ_AUDIO_FX"

        /** قوة الاتساع للمستوى الخفيف (من أصل 1000). */
        const val STRENGTH_LIGHT: Short = 500

        /** قوة الاتساع للمستوى المتوسط (من أصل 1000). */
        const val STRENGTH_MEDIUM: Short = 900
    }

    private data class SessionEffects(
        val virtualizer: Virtualizer?,
        val reverb: PresetReverb?
    )

    private val sessions = ConcurrentHashMap<Int, SessionEffects>()

    /**
     * ربط وضبط المؤثرات على جلسة صوت محددة.
     *
     * @param audioSessionId معرف جلسة AudioTrack.
     * @param expansionLevel مستوى الاتساع (OFF, LIGHT, MEDIUM).
     */
    fun attach(audioSessionId: Int, expansionLevel: Int) {
        if (audioSessionId <= 0 ||
            expansionLevel <= AudioExpansionLevels.OFF
        ) {
            detach(audioSessionId)
            return
        }

        // تفريغ أي تأثيرات قديمة للجلسة قبل إنشاء الجديدة
        detach(audioSessionId)

        val virt = try {
            Virtualizer(0, audioSessionId).apply {
                val strength = if (
                    expansionLevel == AudioExpansionLevels.LIGHT
                ) {
                    STRENGTH_LIGHT
                } else {
                    STRENGTH_MEDIUM
                }
                if (strengthSupported) {
                    setStrength(strength)
                }
                enabled = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Virtualizer init failed for session $audioSessionId", t)
            null
        }

        val rev = try {
            PresetReverb(0, audioSessionId).apply {
                preset = if (
                    expansionLevel == AudioExpansionLevels.LIGHT
                ) {
                    PresetReverb.PRESET_SMALLROOM
                } else {
                    PresetReverb.PRESET_MEDIUMROOM
                }
                enabled = true
            }
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "PresetReverb init failed for session $audioSessionId",
                t
            )
            null
        }

        if (virt != null || rev != null) {
            sessions[audioSessionId] = SessionEffects(virt, rev)
        }
    }

    /**
     * إيقاف وتحرير المؤثرات لجلسة صوت محددة لمنع تسريب الموارد.
     */
    fun detach(audioSessionId: Int) {
        val effects = sessions.remove(audioSessionId) ?: return
        runCatching {
            effects.virtualizer?.enabled = false
            effects.virtualizer?.release()
        }
        runCatching {
            effects.reverb?.enabled = false
            effects.reverb?.release()
        }
    }

    /**
     * تحرير كافة المؤثرات النشطة (عند تدمير الخدمة أو إعادة التشغيل).
     */
    fun releaseAll() {
        val keys = sessions.keys().toList()
        for (sessionId in keys) {
            detach(sessionId)
        }
    }

    /**
     * فحص هل المؤثرات نشطة لجلسة معينة.
     */
    fun isAttached(audioSessionId: Int): Boolean =
        sessions.containsKey(audioSessionId)
}
