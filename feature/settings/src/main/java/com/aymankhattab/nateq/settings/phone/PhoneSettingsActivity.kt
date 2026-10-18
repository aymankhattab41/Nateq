package com.aymankhattab.nateq.settings.phone

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
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

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateDefaultDialerStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_settings)

        settingsRepo = SettingsRepository.create(this)
        title = getString(R.string.phone_settings_title)

        setupDefaultDialerSection()
        setupModeSection()
        setupActivationSection()
        setupDialpadSizeSection()
        setupDialpadStyleSection()
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
        val btnPinShortcut =
            findViewById<MaterialButton>(R.id.btn_pin_phone_shortcut)
        val switchAppDrawer =
            findViewById<SwitchMaterial>(R.id.switch_phone_app_drawer)

        btnSetDefaultDialer?.setOnClickListener {
            requestDefaultDialerRole()
        }

        btnOpenPhone.setOnClickListener {
            val intent = Intent(this, LordPhoneActivity::class.java)
            startActivity(intent)
        }

        btnPinShortcut.setOnClickListener {
            pinPhoneShortcut()
        }

        switchAppDrawer.isChecked = settingsRepo.isPhoneLauncherIconEnabled()
        switchAppDrawer.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setPhoneLauncherIconEnabled(isChecked)
            setPhoneLauncherAliasEnabled(isChecked)
            Toast.makeText(
                this,
                if (isChecked) R.string.phone_shortcut_created
                else R.string.phone_shortcut_removed,
                Toast.LENGTH_SHORT
            ).show()
        }

        val btnPinSpeedDial =
            findViewById<MaterialButton>(R.id.btn_pin_speed_dial_shortcut)
        val switchSpeedDialDrawer =
            findViewById<SwitchMaterial>(R.id.switch_speed_dial_app_drawer)

        btnPinSpeedDial.setOnClickListener {
            pinSpeedDialShortcut()
        }

        switchSpeedDialDrawer.isChecked =
            settingsRepo.isSpeedDialLauncherIconEnabled()
        switchSpeedDialDrawer.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setSpeedDialLauncherIconEnabled(isChecked)
            setSpeedDialLauncherAliasEnabled(isChecked)
            Toast.makeText(
                this,
                if (isChecked) R.string.speed_dial_shortcut_created
                else R.string.speed_dial_shortcut_removed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun pinPhoneShortcut() {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val shortcutIntent =
                Intent(this, LordPhoneActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                }
            val pinShortcutInfo = ShortcutInfoCompat.Builder(
                this,
                "shortcut_lord_phone"
            )
                .setIcon(
                    IconCompat.createWithResource(
                        this,
                        R.drawable.ic_call
                    )
                )
                .setShortLabel(getString(R.string.phone_app_name))
                .setLongLabel(getString(R.string.phone_app_name))
                .setIntent(shortcutIntent)
                .build()

            val success = ShortcutManagerCompat.requestPinShortcut(
                this,
                pinShortcutInfo,
                null
            )
            val msg = if (success) {
                R.string.phone_pin_shortcut_success
            } else {
                R.string.phone_shortcut_created
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                R.string.phone_pin_shortcut_success,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setPhoneLauncherAliasEnabled(enabled: Boolean) {
        val alias = ComponentName(
            this,
            "com.aymankhattab.nateq.settings.phone.LordPhoneLauncherAlias"
        )
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            alias,
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun pinSpeedDialShortcut() {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val shortcutIntent =
                Intent(this, LordSpeedDialActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                }
            val pinShortcutInfo = ShortcutInfoCompat.Builder(
                this,
                "shortcut_lord_speed_dial"
            )
                .setIcon(
                    IconCompat.createWithResource(
                        this,
                        R.drawable.ic_call
                    )
                )
                .setShortLabel(getString(R.string.speed_dial_title))
                .setLongLabel(getString(R.string.speed_dial_title))
                .setIntent(shortcutIntent)
                .build()

            val success = ShortcutManagerCompat.requestPinShortcut(
                this,
                pinShortcutInfo,
                null
            )
            val msg = if (success) {
                R.string.speed_dial_pin_shortcut_success
            } else {
                R.string.speed_dial_shortcut_created
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                R.string.speed_dial_pin_shortcut_success,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setSpeedDialLauncherAliasEnabled(enabled: Boolean) {
        val alias = ComponentName(
            this,
            "com.aymankhattab.nateq.settings.phone.LordSpeedDialLauncherAlias"
        )
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            alias,
            state,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun isDefaultDialer(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE)
                as? RoleManager
            if (roleManager != null &&
                roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                return true
            }
        }
        val telecom = getSystemService(Context.TELECOM_SERVICE)
            as? TelecomManager
        return telecom?.defaultDialerPackage == packageName
    }

    private fun updateDefaultDialerStatus() {
        val isDefault = isDefaultDialer()

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
        if (isDefaultDialer()) {
            updateDefaultDialerStatus()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE)
                as? RoleManager
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                try {
                    val intent =
                        roleManager.createRequestRoleIntent(
                            RoleManager.ROLE_DIALER
                        )
                    defaultDialerLauncher.launch(intent)
                    return
                } catch (e: Exception) {
                    Log.w("PhoneSettings", "Role request failed", e)
                }
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
            defaultDialerLauncher.launch(intent)
            return
        } catch (_: Exception) {}

        try {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            defaultDialerLauncher.launch(intent)
            return
        } catch (_: Exception) {}

        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            defaultDialerLauncher.launch(intent)
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

    private fun setupDialpadSizeSection() {
        val rgSize = findViewById<RadioGroup>(R.id.rg_dialpad_size)
        val rbNormal = findViewById<RadioButton>(R.id.rb_dialpad_size_normal)
        val rbMedium = findViewById<RadioButton>(R.id.rb_dialpad_size_medium)
        val rbLarge = findViewById<RadioButton>(R.id.rb_dialpad_size_large)

        when (settingsRepo.getDialpadSizeLevel()) {
            1 -> rbMedium.isChecked = true
            2 -> rbLarge.isChecked = true
            else -> rbNormal.isChecked = true
        }

        rgSize.setOnCheckedChangeListener { _, checkedId ->
            val level = when (checkedId) {
                R.id.rb_dialpad_size_medium -> 1
                R.id.rb_dialpad_size_large -> 2
                else -> 0
            }
            settingsRepo.setDialpadSizeLevel(level)
            val msg = when (level) {
                1 -> getString(R.string.phone_dialpad_size_medium)
                2 -> getString(R.string.phone_dialpad_size_large)
                else -> getString(R.string.phone_dialpad_size_normal)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupDialpadStyleSection() {
        val rgStyle = findViewById<RadioGroup>(R.id.rg_dialpad_style)
        val rbGridWide =
            findViewById<RadioButton>(R.id.rb_dialpad_style_grid_wide)
        val rbGrid5x2 =
            findViewById<RadioButton>(R.id.rb_dialpad_style_grid_5x2)
        val rbSingleRow =
            findViewById<RadioButton>(R.id.rb_dialpad_style_single_row)

        when (settingsRepo.getDialpadLayoutStyle()) {
            SettingsRepository.DIALPAD_STYLE_GRID_5X2 ->
                rbGrid5x2.isChecked = true
            SettingsRepository.DIALPAD_STYLE_SINGLE_ROW ->
                rbSingleRow.isChecked = true
            else ->
                rbGridWide.isChecked = true
        }

        rgStyle.setOnCheckedChangeListener { _, checkedId ->
            val style = when (checkedId) {
                R.id.rb_dialpad_style_grid_5x2 ->
                    SettingsRepository.DIALPAD_STYLE_GRID_5X2
                R.id.rb_dialpad_style_single_row ->
                    SettingsRepository.DIALPAD_STYLE_SINGLE_ROW
                else ->
                    SettingsRepository.DIALPAD_STYLE_GRID_WIDE
            }
            settingsRepo.setDialpadLayoutStyle(style)
            val msg = when (style) {
                SettingsRepository.DIALPAD_STYLE_GRID_5X2 ->
                    getString(R.string.phone_dialpad_style_grid_5x2)
                SettingsRepository.DIALPAD_STYLE_SINGLE_ROW ->
                    getString(R.string.phone_dialpad_style_single_row)
                else ->
                    getString(R.string.phone_dialpad_style_grid_wide)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
