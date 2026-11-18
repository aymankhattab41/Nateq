package com.aymankhattab.nateq.core.data

/**
 * تفويض محرك TTS الاحتياطي للإعلانات — مُستخرج من [SettingsRepository]
 * ضمن تفكيك (14) دون تغيير سلوك.
 */
internal class SettingsAnnouncementFallbackDelegate(
    private val repo: SettingsRepository
) {
    fun getAnnouncementFallbackEngine(): String? =
        repo.prefs.getString("announcement_fallback_engine", null)

    fun setAnnouncementFallbackEngine(enginePackage: String?) {
        if (enginePackage.isNullOrBlank()) {
            repo.prefs.edit()
                .remove("announcement_fallback_engine").apply()
        } else {
            repo.prefs.edit()
                .putString("announcement_fallback_engine", enginePackage)
                .apply()
        }
    }
}
