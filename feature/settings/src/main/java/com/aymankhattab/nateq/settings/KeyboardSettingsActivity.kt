package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.feature.settings.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * شاشة إعدادات وتخصيص لوحة مفاتيح Lord TTS الميسّرة.
 * تتيح اختيار وضع العرض (كفيف / ضعيف البصر)، وطريقة التفعيل
 * (رفع الإصبع / الضغط مرتين)، والخيارات المتقدمة، وتجربتها فوراً.
 */
class KeyboardSettingsActivity : AppCompatActivity() {

    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            com.google.android.material.R.style
                .Theme_MaterialComponents_DayNight_NoActionBar
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_settings)

        settingsRepo = SettingsRepository(this)
        title = getString(R.string.keyboard_settings_title)

        setupModeSection()
        setupActivationSection()
        setupSwitchesSection()
        setupActionButtons()
        setupTestArea()
    }

    private fun setupModeSection() {
        val rgMode = findViewById<RadioGroup>(R.id.rg_keyboard_mode)
        val rbBlind = findViewById<RadioButton>(R.id.rb_mode_blind)
        val rbLowVision = findViewById<RadioButton>(R.id.rb_mode_low_vision)

        val currentMode = settingsRepo.getKeyboardMode()
        if (currentMode == SettingsRepository.KEYBOARD_MODE_LOW_VISION) {
            rbLowVision.isChecked = true
        } else {
            rbBlind.isChecked = true
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.rb_mode_low_vision) {
                SettingsRepository.KEYBOARD_MODE_LOW_VISION
            } else {
                SettingsRepository.KEYBOARD_MODE_BLIND
            }
            settingsRepo.setKeyboardMode(newMode)
            val isLowVision =
                newMode == SettingsRepository.KEYBOARD_MODE_LOW_VISION
            val msg = if (isLowVision) {
                getString(R.string.keyboard_mode_low_vision)
            } else {
                getString(R.string.keyboard_mode_blind)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupActivationSection() {
        val rgActivation =
            findViewById<RadioGroup>(R.id.rg_keyboard_activation)
        val rbLift = findViewById<RadioButton>(R.id.rb_activation_lift)
        val rbDoubleTap =
            findViewById<RadioButton>(R.id.rb_activation_double_tap)

        val currentActivation = settingsRepo.getKeyboardActivation()
        if (currentActivation ==
            SettingsRepository.KEYBOARD_ACTIVATION_DOUBLE_TAP
        ) {
            rbDoubleTap.isChecked = true
        } else {
            rbLift.isChecked = true
        }

        rgActivation.setOnCheckedChangeListener { _, checkedId ->
            val isDoubleTap =
                checkedId == R.id.rb_activation_double_tap
            val newActivation = if (isDoubleTap) {
                SettingsRepository.KEYBOARD_ACTIVATION_DOUBLE_TAP
            } else {
                SettingsRepository.KEYBOARD_ACTIVATION_LIFT
            }
            settingsRepo.setKeyboardActivation(newActivation)
            val msg = if (newActivation ==
                SettingsRepository.KEYBOARD_ACTIVATION_DOUBLE_TAP
            ) {
                getString(R.string.keyboard_activation_double_tap)
            } else {
                getString(R.string.keyboard_activation_lift)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSwitchesSection() {
        val swAnnounceAfterLift =
            findViewById<SwitchMaterial>(R.id.switch_announce_after_lift)
        val swPhonetic =
            findViewById<SwitchMaterial>(R.id.switch_phonetic)
        val swHaptic =
            findViewById<SwitchMaterial>(R.id.switch_haptic)
        val swSound =
            findViewById<SwitchMaterial>(R.id.switch_sound)

        swAnnounceAfterLift.isChecked =
            settingsRepo.isKeyboardAnnounceAfterLift()
        swPhonetic.isChecked =
            settingsRepo.isKeyboardPhoneticEnabled()
        swHaptic.isChecked =
            settingsRepo.isKeyboardHapticEnabled()
        swSound.isChecked =
            settingsRepo.isKeyboardSoundEnabled()

        swAnnounceAfterLift.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setKeyboardAnnounceAfterLift(isChecked)
        }
        swPhonetic.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setKeyboardPhoneticEnabled(isChecked)
        }
        swHaptic.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setKeyboardHapticEnabled(isChecked)
        }
        swSound.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setKeyboardSoundEnabled(isChecked)
        }
    }

    private fun setupActionButtons() {
        val btnEnable = findViewById<MaterialButton>(R.id.btn_enable_ime)
        val btnSelect = findViewById<MaterialButton>(R.id.btn_select_ime)

        btnEnable.setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    R.string.oem_screen_open_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnSelect.setOnClickListener {
            val imm =
                getSystemService(Context.INPUT_METHOD_SERVICE) as?
                    InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    private fun setupTestArea() {
        val etTest = findViewById<EditText>(R.id.et_test_typing)
        val btnClear = findViewById<MaterialButton>(R.id.btn_clear_test)

        btnClear.setOnClickListener {
            etTest.text.clear()
        }
    }
}
