package com.aymankhattab.nateq.core.data

/** تفويض إعدادات لوحة المفاتيح والهاتف ولوحة الطلب والملفات الشخصية الخام. */
internal class SettingsPhoneInputDelegate(
    private val repo: SettingsRepository
) {
    private val prefs get() = repo.prefs

    fun getKeyboardMode(): String =
        prefs.getString(SettingsRepository.KEY_KEYBOARD_MODE, SettingsRepository.KEYBOARD_MODE_BLIND)
            ?: SettingsRepository.KEYBOARD_MODE_BLIND

    fun setKeyboardMode(mode: String) {
        prefs.edit().putString(SettingsRepository.KEY_KEYBOARD_MODE, mode).commit()
    }

    fun getKeyboardActivation(): String =
        prefs.getString(SettingsRepository.KEY_KEYBOARD_ACTIVATION, SettingsRepository.KEYBOARD_ACTIVATION_LIFT)
            ?: SettingsRepository.KEYBOARD_ACTIVATION_LIFT

    fun setKeyboardActivation(activation: String) {
        prefs.edit().putString(SettingsRepository.KEY_KEYBOARD_ACTIVATION, activation).commit()
    }

    fun isKeyboardAnnounceAfterLiftEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_KEYBOARD_ANNOUNCE_AFTER_LIFT, false)

    fun isKeyboardAnnounceAfterLift(): Boolean =
        isKeyboardAnnounceAfterLiftEnabled()

    fun setKeyboardAnnounceAfterLiftEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_KEYBOARD_ANNOUNCE_AFTER_LIFT, enabled)
            .commit()
    }

    fun setKeyboardAnnounceAfterLift(enabled: Boolean) {
        setKeyboardAnnounceAfterLiftEnabled(enabled)
    }

    fun isKeyboardHapticEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_KEYBOARD_HAPTIC, false)

    fun setKeyboardHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_KEYBOARD_HAPTIC, enabled).commit()
    }

    fun isKeyboardSoundEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_KEYBOARD_SOUND, true)

    fun setKeyboardSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_KEYBOARD_SOUND, enabled).commit()
    }

    fun isKeyboardPhoneticEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_KEYBOARD_PHONETIC, true)

    fun setKeyboardPhoneticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_KEYBOARD_PHONETIC, enabled).commit()
    }

    fun isKeyboardAlwaysShowNumberRow(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_KEYBOARD_ALWAYS_SHOW_NUMBER_ROW, false)

    fun setKeyboardAlwaysShowNumberRow(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_KEYBOARD_ALWAYS_SHOW_NUMBER_ROW, enabled)
            .commit()
    }

    fun getPhoneMode(): String =
        prefs.getString(SettingsRepository.KEY_PHONE_MODE, SettingsRepository.PHONE_MODE_BLIND)
            ?: SettingsRepository.PHONE_MODE_BLIND

    fun setPhoneMode(mode: String) {
        prefs.edit().putString(SettingsRepository.KEY_PHONE_MODE, mode).commit()
    }

    fun getPhoneActivation(): String =
        prefs.getString(SettingsRepository.KEY_PHONE_ACTIVATION, SettingsRepository.PHONE_ACTIVATION_LIFT)
            ?: SettingsRepository.PHONE_ACTIVATION_LIFT

    fun setPhoneActivation(activation: String) {
        prefs.edit().putString(SettingsRepository.KEY_PHONE_ACTIVATION, activation).commit()
    }

    fun isPhoneAnnounceAfterLiftEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_ANNOUNCE_AFTER_LIFT, false)

    fun setPhoneAnnounceAfterLiftEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_PHONE_ANNOUNCE_AFTER_LIFT, enabled)
            .commit()
    }

    fun isPhoneHapticEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_HAPTIC, false)

    fun setPhoneHapticEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_PHONE_HAPTIC, enabled).commit()
    }

    fun isPhoneSoundEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_SOUND, true)

    fun setPhoneSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_PHONE_SOUND, enabled).commit()
    }

    fun isPhoneRecordEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_RECORD_ENABLED, false)

    fun setPhoneRecordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_PHONE_RECORD_ENABLED, enabled).commit()
    }

    fun getPhoneRecordMode(): String =
        prefs.getString(SettingsRepository.KEY_PHONE_RECORD_MODE, SettingsRepository.PHONE_RECORD_MODE_MANUAL)
            ?: SettingsRepository.PHONE_RECORD_MODE_MANUAL

    fun setPhoneRecordMode(mode: String) {
        prefs.edit().putString(SettingsRepository.KEY_PHONE_RECORD_MODE, mode).commit()
    }

    fun isPhoneShowRecordButton(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_SHOW_RECORD_BUTTON, true)

    fun setPhoneShowRecordButton(show: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_PHONE_SHOW_RECORD_BUTTON, show).commit()
    }

    fun isPhoneRecordBoostVolumeEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_RECORD_BOOST_VOLUME, true)

    fun setPhoneRecordBoostVolumeEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_PHONE_RECORD_BOOST_VOLUME, enabled)
            .commit()
    }

    fun isPhoneAutoSpeakerProximityEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_AUTO_SPEAKER_PROXIMITY, false)

    fun setPhoneAutoSpeakerProximityEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_PHONE_AUTO_SPEAKER_PROXIMITY, enabled)
            .commit()
    }

    fun isPhoneHardwareKeysAnswerEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_HARDWARE_KEYS_ANSWER, true)

    fun setPhoneHardwareKeysAnswerEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_PHONE_HARDWARE_KEYS_ANSWER, enabled)
            .commit()
    }

    fun isPhoneLauncherIconEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_PHONE_LAUNCHER_ICON, false)

    fun setPhoneLauncherIconEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SettingsRepository.KEY_PHONE_LAUNCHER_ICON, enabled).commit()
    }

    fun isSpeedDialLauncherIconEnabled(): Boolean =
        prefs.getBoolean(SettingsRepository.KEY_SPEED_DIAL_LAUNCHER_ICON, false)

    fun setSpeedDialLauncherIconEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(SettingsRepository.KEY_SPEED_DIAL_LAUNCHER_ICON, enabled)
            .commit()
    }

    fun getKeyboardSizeLevel(): Int {
        val level = prefs.getInt(SettingsRepository.KEY_KEYBOARD_SIZE_LEVEL, 0).coerceIn(0, 2)
        if (getKeyboardMode() == SettingsRepository.KEYBOARD_MODE_LOW_VISION && level < 1) {
            return 1
        }
        return level
    }

    fun setKeyboardSizeLevel(level: Int) {
        val clamped = level.coerceIn(0, 2)
        prefs.edit().putInt(SettingsRepository.KEY_KEYBOARD_SIZE_LEVEL, clamped).commit()
    }

    fun getDialpadSizeLevel(): Int =
        prefs.getInt(SettingsRepository.KEY_DIALPAD_SIZE_LEVEL, 0).coerceIn(0, 2)

    fun setDialpadSizeLevel(level: Int) {
        val clamped = level.coerceIn(0, 2)
        prefs.edit().putInt(SettingsRepository.KEY_DIALPAD_SIZE_LEVEL, clamped).commit()
    }

    fun getDialpadLayoutStyle(): String =
        prefs.getString(SettingsRepository.KEY_DIALPAD_LAYOUT_STYLE, SettingsRepository.DIALPAD_STYLE_GRID_WIDE)
            ?: SettingsRepository.DIALPAD_STYLE_GRID_WIDE

    fun setDialpadLayoutStyle(style: String) {
        val effective = when (style) {
            SettingsRepository.DIALPAD_STYLE_GRID_5X2, SettingsRepository.DIALPAD_STYLE_SINGLE_ROW -> style
            else -> SettingsRepository.DIALPAD_STYLE_GRID_WIDE
        }
        prefs.edit().putString(SettingsRepository.KEY_DIALPAD_LAYOUT_STYLE, effective).commit()
    }

    fun getAppProfilesRaw(): String? =
        prefs.getString(SettingsRepository.KEY_APP_PROFILES, null)

    fun setAppProfilesRaw(raw: String): Boolean = synchronized(repo) {
        prefs.edit().putString(SettingsRepository.KEY_APP_PROFILES, raw).commit()
    }

    fun getContactVoicesRaw(): String? =
        prefs.getString(SettingsRepository.KEY_CONTACT_VOICES, null)

    fun setContactVoicesRaw(raw: String): Boolean = synchronized(repo) {
        prefs.edit().putString(SettingsRepository.KEY_CONTACT_VOICES, raw).commit()
    }
}
