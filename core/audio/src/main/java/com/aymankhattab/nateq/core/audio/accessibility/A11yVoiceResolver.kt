package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.data.AppProfileStore
import com.aymankhattab.nateq.core.data.EffectiveVoice
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoiceProfileResolver

/**
 * حسم تجاوزات الصوت لخدمات الشاشة (ع٤١-ب10.3، ب11.2).
 * يجمع ملفَ التطبيق الأمامي وصوتَ جهة الاتصال (باسم المرسِل) على
 * أساس `EffectiveVoice` الافتراضي، ثم يُمرَّر إلى منسّق النطق.
 */
object A11yVoiceResolver {

    /**
     * @return التجاوزات المحسومة، أو null إن لم توجد أيُّ طبقة.
     */
    fun resolve(
        context: Context,
        settings: SettingsRepository,
        packageName: String?,
        senderName: String?
    ): EffectiveVoice? {
        val appEnabled = settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_APP_PROFILES
        ) && settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH
        )
        val contactEnabled = settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CONTACT_VOICES
        )
        val app = if (appEnabled && !packageName.isNullOrBlank()) {
            AppProfileStore.create(context).get(packageName)?.toVoiceProfile()
        } else {
            null
        }
        val contact = if (contactEnabled && !senderName.isNullOrBlank()) {
            ContactVoiceLookup.byName(context, senderName)
        } else {
            null
        }
        if (app == null && contact == null) return null
        return VoiceProfileResolver.effective(
            base = EffectiveVoice(
                engine = null,
                voiceId = null,
                speechRate = 1f,
                pitch = 1f,
                volume = 1f
            ),
            appProfile = app,
            contact = contact
        )
    }
}
