package com.aymankhattab.nateq.core.audio.phone

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList

/**
 * خدمة إدارة المكالمات الهاتفية الجارية لنظام Telecom.
 * تدعم الرد، الرفض، مكبر الصوت التلقائي عبر حساس التقارب، وDTMF.
 */
@Suppress("DEPRECATION")
class LordInCallService : InCallService(), SensorEventListener {

    interface CallListener {
        fun onCallStateChanged(call: Call, state: Int)
        fun onCallAudioStateChanged(audioState: CallAudioState)
        fun onCallFinished()
    }

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

        private val listeners = CopyOnWriteArrayList<CallListener>()

        fun registerListener(listener: CallListener) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }

        fun unregisterListener(listener: CallListener) {
            listeners.remove(listener)
        }
    }

    private lateinit var settingsRepo: SettingsRepository
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isProximityRegistered = false
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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
        initProximityWakeLock()
    }

    private fun initProximityWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null &&
            pm.isWakeLockLevelSupported(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
            )
        ) {
            proximityWakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "LordTTS:InCallProximityWakeLock"
            )
        }
    }

    private fun acquireProximityWakeLock() {
        try {
            if (proximityWakeLock != null && !proximityWakeLock!!.isHeld) {
                proximityWakeLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseProximityWakeLock() {
        try {
            if (proximityWakeLock != null && proximityWakeLock!!.isHeld) {
                proximityWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        call.registerCallback(callCallback, mainHandler)
        launchInCallActivity()
        handleCallStateChange(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) {
            stopProximityMonitoring()
            releaseProximityWakeLock()
            CallRecordManager.stopRecording(this)
            currentCall = null
            val intent = Intent(ACTION_FINISH_INCALL_UI).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            mainHandler.post {
                for (listener in listeners) {
                    listener.onCallFinished()
                }
            }
        }
        call.unregisterCallback(callCallback)
    }

    private fun handleCallStateChange(call: Call, state: Int) {
        val intent = Intent(ACTION_CALL_STATE_CHANGED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)

        mainHandler.post {
            for (listener in listeners) {
                listener.onCallStateChanged(call, state)
            }
        }

        when (state) {
            Call.STATE_ACTIVE -> {
                // التأكد من عدم كتم الميكروفون عند بدء المكالمة
                try {
                    setMuted(false)
                } catch (_: Exception) {}

                acquireProximityWakeLock()

                // التحقق من تفعيل التسجيل التلقائي
                val handle = call.details?.handle?.schemeSpecificPart ?: ""
                val name = call.details?.callerDisplayName ?: ""
                val isKnown = name.isNotEmpty()
                if (CallRecordManager.shouldAutoRecord(
                        this, handle, isKnown
                    )
                ) {
                    CallRecordManager.startRecording(this, handle, name)
                }

                // تفعيل مستشعر التقارب لمكبر الصوت
                if (settingsRepo.isPhoneAutoSpeakerProximityEnabled()) {
                    startProximityMonitoring()
                }
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                acquireProximityWakeLock()
            }
            Call.STATE_DISCONNECTED -> {
                stopProximityMonitoring()
                releaseProximityWakeLock()
                CallRecordManager.stopRecording(this)
                val finishIntent = Intent(ACTION_FINISH_INCALL_UI).apply {
                    setPackage(packageName)
                }
                sendBroadcast(finishIntent)
                mainHandler.post {
                    for (listener in listeners) {
                        listener.onCallFinished()
                    }
                }
            }
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
            CallAudioState.ROUTE_WIRED_OR_EARPIECE
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

    @Deprecated("Deprecated in Android 34+")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        audioState?.let { st ->
            mainHandler.post {
                for (listener in listeners) {
                    listener.onCallAudioStateChanged(st)
                }
            }
        }
    }

    private fun startProximityMonitoring() {
        if (!isProximityRegistered && proximitySensor != null) {
            sensorManager?.registerListener(
                this,
                proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                mainHandler
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

        val state = callAudioState ?: return
        val currentRoute = state.route
        // إذا كان المستخدم يستخدم سماعة بلوتوث أو سلكية، لا نغير المسار
        if (currentRoute == CallAudioState.ROUTE_BLUETOOTH ||
            currentRoute == CallAudioState.ROUTE_WIRED_HEADSET
        ) {
            return
        }

        val distance = event.values[0]
        val maxRange = proximitySensor?.maximumRange ?: 5.0f
        val isNear = distance < 1.0f ||
            (maxRange > 1.0f && distance <= 3.0f && distance < maxRange)

        val targetRoute = if (isNear) {
            CallAudioState.ROUTE_WIRED_OR_EARPIECE
        } else {
            CallAudioState.ROUTE_SPEAKER
        }

        if (currentRoute != targetRoute) {
            setAudioRoute(targetRoute)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        stopProximityMonitoring()
        releaseProximityWakeLock()
        instance = null
    }
}
