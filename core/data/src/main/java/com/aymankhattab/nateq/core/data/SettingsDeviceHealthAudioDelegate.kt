package com.aymankhattab.nateq.core.data

/** تفويض إعدادات صحة الجهاز/البطارية ومؤثرات الرنات ونغمات التنبيهات. */
internal class SettingsDeviceHealthAudioDelegate(
    private val repo: SettingsRepository
) {
    private val prefs get() = repo.prefs

    fun getDeviceHealthItems(): Set<String> =
        prefs.getStringSet("device_health_items", null)
            ?.filter { it in repo.validDeviceHealthItems() }
            ?.toSet() ?: SettingsRepository.DEFAULT_DEVICE_HEALTH_ITEMS

    fun setDeviceHealthItems(items: Set<String>) {
        prefs.edit().putStringSet(
            "device_health_items",
            items.filter { it in repo.validDeviceHealthItems() }
                .toMutableSet()
        ).apply()
    }

    fun validDeviceHealthItems(): Set<String> = setOf(
        SettingsRepository.DEVICE_HEALTH_BATTERY,
        SettingsRepository.DEVICE_HEALTH_CHARGING,
        SettingsRepository.DEVICE_HEALTH_STORAGE,
        SettingsRepository.DEVICE_HEALTH_MEMORY
    )

    fun isBatteryAnnouncementEnabled(): Boolean =
        prefs.getBoolean("battery_announcement_enabled", false)

    fun setBatteryAnnouncementEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("battery_announcement_enabled", enabled)
            .apply()
    }

    fun getBatteryAnnouncementLevels(): Set<String> =
        prefs.getStringSet(
            "battery_announcement_levels",
            mutableSetOf("20", "15")
        )?.toSet() ?: setOf("20", "15")

    fun setBatteryAnnouncementLevels(levels: Set<String>) {
        prefs.edit()
            .putStringSet(
                "battery_announcement_levels",
                levels.toMutableSet()
            )
            .apply()
    }

    fun getBatteryAnnouncementVoiceId(): String? =
        repo.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY
        )

    fun setBatteryAnnouncementVoiceId(voiceId: String?) {
        if (voiceId != null) {
            repo.setPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_BATTERY,
                voiceId
            )
        }
    }

    fun getBatteryAnnouncementLanguage(): String? =
        repo.getLanguageForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY
        )

    fun setBatteryAnnouncementLanguage(language: String?) {
        repo.setLanguageForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            language
        )
    }

    fun getBatteryAnnouncementRate(): Float =
        repo.getSpeechRateForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY
        )

    fun setBatteryAnnouncementRate(rate: Float) {
        repo.setSpeechRateForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            rate
        )
    }

    fun getBatteryAnnouncementVolume(): Float =
        repo.getVolumeForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY
        )

    fun setBatteryAnnouncementVolume(volume: Float) {
        repo.setVolumeForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            volume
        )
    }

    fun validTimeChimeSounds(): Set<String> = setOf(
        "classic_bell", "digital_chime", "soft_ding"
    )

    fun isTimeChimeEnabled(): Boolean =
        prefs.getBoolean("time_chime_enabled", true)

    fun setTimeChimeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("time_chime_enabled", enabled).apply()
    }

    fun isTimeChimeAt0Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_0", true)

    fun setTimeChimeAt0Enabled(enabled: Boolean) {
        prefs.edit().putBoolean("time_chime_at_0", enabled).apply()
    }

    fun isTimeChimeAt15Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_15", false)

    fun setTimeChimeAt15Enabled(enabled: Boolean) {
        prefs.edit().putBoolean("time_chime_at_15", enabled).apply()
    }

    fun isTimeChimeAt30Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_30", false)

    fun setTimeChimeAt30Enabled(enabled: Boolean) {
        prefs.edit().putBoolean("time_chime_at_30", enabled).apply()
    }

    fun isTimeChimeAt45Enabled(): Boolean =
        prefs.getBoolean("time_chime_at_45", false)

    fun setTimeChimeAt45Enabled(enabled: Boolean) {
        prefs.edit().putBoolean("time_chime_at_45", enabled).apply()
    }

    fun getTimeChimeSound(): String {
        val value = prefs.getString("time_chime_sound", "classic_bell")
            ?: "classic_bell"
        return value.takeIf { it in validTimeChimeSounds() }
            ?: "classic_bell"
    }

    fun setTimeChimeSound(sound: String) {
        prefs.edit()
            .putString(
                "time_chime_sound",
                sound.takeIf { it in validTimeChimeSounds() }
                    ?: "classic_bell"
            )
            .apply()
    }

    fun getTimeChimeVolume(): Float =
        prefs.getFloat("time_chime_volume", 0.5f).coerceIn(0.1f, 1f)

    fun setTimeChimeVolume(volume: Float) {
        prefs.edit()
            .putFloat("time_chime_volume", volume.coerceIn(0.1f, 1f))
            .apply()
    }

    fun isTimeChimeVolumeFollowsAnnouncement(): Boolean =
        prefs.getBoolean("time_chime_volume_follows_announcement", true)

    fun setTimeChimeVolumeFollowsAnnouncement(follows: Boolean) {
        prefs.edit()
            .putBoolean("time_chime_volume_follows_announcement", follows)
            .apply()
    }

    fun getEffectiveTimeChimeVolume(): Float =
        TimeChimeVolumeLink.effective(
            followsAnnouncement =
                isTimeChimeVolumeFollowsAnnouncement(),
            announcementVolume =
                repo.getVolumeForCategory(
                    SettingsRepository.VOICE_CATEGORY_TIME
                ),
            manualVolume = getTimeChimeVolume()
        )

    fun getCustomChimeUri(): String =
        prefs.getString("custom_chime_uri", "").orEmpty()

    fun setCustomChimeUri(uri: String) {
        prefs.edit().putString("custom_chime_uri", uri).apply()
    }

    fun getCustomChimeUri15(): String =
        prefs.getString("custom_chime_uri_15", "").orEmpty()

    fun setCustomChimeUri15(uri: String) {
        prefs.edit().putString("custom_chime_uri_15", uri).apply()
    }

    fun getCustomChimeUri30(): String =
        prefs.getString("custom_chime_uri_30", "").orEmpty()

    fun setCustomChimeUri30(uri: String) {
        prefs.edit().putString("custom_chime_uri_30", uri).apply()
    }

    fun getCustomChimeUri45(): String =
        prefs.getString("custom_chime_uri_45", "").orEmpty()

    fun setCustomChimeUri45(uri: String) {
        prefs.edit().putString("custom_chime_uri_45", uri).apply()
    }

    fun isTimeAlarmMaxPrecisionEnabled(): Boolean =
        prefs.getBoolean("time_alarm_max_precision", false)

    fun setTimeAlarmMaxPrecisionEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("time_alarm_max_precision", enabled)
            .apply()
    }

    fun getBatteryCueVolume(): Float =
        prefs.getFloat("battery_cue_volume", 0.8f).coerceIn(0.1f, 1f)

    fun setBatteryCueVolume(volume: Float) {
        prefs.edit()
            .putFloat("battery_cue_volume", volume.coerceIn(0.1f, 1f))
            .apply()
    }

    fun getBatterySoundCueMode(): Int =
        prefs.getInt("battery_sound_cue_mode", 0).coerceIn(0, 2)

    fun setBatterySoundCueMode(mode: Int) {
        prefs.edit()
            .putInt("battery_sound_cue_mode", mode.coerceIn(0, 2))
            .apply()
    }
}
