package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * نموذج FeatureFlags مركزي لميزات إمكانية الوصول (ع٢٧-19).
 * كل ميزة لها مفتاح واحد فقط وافتراضها مطفأ (معطل).
 */
object FeatureFlags {

    // مفاتيح الميزات المرقّمة
    const val FEATURE_READ_MORE_EXPAND = "a11y_read_more_expand"
    const val FEATURE_CHAT_LIVE_READING = "a11y_chat_live_reading"
    const val FEATURE_CHAT_SUMMARY = "a11y_chat_summary"
    const val FEATURE_CONTACT_VOICES = "a11y_contact_voices"
    const val FEATURE_APP_PROFILES = "a11y_app_profiles"
    const val FEATURE_AUTO_PROFILE_SWITCH = "a11y_auto_profile_switch"
    const val FEATURE_CUSTOM_UNLABELED = "a11y_custom_unlabeled"
    const val FEATURE_TYPING_ECHO = "a11y_typing_echo"
    const val FEATURE_KEY_SHORTCUTS = "a11y_key_shortcuts"
    const val FEATURE_ACCESSIBILITY_BUTTON = "a11y_accessibility_button"
    const val FEATURE_READ_SCREEN_NOW = "a11y_read_screen_now"
    const val FEATURE_CONTINUOUS_READING = "a11y_continuous_reading"
    const val FEATURE_CLIPBOARD_READING = "a11y_clipboard_reading"
    const val FEATURE_OTP_READING = "a11y_otp_reading"
    const val FEATURE_URL_SHORTENING = "a11y_url_shortening"
    const val FEATURE_POPUP_ANNOUNCEMENT = "a11y_popup_announcement"
    const val FEATURE_PROGRESS_MONITORING = "a11y_progress_monitoring"
    const val FEATURE_CALL_KEY_ACTIONS = "a11y_call_key_actions"
    const val FEATURE_WHERE_AM_I = "a11y_where_am_i"
    const val FEATURE_OCR_READING = "a11y_ocr_reading"
    const val FEATURE_BLUETOOTH_ANNOUNCEMENT = "a11y_bluetooth_announcement"

    val ALL_FEATURES = listOf(
        FEATURE_READ_MORE_EXPAND,
        FEATURE_CHAT_LIVE_READING,
        FEATURE_CHAT_SUMMARY,
        FEATURE_CONTACT_VOICES,
        FEATURE_APP_PROFILES,
        FEATURE_AUTO_PROFILE_SWITCH,
        FEATURE_CUSTOM_UNLABELED,
        FEATURE_TYPING_ECHO,
        FEATURE_KEY_SHORTCUTS,
        FEATURE_ACCESSIBILITY_BUTTON,
        FEATURE_READ_SCREEN_NOW,
        FEATURE_CONTINUOUS_READING,
        FEATURE_CLIPBOARD_READING,
        FEATURE_OTP_READING,
        FEATURE_URL_SHORTENING,
        FEATURE_POPUP_ANNOUNCEMENT,
        FEATURE_PROGRESS_MONITORING,
        FEATURE_CALL_KEY_ACTIONS,
        FEATURE_WHERE_AM_I,
        FEATURE_OCR_READING,
        FEATURE_BLUETOOTH_ANNOUNCEMENT
    )

    /**
     * فحص مركزي: هل الميزة نشطة فعلياً؟
     * يراعي:
     * 1. هل الخدمة متصلة وغير متوقفة مؤقتاً؟
     * 2. هل الميزة مفعّلة في الإعدادات؟
     * 3. إن وُجد اسم حزمة: هل تسمح سياسة التطبيقات (AppPolicy)؟
     */
    fun isActive(
        feature: String,
        packageName: String? = null,
        context: Context? = null,
        settings: SettingsRepository? = null,
        stateStore: A11yStateStore? = null
    ): Boolean {
        // التحقق من حالة الإيقاف المؤقت للخدمة
        val store = stateStore ?: A11yStateStore.getInstance(context)
        if (store != null && (!store.isConnected() || store.isPaused())) {
            return false
        }

        // التحقق من مفتاح الميزة
        val repo = settings ?: context?.let { SettingsRepository.create(it) }
        val isFeatureEnabled = repo?.isAccessibilityFeatureEnabled(feature)
            ?: false
        if (!isFeatureEnabled) return false

        // التحقق من سياسة التطبيقات إن طُلب لحزمة محددة
        if (packageName != null) {
            val userAllowed = repo?.getA11yAllowedPackages()
            val allowAll = repo?.isA11yAllowAllApps() ?: false
            if (!AppPolicy.isAllowed(packageName, userAllowed, allowAll)) {
                return false
            }
        }

        return true
    }
}
