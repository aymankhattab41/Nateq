package com.aymankhattab.nateq.core.audio.phone

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppTimelineTracker
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch

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
        const val ACTION_ANSWER_CALL =
            "com.aymankhattab.nateq.phone.ACTION_ANSWER_CALL"
        const val ACTION_REJECT_CALL =
            "com.aymankhattab.nateq.phone.ACTION_REJECT_CALL"
        const val ACTION_DISCONNECT_CALL =
            "com.aymankhattab.nateq.phone.ACTION_DISCONNECT_CALL"
        const val CHANNEL_ID_INCALL = "lord_incall_notifications"
        const val NOTIFICATION_ID_CALL = 7001

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
    private var routeBeforeRecording: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val callActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ANSWER_CALL -> {
                    answerCall()
                    currentCall?.let { showOngoingCallNotification(it) }
                }
                ACTION_REJECT_CALL -> {
                    rejectCall()
                    dismissCallNotification()
                }
                ACTION_DISCONNECT_CALL -> {
                    disconnectCall()
                    dismissCallNotification()
                }
            }
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            handleCallStateChange(call, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepo = SettingsRepository.create(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE)
            as? SensorManager
        proximitySensor =
            sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        initProximityWakeLock()

        val filter = IntentFilter().apply {
            addAction(ACTION_ANSWER_CALL)
            addAction(ACTION_REJECT_CALL)
            addAction(ACTION_DISCONNECT_CALL)
        }
        ContextCompat.registerReceiver(
            this,
            callActionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
        if (call.state == Call.STATE_RINGING) {
            handleIncomingCallUi(call)
        } else {
            launchInCallActivity()
        }
        handleCallStateChange(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) {
            dismissCallNotification()
            stopProximityMonitoring()
            releaseProximityWakeLock()
            restoreSpeakerAfterRecording()
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
                showOngoingCallNotification(call)
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
                    if (CallRecordManager.startRecording(this, handle, name)) {
                        enableSpeakerForRecording()
                    }
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
                dismissCallNotification()
                stopProximityMonitoring()
                releaseProximityWakeLock()
                restoreSpeakerAfterRecording()
                CallRecordManager.stopRecording(this)
                val finishIntent = Intent(ACTION_FINISH_INCALL_UI).apply {
                    setPackage(packageName)
                }
                sendBroadcast(finishIntent)
                mainHandler.post {
                    for (listener in listeners) {
                        listener.onCallFinished()
                    }
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.Dispatchers.Main
                    ).launch {
                        com.aymankhattab.nateq.core.audio.accessibility
                            .TtsSettingsAutomation
                            .checkAndRunPendingSwitch(applicationContext)
                    }
                }
            }
        }
    }

    internal var lastNotificationFullScreenIntent: Boolean? = null
    internal var lastActivityLaunched: Boolean = false

    internal fun launchInCallActivity() {
        lastActivityLaunched = true
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

    fun enableSpeakerForRecording() {
        if (!settingsRepo.isPhoneRecordAutoSpeakerEnabled()) return
        val currentRoute =
            callAudioState?.route ?: CallAudioState.ROUTE_EARPIECE
        routeBeforeRecording = currentRoute
        setAudioRoute(CallAudioState.ROUTE_SPEAKER)
    }

    fun restoreSpeakerAfterRecording() {
        val prev = routeBeforeRecording
        if (prev != null) {
            setAudioRoute(prev)
            routeBeforeRecording = null
        }
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
        runCatching { unregisterReceiver(callActionReceiver) }
        dismissCallNotification()
        instance = null
    }

    internal fun handleIncomingCallUi(call: Call?) {
        val isProtected = ProtectedAppTimelineTracker.isInQuietPeriod() ||
            isForegroundProtected()

        if (isProtected) {
            showIncomingCallNotification(call, useFullScreenIntent = false)
            return
        }

        val mode = settingsRepo.getIncomingCallUiMode()
        when (mode) {
            SettingsRepository.INCOMING_CALL_UI_FULLSCREEN -> {
                showIncomingCallNotification(call, useFullScreenIntent = true)
                launchInCallActivity()
            }
            SettingsRepository.INCOMING_CALL_UI_NOTIFICATION -> {
                showIncomingCallNotification(call, useFullScreenIntent = false)
            }
            else -> {
                val pm = getSystemService(Context.POWER_SERVICE)
                    as? PowerManager
                val km = getSystemService(Context.KEYGUARD_SERVICE)
                    as? KeyguardManager
                val isScreenOn = pm?.isInteractive ?: false
                val isLocked = km?.isKeyguardLocked ?: false
                val fgPkg = AccessibilityBridge.foregroundPackage()
                val isLauncher = ProtectedAppManager.isHomeLauncher(
                    this, fgPkg
                )

                if (!isScreenOn || isLocked) {
                    showIncomingCallNotification(
                        call, useFullScreenIntent = true
                    )
                } else if (
                    fgPkg != null && fgPkg != packageName && !isLauncher
                ) {
                    showIncomingCallNotification(
                        call, useFullScreenIntent = false
                    )
                } else {
                    showIncomingCallNotification(
                        call, useFullScreenIntent = true
                    )
                    launchInCallActivity()
                }
            }
        }
    }

    private fun isForegroundProtected(): Boolean {
        val fg = AccessibilityBridge.foregroundPackage() ?: return false
        return ProtectedAppTimelineTracker.isQuietPackage(fg)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID_INCALL,
                "المكالمات الواردة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات المكالمات الواردة والجارية"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    internal fun showIncomingCallNotification(
        call: Call?,
        useFullScreenIntent: Boolean
    ) {
        lastNotificationFullScreenIntent = useFullScreenIntent
        ensureNotificationChannel()
        val handle = call?.details?.handle?.schemeSpecificPart ?: ""
        val callerName = call?.details?.callerDisplayName
            ?.takeIf { it.isNotBlank() } ?: handle
        val displayText = if (callerName.isNotBlank()) {
            "مكالمة واردة من $callerName"
        } else {
            "مكالمة واردة"
        }

        val answerIntent = Intent(ACTION_ANSWER_CALL).apply {
            setPackage(packageName)
        }
        val answerPending = PendingIntent.getBroadcast(
            this,
            1001,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(ACTION_REJECT_CALL).apply {
            setPackage(packageName)
        }
        val rejectPending = PendingIntent.getBroadcast(
            this,
            1002,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenPending = if (useFullScreenIntent) {
            val fullIntent = Intent().apply {
                setClassName(
                    this@LordInCallService,
                    "com.aymankhattab.nateq.settings.phone.LordInCallActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            PendingIntent.getActivity(
                this,
                1000,
                fullIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val callerPerson = Person.Builder()
            .setName(
                if (callerName.isNotBlank()) callerName else "مكالمة واردة"
            )
            .setImportant(true)
            .build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_INCALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(
                if (callerName.isNotBlank()) callerName else "مكالمة واردة"
            )
            .setContentText(displayText)
            .setTicker(displayText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callStyle = NotificationCompat.CallStyle.forIncomingCall(
                callerPerson,
                rejectPending,
                answerPending
            )
            builder.setStyle(callStyle)
        } else {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "رفض",
                rejectPending
            )
            builder.addAction(
                android.R.drawable.ic_menu_call,
                "رد",
                answerPending
            )
        }

        if (fullScreenPending != null) {
            builder.setFullScreenIntent(fullScreenPending, true)
            builder.setContentIntent(fullScreenPending)
        } else {
            val clickIntent = Intent().apply {
                setClassName(
                    this@LordInCallService,
                    "com.aymankhattab.nateq.settings.phone.LordInCallActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val clickPending = PendingIntent.getActivity(
                this,
                1000,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(clickPending)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager
        nm?.notify(NOTIFICATION_ID_CALL, builder.build())
    }

    private fun showOngoingCallNotification(call: Call) {
        ensureNotificationChannel()
        val handle = call.details?.handle?.schemeSpecificPart ?: ""
        val callerName = call.details?.callerDisplayName
            ?.takeIf { it.isNotBlank() } ?: handle
        val displayText = if (callerName.isNotBlank()) {
            "مكالمة جارية مع $callerName"
        } else {
            "مكالمة جارية"
        }

        val hangupIntent = Intent(ACTION_DISCONNECT_CALL).apply {
            setPackage(packageName)
        }
        val hangupPending = PendingIntent.getBroadcast(
            this,
            1004,
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val returnIntent = Intent().apply {
            setClassName(
                this@LordInCallService,
                "com.aymankhattab.nateq.settings.phone.LordInCallActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val returnPending = PendingIntent.getActivity(
            this,
            1005,
            returnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(
                if (callerName.isNotBlank()) callerName else "مكالمة جارية"
            )
            .build()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_INCALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(
                if (callerName.isNotBlank()) callerName else "مكالمة جارية"
            )
            .setContentText(displayText)
            .setTicker(displayText)
            .setContentIntent(returnPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callStyle = NotificationCompat.CallStyle.forOngoingCall(
                callerPerson,
                hangupPending
            )
            builder.setStyle(callStyle)
            builder.addAction(
                android.R.drawable.ic_menu_call,
                "العودة إلى المكالمة",
                returnPending
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إنهاء",
                hangupPending
            )
            builder.addAction(
                android.R.drawable.ic_menu_call,
                "العودة إلى المكالمة",
                returnPending
            )
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager
        nm?.notify(NOTIFICATION_ID_CALL, builder.build())
    }

    private fun dismissCallNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager
        nm?.cancel(NOTIFICATION_ID_CALL)
    }
}
