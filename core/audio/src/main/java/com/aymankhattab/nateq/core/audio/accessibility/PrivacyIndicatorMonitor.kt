package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * تنبيه الميكروفون والكاميرا (ع٥٦-أمر 17).
 * يراقب بدء تسجيل الصوت واستخدام الكاميرا من التطبيقات الأخرى وينبه صوتياً.
 */
object PrivacyIndicatorMonitor {

    private const val MIN_ALERT_INTERVAL_MS = 30000L
    private var lastMicAlertTime = 0L
    private var lastCameraAlertTime = 0L

    private var isRegistered = false
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? =
        null
    private var cameraCallback: CameraManager.AvailabilityCallback? = null

    fun register(context: Context) {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_camera_mic_alert")) return
        if (isRegistered) return

        val appContext = context.applicationContext

        // مراقبة الميكروفون (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registerAudioMonitor(appContext)
        }

        // مراقبة الكاميرا
        registerCameraMonitor(appContext)

        isRegistered = true
    }

    fun unregister(context: Context) {
        if (!isRegistered) return
        val appContext = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioRecordingCallback?.let {
                val am = appContext.getSystemService(Context.AUDIO_SERVICE)
                    as? AudioManager
                am?.unregisterAudioRecordingCallback(it)
            }
            audioRecordingCallback = null
        }

        cameraCallback?.let {
            val cm = appContext.getSystemService(Context.CAMERA_SERVICE)
                as? CameraManager
            cm?.unregisterAvailabilityCallback(it)
        }
        cameraCallback = null

        isRegistered = false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerAudioMonitor(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE)
            as? AudioManager ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(
                configs: MutableList<AudioRecordingConfiguration>?
            ) {
                if (configs.isNullOrEmpty()) return
                if (CallStateReader.isRinging(context)) return

                val now = SystemClock.uptimeMillis()
                if (lastMicAlertTime != 0L &&
                    now - lastMicAlertTime < MIN_ALERT_INTERVAL_MS
                ) return

                lastMicAlertTime = now
                val msg = context.getString(R.string.mic_recording_started)
                A11ySpeechCoordinator.speakAnnouncement(context, msg)
            }
        }
        audioRecordingCallback = cb
        am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
    }

    private fun registerCameraMonitor(context: Context) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE)
            as? CameraManager ?: return
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                if (CallStateReader.isRinging(context)) return

                val now = SystemClock.uptimeMillis()
                if (lastCameraAlertTime != 0L &&
                    now - lastCameraAlertTime < MIN_ALERT_INTERVAL_MS
                ) return

                lastCameraAlertTime = now
                val msg = context.getString(R.string.camera_in_use)
                A11ySpeechCoordinator.speakAnnouncement(context, msg)
            }
        }
        cameraCallback = cb
        cm.registerAvailabilityCallback(cb, Handler(Looper.getMainLooper()))
    }

    fun resetForTesting() {
        lastMicAlertTime = 0L
        lastCameraAlertTime = 0L
        isRegistered = false
    }
}
