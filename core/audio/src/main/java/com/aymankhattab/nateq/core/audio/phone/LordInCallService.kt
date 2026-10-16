package com.aymankhattab.nateq.core.audio.phone

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * خدمة إدارة المكالمات الهاتفية الجارية لنظام أندرويد Telecom.
 * تدعم الرد، الرفض، مكبر الصوت التلقائي عبر حساس التقارب، وإرسال DTMF.
 */
@Suppress("DEPRECATION")
class LordInCallService : InCallService(), SensorEventListener {

    companion object {
        @Volatile
        var currentCall: Call? = null
            private set

        @Volatile
        var instance: LordInCallService? = null
            private set

        const val ACTION_CALL_STATE_CHANGED =
            "com.aymankhattab.nateq.phone.ACTION_CALL_STATE_CHANGED"
        const val ACTION_FINISH_INCALL_UI =
            "com.aymankhattab.nateq.phone.ACTION_FINISH_INCALL_UI"
    }

    private lateinit var settingsRepo: SettingsRepository
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isProximityRegistered = false

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            handleCallStateChange(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepo = SettingsRepository(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        proximitySensor =
            sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callCallback)
        launchInCallActivity()
        handleCallStateChange(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) {
            stopProximityMonitoring()
            CallRecordManager.stopRecording(this)
            currentCall = null
            sendBroadcast(Intent(ACTION_FINISH_INCALL_UI))
        }
        call.unregisterCallback(callCallback)
    }

    private fun handleCallStateChange(call: Call, state: Int) {
        sendBroadcast(Intent(ACTION_CALL_STATE_CHANGED))

        if (state == Call.STATE_ACTIVE) {
            // التحقق من تفعيل التسجيل التلقائي
            val handle = call.details?.handle?.schemeSpecificPart ?: ""
            val name = call.details?.callerDisplayName ?: ""
            val isKnown = name.isNotEmpty()
            if (CallRecordManager.shouldAutoRecord(this, handle, isKnown)) {
                CallRecordManager.startRecording(this, handle, name)
            }

            // تفعيل مستشعر التقارب إن كان مفعلاً اختيارياً
            if (settingsRepo.isPhoneAutoSpeakerProximityEnabled()) {
                startProximityMonitoring()
            }
        } else if (state == Call.STATE_DISCONNECTED) {
            stopProximityMonitoring()
            CallRecordManager.stopRecording(this)
            sendBroadcast(Intent(ACTION_FINISH_INCALL_UI))
        }
    }

    private fun launchInCallActivity() {
        try {
            val clazz = Class.forName(
                "com.aymankhattab.nateq.settings.phone.LordInCallActivity"
            )
            val intent = Intent(this, clazz).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    fun answerCall() {
        currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun rejectCall() {
        val call = currentCall ?: return
        if (call.state == Call.STATE_RINGING) {
            call.reject(false, null)
        } else {
            call.disconnect()
        }
    }

    fun disconnectCall() {
        currentCall?.disconnect()
    }

    fun sendDtmf(digit: Char) {
        currentCall?.playDtmfTone(digit)
        currentCall?.stopDtmfTone()
    }

    fun toggleSpeaker(): Boolean {
        val state = callAudioState ?: return false
        val isSpeaker = (state.route == CallAudioState.ROUTE_SPEAKER)
        val targetRoute = if (isSpeaker) {
            CallAudioState.ROUTE_EARPIECE
        } else {
            CallAudioState.ROUTE_SPEAKER
        }
        setAudioRoute(targetRoute)
        return !isSpeaker
    }

    fun toggleMute(): Boolean {
        val state = callAudioState ?: return false
        val newMute = !state.isMuted
        setMuted(newMute)
        return newMute
    }

    private fun startProximityMonitoring() {
        if (!isProximityRegistered && proximitySensor != null) {
            sensorManager?.registerListener(
                this,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isProximityRegistered = true
        }
    }

    private fun stopProximityMonitoring() {
        if (isProximityRegistered) {
            sensorManager?.unregisterListener(this)
            isProximityRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_PROXIMITY) return
        if (!settingsRepo.isPhoneAutoSpeakerProximityEnabled()) return

        val distance = event.values[0]
        val maxRange = proximitySensor?.maximumRange ?: 5.0f
        val isNear = (distance < maxRange)

        if (isNear) {
            setAudioRoute(CallAudioState.ROUTE_EARPIECE)
        } else {
            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        stopProximityMonitoring()
        instance = null
    }
}
