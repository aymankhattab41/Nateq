package com.aymankhattab.nateq.settings.phone

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * شاشة إعدادات هاتف Lord TTS الميسّر وتسجيل المكالمات.
 */
@Suppress("DEPRECATION")
class PhoneSettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private var tvDefaultDialerStatus: TextView? = null
    private var btnSetDefaultDialer: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_settings)

        settingsRepo = SettingsRepository(this)
        title = getString(R.string.phone_settings_title)

        setupDefaultDialerSection()
        setupModeSection()
        setupActivationSection()
        setupRecordingSection()
        setupHardwareSection()
    }

    override fun onResume() {
        super.onResume()
        updateDefaultDialerStatus()
    }

    private fun setupDefaultDialerSection() {
        tvDefaultDialerStatus = findViewById(R.id.tv_default_dialer_status)
        btnSetDefaultDialer = findViewById(R.id.btn_set_default_dialer)
        val btnOpenPhone =
            findViewById<MaterialButton>(R.id.btn_open_phone_app)

        btnSetDefaultDialer?.setOnClickListener {
            requestDefaultDialerRole()
        }

        btnOpenPhone.setOnClickListener {
            val intent = Intent(this, LordPhoneActivity::class.java)
            startActivity(intent)
        }
    }

    private fun updateDefaultDialerStatus() {
        val telecom = getSystemService(Context.TELECOM_SERVICE)
            as? TelecomManager
        val isDefault = telecom?.defaultDialerPackage == packageName

        if (isDefault) {
            tvDefaultDialerStatus?.text =
                getString(R.string.phone_status_is_default)
            tvDefaultDialerStatus?.setTextColor(0xFF388E3C.toInt())
            btnSetDefaultDialer?.visibility = View.GONE
        } else {
            tvDefaultDialerStatus?.text =
                getString(R.string.phone_status_not_default)
            tvDefaultDialerStatus?.setTextColor(0xFFE65100.toInt())
            btnSetDefaultDialer?.visibility = View.VISIBLE
        }
    }

    private fun requestDefaultDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE)
                as? RoleManager
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                val intent =
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivity(intent)
                return
            }
        }

        try {
            val intent = Intent(
                TelecomManager.ACTION_CHANGE_DEFAULT_DIALER
            ).apply {
                putExtra(
                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                    packageName
                )
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun setupModeSection() {
        val rg = findViewById<RadioGroup>(R.id.rg_phone_mode)
        val rbBlind = findViewById<RadioButton>(R.id.rb_phone_mode_blind)
        val rbLowVision =
            findViewById<RadioButton>(R.id.rb_phone_mode_low_vision)

        val current = settingsRepo.getPhoneMode()
        if (current == SettingsRepository.PHONE_MODE_LOW_VISION) {
            rbLowVision.isChecked = true
        } else {
            rbBlind.isChecked = true
        }

        rg.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rb_phone_mode_low_vision) {
                SettingsRepository.PHONE_MODE_LOW_VISION
            } else {
                SettingsRepository.PHONE_MODE_BLIND
            }
            settingsRepo.setPhoneMode(mode)
        }
    }

    private fun setupActivationSection() {
        val rg = findViewById<RadioGroup>(R.id.rg_phone_activation)
        val rbLift = findViewById<RadioButton>(R.id.rb_phone_activation_lift)
        val rbDouble =
            findViewById<RadioButton>(R.id.rb_phone_activation_double_tap)
        val swAnnounce =
            findViewById<SwitchMaterial>(R.id.switch_phone_announce_after_lift)

        val current = settingsRepo.getPhoneActivation()
        if (current == SettingsRepository.PHONE_ACTIVATION_DOUBLE_TAP) {
            rbDouble.isChecked = true
        } else {
            rbLift.isChecked = true
        }

        rg.setOnCheckedChangeListener { _, checkedId ->
            val activation = if (checkedId ==
                R.id.rb_phone_activation_double_tap
            ) {
                SettingsRepository.PHONE_ACTIVATION_DOUBLE_TAP
            } else {
                SettingsRepository.PHONE_ACTIVATION_LIFT
            }
            settingsRepo.setPhoneActivation(activation)
        }

        swAnnounce.isChecked = settingsRepo.isPhoneAnnounceAfterLiftEnabled()
        swAnnounce.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneAnnounceAfterLiftEnabled(isChecked)
        }
    }

    private fun setupRecordingSection() {
        val swEnabled =
            findViewById<SwitchMaterial>(R.id.switch_phone_record_enabled)
        val llDetails =
            findViewById<View>(R.id.ll_record_details_container)
        val rgMode = findViewById<RadioGroup>(R.id.rg_phone_record_mode)
        val rbManual = findViewById<RadioButton>(R.id.rb_record_manual)
        val rbAutoAll = findViewById<RadioButton>(R.id.rb_record_auto_all)
        val rbAutoUnsaved =
            findViewById<RadioButton>(R.id.rb_record_auto_unsaved)
        val swShowBtn =
            findViewById<SwitchMaterial>(R.id.switch_phone_show_record_button)
        val swBoost =
            findViewById<SwitchMaterial>(R.id.switch_phone_record_boost_volume)

        val isEnabled = settingsRepo.isPhoneRecordEnabled()
        swEnabled.isChecked = isEnabled
        llDetails.visibility = if (isEnabled) View.VISIBLE else View.GONE

        swEnabled.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneRecordEnabled(isChecked)
            llDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        when (settingsRepo.getPhoneRecordMode()) {
            SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL ->
                rbAutoAll.isChecked = true
            SettingsRepository.PHONE_RECORD_MODE_AUTO_UNSAVED ->
                rbAutoUnsaved.isChecked = true
            else ->
                rbManual.isChecked = true
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_record_auto_all ->
                    SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL
                R.id.rb_record_auto_unsaved ->
                    SettingsRepository.PHONE_RECORD_MODE_AUTO_UNSAVED
                else ->
                    SettingsRepository.PHONE_RECORD_MODE_MANUAL
            }
            settingsRepo.setPhoneRecordMode(mode)
        }

        swShowBtn.isChecked = settingsRepo.isPhoneShowRecordButton()
        swShowBtn.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneShowRecordButton(isChecked)
        }

        swBoost.isChecked = settingsRepo.isPhoneRecordBoostVolumeEnabled()
        swBoost.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneRecordBoostVolumeEnabled(isChecked)
        }
    }

    private fun setupHardwareSection() {
        val swAutoSpeaker =
            findViewById<SwitchMaterial>(
                R.id.switch_phone_auto_speaker_proximity
            )
        val swHwKeys =
            findViewById<SwitchMaterial>(
                R.id.switch_phone_hardware_keys_answer
            )
        val swHaptic = findViewById<SwitchMaterial>(R.id.switch_phone_haptic)
        val swSound = findViewById<SwitchMaterial>(R.id.switch_phone_sound)

        swAutoSpeaker.isChecked =
            settingsRepo.isPhoneAutoSpeakerProximityEnabled()
        swAutoSpeaker.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneAutoSpeakerProximityEnabled(isChecked)
        }

        swHwKeys.isChecked =
            settingsRepo.isPhoneHardwareKeysAnswerEnabled()
        swHwKeys.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneHardwareKeysAnswerEnabled(isChecked)
        }

        swHaptic.isChecked = settingsRepo.isPhoneHapticEnabled()
        swHaptic.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneHapticEnabled(isChecked)
        }

        swSound.isChecked = settingsRepo.isPhoneSoundEnabled()
        swSound.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneSoundEnabled(isChecked)
        }
    }
}
