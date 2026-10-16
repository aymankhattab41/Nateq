package com.aymankhattab.nateq.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private var tvImeStatus: TextView? = null
    private var btnEnableIme: MaterialButton? = null
    private var btnSelectIme: MaterialButton? = null
    private var btnMicPermission: MaterialButton? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        updateMicPermissionUi()
        if (isGranted) {
            Toast.makeText(
                this,
                R.string.keyboard_mic_granted_toast,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                R.string.keyboard_mic_denied_toast,
                Toast.LENGTH_LONG
            ).show()
        }
    }

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

        if (intent.getBooleanExtra("request_mic", false)) {
            requestMicPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        updateImeStatusUi()
        updateMicPermissionUi()
    }

    private fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return false
        val list = imm.enabledInputMethodList
        return list.any { it.packageName == packageName }
    }

    private fun isKeyboardSelected(): Boolean {
        val currentIme = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: return false
        return currentIme.contains(packageName)
    }

    private fun updateImeStatusUi() {
        val statusView = tvImeStatus ?: return
        val enabled = isKeyboardEnabled()
        val selected = isKeyboardSelected()

        when {
            !enabled -> {
                statusView.text =
                    getString(R.string.keyboard_status_not_enabled)
                statusView.setTextColor(Color.rgb(220, 50, 50))
                btnSelectIme?.isEnabled = false
            }
            !selected -> {
                statusView.text =
                    getString(R.string.keyboard_status_enabled_not_selected)
                statusView.setTextColor(Color.rgb(230, 150, 0))
                btnSelectIme?.isEnabled = true
            }
            else -> {
                statusView.text =
                    getString(R.string.keyboard_status_active)
                statusView.setTextColor(Color.rgb(46, 125, 50))
                btnSelectIme?.isEnabled = true
            }
        }
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
            settingsRepo.isKeyboardAnnounceAfterLiftEnabled()
        swPhonetic.isChecked =
            settingsRepo.isKeyboardPhoneticEnabled()
        swHaptic.isChecked =
            settingsRepo.isKeyboardHapticEnabled()
        swSound.isChecked =
            settingsRepo.isKeyboardSoundEnabled()

        swAnnounceAfterLift.setOnCheckedChangeListener { _, isChecked ->
            settingsRepo.setKeyboardAnnounceAfterLiftEnabled(isChecked)
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
        tvImeStatus = findViewById(R.id.tv_ime_status)
        btnEnableIme = findViewById(R.id.btn_enable_ime)
        btnSelectIme = findViewById(R.id.btn_select_ime)
        btnMicPermission = findViewById(R.id.btn_mic_permission)

        btnEnableIme?.setOnClickListener {
            openInputMethodSettings()
        }

        btnSelectIme?.setOnClickListener {
            showInputMethodPicker()
        }

        btnMicPermission?.setOnClickListener {
            requestMicPermission()
        }
    }

    private fun requestMicPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            Toast.makeText(
                this,
                R.string.keyboard_mic_status_granted,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateMicPermissionUi() {
        val btn = btnMicPermission ?: return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            btn.text = getString(R.string.keyboard_mic_status_granted)
            btn.isEnabled = false
        } else {
            btn.text = getString(R.string.keyboard_grant_mic_permission)
            btn.isEnabled = true
        }
    }

    private fun openInputMethodSettings() {
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

    private fun showInputMethodPicker() {
        val imm =
            getSystemService(Context.INPUT_METHOD_SERVICE) as?
                InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun setupTestArea() {
        val etTest = findViewById<EditText>(R.id.et_test_typing)
        val btnClear = findViewById<MaterialButton>(R.id.btn_clear_test)

        etTest.isFocusable = true
        etTest.isFocusableInTouchMode = true

        etTest.setOnClickListener {
            etTest.requestFocus()
            if (!isKeyboardEnabled()) {
                Toast.makeText(
                    this,
                    R.string.keyboard_not_enabled_hint,
                    Toast.LENGTH_LONG
                ).show()
                openInputMethodSettings()
                return@setOnClickListener
            }
            if (!isKeyboardSelected()) {
                Toast.makeText(
                    this,
                    R.string.keyboard_not_selected_hint,
                    Toast.LENGTH_LONG
                ).show()
                showInputMethodPicker()
                return@setOnClickListener
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            imm?.showSoftInput(etTest, InputMethodManager.SHOW_IMPLICIT)
        }

        etTest.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isKeyboardSelected()) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                imm?.showSoftInput(etTest, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        btnClear.setOnClickListener {
            etTest.text.clear()
        }
    }
}
