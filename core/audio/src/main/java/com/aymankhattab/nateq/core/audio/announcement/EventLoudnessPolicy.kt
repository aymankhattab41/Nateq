package com.aymankhattab.nateq.core.audio.announcement

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.core.data.CategoryLoudnessConfig
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.StreamMode

/**
 * ع٤٢-2: رموز أسباب تعثر تغيير مستوى الصوت للتشخيص.
 */
object VolumeFailureReason {
    const val NONE = "none"
    const val SECURITY_DND = "security_dnd"
    const val RINGER_MODE_RESTRICTION = "ringer_mode_restriction"
    const val UNKNOWN = "unknown"
}

/**
 * نتيجة حل مسار الصوت: القناة الصوتية والسمات المطابقة تماماً.
 */
data class ResolvedAudioRoute(
    val stream: Int,
    val audioAttributes: AudioAttributes,
    val modeName: String
)

/**
 * ع٤٢-2 — سياسة جهارة الأحداث: صمام أمان السماعات، تجاوز وضع الصامت،
 * واختيار القناة المطابقة للسمات مع الاستعادة الآمنة.
 */
object EventLoudnessPolicy {

    private const val TAG = "NATEQ_LOUDNESS"

    @Volatile
    var lastFailureReason: String = VolumeFailureReason.NONE
        private set

    /**
     * هل توجد سماعة رأس (سلكية أو بلوتوث أو USB أو معين سمعي) متصلة حالياً؟
     */
    fun isHeadsetConnected(audioManager: AudioManager): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS
                )
                devices.any { device ->
                    isHeadphoneDeviceType(device.type)
                }
            } else {
                @Suppress("DEPRECATION")
                (audioManager.isWiredHeadsetOn ||
                    audioManager.isBluetoothA2dpOn ||
                    audioManager.isBluetoothScoOn)
            }
        }.getOrDefault(false)
    }

    /**
     * التحقق مما إذا كان نوع الجهاز يمثل سماعة أذن أو رأساً.
     */
    @VisibleForTesting
    internal fun isHeadphoneDeviceType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
            ) {
                true
            } else {
                false
            }
        }
    }

    /**
     * حساب مستوى الصوت المستهدف مع سقف أمان السماعات (بند 1 وبند 5).
     */
    fun computeTargetVolume(
        current: Int,
        max: Int,
        config: CategoryLoudnessConfig,
        isHeadsetConnected: Boolean
    ): Int {
        if (!config.forceMax || max <= 0) return current
        if (isHeadsetConnected) {
            val safePercent = config.headsetCapPercent.coerceIn(0, 100)
            val cap = ((max * safePercent) / 100).coerceAtLeast(1)
            return if (current < cap) cap else current
        }
        return max
    }

    /**
     * حل المسار والقناة المتطابقين بحسب السياسة وحالة الجهاز (بند 3 وبند 4).
     */
    fun resolveTargetStreamAndAttributes(
        category: String?,
        config: CategoryLoudnessConfig,
        inCall: Boolean,
        isSilentOrVibrate: Boolean
    ): ResolvedAudioRoute {
        if (inCall && AnnouncementSpeaker.isCallerCategory(category)) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            return ResolvedAudioRoute(
                stream = AudioManager.STREAM_VOICE_CALL,
                audioAttributes = attr,
                modeName = "CALL"
            )
        }

        return when (config.streamMode) {
            StreamMode.ACCESSIBILITY -> {
                val stream = if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {
                    AudioManager.STREAM_ACCESSIBILITY
                } else {
                    AudioManager.STREAM_MUSIC
                }
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                ResolvedAudioRoute(stream, attr, "ACCESSIBILITY")
            }
            StreamMode.ALARM -> {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ResolvedAudioRoute(AudioManager.STREAM_ALARM, attr, "ALARM")
            }
            StreamMode.NOTIFICATION -> {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ResolvedAudioRoute(
                    AudioManager.STREAM_NOTIFICATION,
                    attr,
                    "NOTIFICATION"
                )
            }
            StreamMode.MEDIA -> {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                ResolvedAudioRoute(AudioManager.STREAM_MUSIC, attr, "MEDIA")
            }
            StreamMode.AUTO -> {
                if (isSilentOrVibrate && config.bypassSilent) {
                    // تجاوز الصامت بقناة الإنذار غير المكتومة نظاماً
                    val attr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                    ResolvedAudioRoute(
                        AudioManager.STREAM_ALARM,
                        attr,
                        "AUTO_BYPASS_SILENT_ALARM"
                    )
                } else if (AnnouncementSpeaker.isCallerCategory(category)) {
                    val attr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SONIFICATION
                        )
                        .build()
                    ResolvedAudioRoute(
                        AudioManager.STREAM_NOTIFICATION,
                        attr,
                        "AUTO_CALLER_NOTIFICATION"
                    )
                } else {
                    val attr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    ResolvedAudioRoute(
                        AudioManager.STREAM_MUSIC,
                        attr,
                        "AUTO_MEDIA"
                    )
                }
            }
        }
    }

    /**
     * تصنيف سبب التعثر لتشخيص أخطاء تغيير الصوت (بند 5).
     */
    fun classifyFailureReason(
        t: Throwable,
        audioManager: AudioManager
    ): String {
        val reason = when {
            t is SecurityException -> VolumeFailureReason.SECURITY_DND
            audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL ->
                VolumeFailureReason.RINGER_MODE_RESTRICTION
            else -> VolumeFailureReason.UNKNOWN
        }
        lastFailureReason = reason
        return reason
    }

    /**
     * استعادة أي مستوى صوت معلّق من إغلاق غير متوقع عند الإقلاع (بند 6).
     */
    fun restorePendingBoostIfAny(
        audioManager: AudioManager,
        settings: SettingsRepository
    ): Boolean {
        val stream = settings.getPendingBoostStream()
        val volume = settings.getPendingBoostVolume()
        if (stream != -1 && volume != -1) {
            Log.i(
                TAG,
                "[EventLoudness] Restoring pending boost stream=$stream to " +
                    "original volume=$volume"
            )
            return runCatching {
                audioManager.setStreamVolume(stream, volume, 0)
                settings.clearPendingBoost()
                true
            }.onFailure { e ->
                Log.w(
                    TAG,
                    "[EventLoudness] Failed to restore pending boost: " +
                        "${e.message}",
                    e
                )
            }.getOrDefault(false)
        }
        return false
    }
}
