package com.aymankhattab.nateq.core.data

/**
 * تفويض الإسكات الفوري والمراقبة الاستباقية — مُستخرج من [SettingsRepository]
 * ضمن تفكيك (14) دون تغيير سلوك.
 */
internal class SettingsInstantSilenceDelegate(
    private val repo: SettingsRepository
) {
    fun isShakeToStopEnabled(): Boolean =
        repo.prefs.getBoolean("shake_to_stop_enabled", false)

    fun setShakeToStopEnabled(enabled: Boolean) =
        repo.prefs.edit()
            .putBoolean("shake_to_stop_enabled", enabled).apply()

    fun isProximitySilenceEnabled(): Boolean =
        repo.prefs.getBoolean("proximity_silence_enabled", false)

    fun setProximitySilenceEnabled(enabled: Boolean) =
        repo.prefs.edit()
            .putBoolean("proximity_silence_enabled", enabled).apply()

    fun isProactiveProtectedWatchEnabled(): Boolean =
        repo.prefs.getBoolean(
            "proactive_protected_watch_enabled", false
        )

    fun setProactiveProtectedWatchEnabled(enabled: Boolean) =
        repo.prefs.edit()
            .putBoolean("proactive_protected_watch_enabled", enabled)
            .apply()
}
