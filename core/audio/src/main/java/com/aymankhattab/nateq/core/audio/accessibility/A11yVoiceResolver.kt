package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.data.AppProfileStore
import com.aymankhattab.nateq.core.data.EffectiveVoice
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoiceProfileResolver

/**
 * حسم تجاوزات الصوت لخدمات الشاشة (ع٤١-ب10.3، ب11.2، ع٤٢-11).
 * يجمع ملفَ التطبيق الأمامي وصوتَ جهة الاتصال (باسم المرسِل أو مفتاح المحادثة)
 * على أساس `EffectiveVoice` الافتراضي، ثم يُمرَّر إلى منسّق النطق.
 */
object A11yVoiceResolver {

    /**
     * @return التجاوزات المحسومة، أو null إن لم توجد أيُّ طبقة.
     */
    fun resolve(
        context: Context,
        settings: SettingsRepository,
        packageName: String? = null,
        senderName: String? = null,
        conversationKey: String? = null
    ): EffectiveVoice? {
        val appProfilesEnabled = settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_APP_PROFILES
        )
        val autoSwitchEnabled = settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH
        )
        val contactEnabled = settings.isAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CONTACT_VOICES
        )

        val targetPackage = when {
            !packageName.isNullOrBlank() -> packageName
            autoSwitchEnabled -> AccessibilityBridge.foregroundPackage()
            else -> null
        }

        val app = if (appProfilesEnabled && !targetPackage.isNullOrBlank()) {
            AppProfileStore.create(context).get(targetPackage)?.toVoiceProfile()
        } else {
            null
        }

        val hasContactQuery =
            !senderName.isNullOrBlank() || !conversationKey.isNullOrBlank()
        val contact = if (contactEnabled && hasContactQuery) {
            ContactVoiceLookup.find(
                context = context,
                keyOrName = senderName.orEmpty(),
                conversationKey = conversationKey
            )
        } else {
            null
        }

        if (app == null && contact == null) return null

        return VoiceProfileResolver.effective(
            base = EffectiveVoice(),
            appProfile = app,
            contact = contact
        )
    }
}
