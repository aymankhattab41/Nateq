package com.aymankhattab.nateq.core.data

/** تفويض جمل إعلان المتصل (بادئة/لاحقة) وقالب إعلان الرسائل. */
internal class SettingsAnnouncementTemplateDelegate(
    private val repo: SettingsRepository
) {
    private val prefs get() = repo.prefs

    fun isCallerPrefixEnabled(): Boolean =
        prefs.getBoolean("caller_prefix_enabled", false)

    fun setCallerPrefixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("caller_prefix_enabled", enabled).apply()
    }

    fun getCallerPrefixText(): String =
        prefs.getString("caller_prefix_text", "") ?: ""

    fun setCallerPrefixText(text: String?) {
        prefs.edit()
            .putString("caller_prefix_text", text?.trim().orEmpty())
            .apply()
    }

    fun isCallerSuffixEnabled(): Boolean =
        prefs.getBoolean("caller_suffix_enabled", false)

    fun setCallerSuffixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("caller_suffix_enabled", enabled).apply()
    }

    fun getCallerSuffixText(): String =
        prefs.getString("caller_suffix_text", "") ?: ""

    fun setCallerSuffixText(text: String?) {
        prefs.edit()
            .putString("caller_suffix_text", text?.trim().orEmpty())
            .apply()
    }

    fun getSmsAnnouncementTemplate(): String =
        prefs.getString("sms_announcement_template", "") ?: ""

    fun setSmsAnnouncementTemplate(template: String?) {
        prefs.edit()
            .putString("sms_announcement_template", template)
            .apply()
    }
}
