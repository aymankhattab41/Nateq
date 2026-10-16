package com.aymankhattab.nateq.settings.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.core.audio.phone.CallRecordManager
import com.aymankhattab.nateq.core.audio.phone.DialpadKey
import com.aymankhattab.nateq.core.audio.phone.LordDialpadView
import com.aymankhattab.nateq.core.audio.phone.LordInCallService
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import java.util.Locale

/**
 * شاشة إدارة المكالمات الجارية والواردة الميسّرة لذوي الإعاقة البصرية.
 */
@Suppress("DEPRECATION")
class LordInCallActivity : AppCompatActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private var tvCallerName: TextView? = null
    private var tvCallerNumber: TextView? = null
    private var tvDuration: TextView? = null
    private var llIncomingActions: View? = null
    private var llActiveControls: View? = null
    private var btnEndCall: MaterialButton? = null
    private var btnSpeaker: MaterialButton? = null
    private var btnMute: MaterialButton? = null
    private var btnRecord: MaterialButton? = null
    private var llDtmfContainer: View? = null
    private var tvDtmfDigits: TextView? = null
    private var dialpadInCall: LordDialpadView? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var callStartTime = 0L
    private val durationRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0L) {
                val elapsedSec =
                    (SystemClock.elapsedRealtime() - callStartTime) / 1000L
                val min = elapsedSec / 60
                val sec = elapsedSec % 60
                tvDuration?.text = String.format("%02d:%02d", min, sec)
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val callListener = object : LordInCallService.CallListener {
        override fun onCallStateChanged(call: Call, state: Int) {
            updateCallUi()
        }

        override fun onCallAudioStateChanged(audioState: CallAudioState) {
            val isSpeaker =
                (audioState.route == CallAudioState.ROUTE_SPEAKER)
            updateSpeakerUi(isSpeaker)
            updateMuteUi(audioState.isMuted)
        }

        override fun onCallFinished() {
            finish()
        }
    }

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LordInCallService.ACTION_CALL_STATE_CHANGED ->
                    updateCallUi()
                LordInCallService.ACTION_FINISH_INCALL_UI ->
                    finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_lord_in_call)

        settingsRepo = SettingsRepository(this)
        initViews()
        initTts()

        LordInCallService.registerListener(callListener)

        val filter = IntentFilter().apply {
            addAction(LordInCallService.ACTION_CALL_STATE_CHANGED)
            addAction(LordInCallService.ACTION_FINISH_INCALL_UI)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                callReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(callReceiver, filter)
        }

        updateCallUi()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
                isTtsReady = true
                announceCallInfo()
            }
        }
    }

    private fun announceCallInfo() {
        val call = LordInCallService.currentCall ?: return
        val name = call.details?.callerDisplayName
            ?: call.details?.handle?.schemeSpecificPart ?: ""
        if (call.state == Call.STATE_RINGING) {
            speak(getString(R.string.phone_incoming_announcement, name))
        } else if (call.state == Call.STATE_ACTIVE) {
            speak(getString(R.string.phone_active_announcement, name))
        }
    }

    private fun speak(text: String) {
        if (!isTtsReady || text.isEmpty()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "incall_speech")
    }

    private fun initViews() {
        tvCallerName = findViewById(R.id.tv_incall_caller_name)
        tvCallerNumber = findViewById(R.id.tv_incall_caller_number)
        tvDuration = findViewById(R.id.tv_incall_duration)
        llIncomingActions = findViewById(R.id.ll_incoming_call_actions)
        llActiveControls = findViewById(R.id.ll_active_call_controls)
        btnEndCall = findViewById(R.id.btn_incall_end_call)
        btnSpeaker = findViewById(R.id.btn_incall_speaker)
        btnMute = findViewById(R.id.btn_incall_mute)
        btnRecord = findViewById(R.id.btn_incall_record)
        llDtmfContainer = findViewById(R.id.ll_dtmf_container)
        tvDtmfDigits = findViewById(R.id.tv_dtmf_digits)
        dialpadInCall = findViewById(R.id.dialpad_incall)

        val btnAnswer = findViewById<MaterialButton>(R.id.btn_incall_answer)
        val btnReject = findViewById<MaterialButton>(R.id.btn_incall_reject)
        val btnKeypad = findViewById<MaterialButton>(R.id.btn_incall_keypad)
        val btnCloseDtmf = findViewById<MaterialButton>(R.id.btn_close_dtmf)

        btnAnswer.setOnClickListener {
            llIncomingActions?.visibility = View.GONE
            llActiveControls?.visibility = View.VISIBLE
            btnEndCall?.visibility = View.VISIBLE
            tvDuration?.visibility = View.VISIBLE
            LordInCallService.instance?.answerCall()
        }

        btnReject.setOnClickListener {
            llIncomingActions?.visibility = View.GONE
            LordInCallService.instance?.rejectCall()
            finish()
        }

        btnEndCall?.setOnClickListener {
            LordInCallService.instance?.disconnectCall()
            finish()
        }

        btnSpeaker?.setOnClickListener {
            val isNowSpeaker =
                LordInCallService.instance?.toggleSpeaker() ?: false
            val msg = if (isNowSpeaker) {
                getString(R.string.phone_speaker_on)
            } else {
                getString(R.string.phone_speaker_off)
            }
            speak(msg)
            updateSpeakerUi(isNowSpeaker)
        }

        btnMute?.setOnClickListener {
            val isNowMuted =
                LordInCallService.instance?.toggleMute() ?: false
            val msg = if (isNowMuted) {
                getString(R.string.phone_mute_on)
            } else {
                getString(R.string.phone_mute_off)
            }
            speak(msg)
            updateMuteUi(isNowMuted)
        }

        btnKeypad.setOnClickListener {
            llDtmfContainer?.visibility = View.VISIBLE
        }

        btnCloseDtmf.setOnClickListener {
            llDtmfContainer?.visibility = View.GONE
        }

        btnRecord?.setOnClickListener {
            toggleCallRecording()
        }

        setupDialpad()
    }

    private fun setupDialpad() {
        val dp = dialpadInCall ?: return
        dp.phoneMode = settingsRepo.getPhoneMode()
        dp.activationMode = settingsRepo.getPhoneActivation()
        dp.soundEnabled = settingsRepo.isPhoneSoundEnabled()
        dp.hapticEnabled = settingsRepo.isPhoneHapticEnabled()

        dp.actionListener = object : LordDialpadView.OnDialpadActionListener {
            override fun onKeySelected(key: DialpadKey) {
                LordInCallService.instance?.sendDtmf(key.digit)
                val current = tvDtmfDigits?.text?.toString() ?: ""
                tvDtmfDigits?.text = current + key.digit
            }

            override fun onKeyHover(key: DialpadKey) {
                speak(key.spokenLabel)
            }
        }
    }

    private fun toggleCallRecording() {
        val call = LordInCallService.currentCall ?: return
        val number = call.details?.handle?.schemeSpecificPart ?: ""
        val name = call.details?.callerDisplayName ?: ""

        if (CallRecordManager.isRecording()) {
            CallRecordManager.stopRecording(this)
            speak(getString(R.string.phone_record_stopped))
            updateRecordUi(isRecording = false)
        } else {
            val started =
                CallRecordManager.startRecording(this, number, name)
            if (started) {
                speak(getString(R.string.phone_record_started))
                updateRecordUi(isRecording = true)
            }
        }
    }

    private fun updateCallUi() {
        val call = LordInCallService.currentCall
        if (call == null || call.state == Call.STATE_DISCONNECTED) {
            finish()
            return
        }

        val name = call.details?.callerDisplayName
        val number = call.details?.handle?.schemeSpecificPart ?: ""
        tvCallerName?.text = if (!name.isNullOrEmpty()) name else number
        tvCallerNumber?.text = if (!name.isNullOrEmpty()) number else ""

        val isRinging = (call.state == Call.STATE_RINGING)
        if (isRinging) {
            llIncomingActions?.visibility = View.VISIBLE
            llActiveControls?.visibility = View.GONE
            btnEndCall?.visibility = View.GONE
            tvDuration?.visibility = View.GONE
            mainHandler.removeCallbacks(durationRunnable)
        } else {
            llIncomingActions?.visibility = View.GONE
            llActiveControls?.visibility = View.VISIBLE
            btnEndCall?.visibility = View.VISIBLE
            tvDuration?.visibility = View.VISIBLE

            if (callStartTime == 0L && call.state == Call.STATE_ACTIVE) {
                callStartTime = SystemClock.elapsedRealtime()
                mainHandler.post(durationRunnable)
            }

            val audioState = LordInCallService.instance?.callAudioState
            val isSpeaker =
                (audioState?.route == CallAudioState.ROUTE_SPEAKER)
            updateSpeakerUi(isSpeaker)

            val isMuted = (audioState?.isMuted == true)
            updateMuteUi(isMuted)

            // إظهار أو إخفاء زر التسجيل حسب تفضيل المستخدم
            val showRecordBtn = settingsRepo.isPhoneShowRecordButton() ||
                settingsRepo.getPhoneRecordMode() ==
                SettingsRepository.PHONE_RECORD_MODE_MANUAL
            btnRecord?.visibility =
                if (showRecordBtn) View.VISIBLE else View.GONE
            updateRecordUi(CallRecordManager.isRecording())
        }
    }

    private fun updateSpeakerUi(isSpeaker: Boolean) {
        btnSpeaker?.setBackgroundColor(
            if (isSpeaker) 0xFF00796B.toInt() else 0x00000000
        )
        btnSpeaker?.contentDescription = if (isSpeaker) {
            getString(R.string.phone_speaker_on)
        } else {
            getString(R.string.phone_speaker_off)
        }
    }

    private fun updateMuteUi(isMuted: Boolean) {
        btnMute?.setBackgroundColor(
            if (isMuted) 0xFFC62828.toInt() else 0x00000000
        )
        btnMute?.contentDescription = if (isMuted) {
            getString(R.string.phone_mute_on)
        } else {
            getString(R.string.phone_mute_off)
        }
    }

    private fun updateRecordUi(isRecording: Boolean) {
        if (isRecording) {
            btnRecord?.text = getString(R.string.phone_record_stop_btn)
            btnRecord?.setBackgroundColor(0xFFC62828.toInt())
        } else {
            btnRecord?.text = getString(R.string.phone_record_btn_label)
            btnRecord?.setBackgroundColor(0x00000000)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val call = LordInCallService.currentCall
        if (call?.state == Call.STATE_RINGING &&
            settingsRepo.isPhoneHardwareKeysAnswerEnabled()
        ) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                llIncomingActions?.visibility = View.GONE
                llActiveControls?.visibility = View.VISIBLE
                btnEndCall?.visibility = View.VISIBLE
                tvDuration?.visibility = View.VISIBLE
                LordInCallService.instance?.answerCall()
                return true
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                llIncomingActions?.visibility = View.GONE
                LordInCallService.instance?.rejectCall()
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        LordInCallService.unregisterListener(callListener)
        mainHandler.removeCallbacks(durationRunnable)
        try {
            unregisterReceiver(callReceiver)
        } catch (_: Exception) {}
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
