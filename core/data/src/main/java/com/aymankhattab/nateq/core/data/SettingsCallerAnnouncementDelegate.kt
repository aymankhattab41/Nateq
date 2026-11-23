package com.aymankhattab.nateq.core.data

/** تفويض إعدادات إعلان اسم المتصل. */
internal class SettingsCallerAnnouncementDelegate(
    private val repo: SettingsRepository
) {
    private val prefs get() = repo.prefs

    fun isCallerAnnouncementEnabled(): Boolean =
        prefs.getBoolean("caller_announcement_enabled", false)

    fun setCallerAnnouncementEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("caller_announcement_enabled", enabled)
            .apply()
    }

    fun isCallerLockScreenPrivacyEnabled(): Boolean =
        prefs.getBoolean("caller_lock_screen_privacy_enabled", false)

    fun setCallerLockScreenPrivacyEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("caller_lock_screen_privacy_enabled", enabled)
            .apply()
    }

    fun isCallerAnnouncementDuringCallEnabled(): Boolean =
        prefs.getBoolean(
            "caller_announcement_during_call_enabled",
            false
        )

    fun setCallerAnnouncementDuringCallEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("caller_announcement_during_call_enabled", enabled)
            .apply()
    }

    fun isCallerRingDuckingEnabled(): Boolean =
        prefs.getBoolean("caller_ring_ducking_enabled", false)

    fun setCallerRingDuckingEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("caller_ring_ducking_enabled", enabled)
            .apply()
    }

    fun getCallerRingDuckPercent(): Int =
        prefs.getInt(
            "caller_ring_duck_percent",
            SettingsRepository.CALLER_RING_DUCK_DEFAULT
        )

    fun setCallerRingDuckPercent(percent: Int) {
        prefs.edit()
            .putInt(
                "caller_ring_duck_percent",
                percent.coerceIn(
                    SettingsRepository.CALLER_RING_DUCK_MIN,
                    SettingsRepository.CALLER_RING_DUCK_MAX
                )
            )
            .apply()
    }

    fun getCallerAnnouncementRepeat(): Int =
        prefs.getInt("caller_announcement_repeat", 1)

    fun setCallerAnnouncementRepeat(repeat: Int) {
        prefs.edit()
            .putInt(
                "caller_announcement_repeat",
                repeat.coerceIn(
                    SettingsRepository.CALLER_REPEAT_MIN,
                    SettingsRepository.CALLER_REPEAT_MAX
                )
            )
            .apply()
    }

    fun getCallWaitingRepeat(): Int =
        prefs.getInt("call_waiting_repeat", 1)
            .coerceIn(
                SettingsRepository.CALLER_REPEAT_MIN,
                SettingsRepository.CALLER_REPEAT_MAX
            )

    fun setCallWaitingRepeat(repeat: Int) {
        prefs.edit()
            .putInt(
                "call_waiting_repeat",
                repeat.coerceIn(
                    SettingsRepository.CALLER_REPEAT_MIN,
                    SettingsRepository.CALLER_REPEAT_MAX
                )
            )
            .apply()
    }

    fun getCallerAnnouncementIntervalSeconds(): Int =
        prefs.getInt("caller_announcement_interval_seconds", 3)
            .coerceIn(
                SettingsRepository.CALLER_INTERVAL_MIN,
                SettingsRepository.CALLER_INTERVAL_MAX
            )

    fun setCallerAnnouncementIntervalSeconds(seconds: Int) {
        prefs.edit()
            .putInt(
                "caller_announcement_interval_seconds",
                seconds.coerceIn(
                    SettingsRepository.CALLER_INTERVAL_MIN,
                    SettingsRepository.CALLER_INTERVAL_MAX
                )
            )
            .apply()
    }

    fun getCallerAnnouncementArabicVoiceId(): String? =
        repo.normalizeVoiceId(
            prefs.getString("caller_announcement_voice_ar", null)
        ) ?: repo.getPreferredVoiceIdForCategory(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
        )

    fun setCallerAnnouncementArabicVoiceId(voiceId: String?) {
        prefs.edit()
            .putString("caller_announcement_voice_ar", voiceId)
            .apply()
        if (voiceId != null) {
            runCatching {
                repo.setPreferredVoiceIdForCategory(
                    SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
                    voiceId
                )
            }
        }
    }

    fun getCallerAnnouncementEnglishVoiceId(): String? =
        repo.normalizeVoiceId(
            prefs.getString("caller_announcement_voice_en", null)
        ) ?: repo.getPreferredVoiceIdForCategory(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
        )

    fun setCallerAnnouncementEnglishVoiceId(voiceId: String?) {
        prefs.edit()
            .putString("caller_announcement_voice_en", voiceId)
            .apply()
        if (voiceId != null) {
            runCatching {
                repo.setPreferredVoiceIdForCategory(
                    SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN,
                    voiceId
                )
            }
        }
    }

    fun getCallerAnnouncementRate(): Float =
        prefs.getFloat("caller_announcement_rate", 1.0f)

    fun setCallerAnnouncementRate(rate: Float) {
        prefs.edit()
            .putFloat(
                "caller_announcement_rate",
                rate.coerceIn(0f, 2f)
            )
            .apply()
    }

    fun getCallerAnnouncementVolume(): Float =
        prefs.getFloat("caller_announcement_volume", 1.0f)

    fun setCallerAnnouncementVolume(volume: Float) {
        prefs.edit()
            .putFloat(
                "caller_announcement_volume",
                volume.coerceIn(0f, 1f)
            )
            .apply()
    }
}
