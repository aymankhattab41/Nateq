package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.data.AppProfileStore
import com.aymankhattab.nateq.core.data.EffectiveVoice
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoiceProfileResolver

/**
 * حسم تجاوزات الصوت لخدمات الشاشة (ع٤١-ب10.3، ب11.2، ع٤٢-11، ع٤٥).
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
            autoSwitchEnabled -> AccessibilityBridge.resolveForegroundPackage()
            else -> null
        }

        val app = if (appProfilesEnabled && !targetPackage.isNullOrBlank()) {
            AppProfileStore.create(context).get(targetPackage)?.toVoiceProfile()
        } else {
            null
        }

        val effectiveSender = senderName
            ?: AccessibilityBridge.currentSenderHint()
        val hasContactQuery =
            !effectiveSender.isNullOrBlank() || !conversationKey.isNullOrBlank()
        val contact = if (contactEnabled && hasContactQuery) {
            ContactVoiceLookup.find(
                context = context,
                keyOrName = effectiveSender.orEmpty(),
                conversationKey = conversationKey
            )
        } else {
            null
        }

        val winningLayer = when {
            contact != null -> "جهة اتصال"
            app != null -> "ملف تطبيق"
            else -> "افتراضي"
        }

        val effective = VoiceProfileResolver.effective(
            base = EffectiveVoice(),
            appProfile = app,
            contact = contact
        )

        VoiceResolutionTracker.record(
            packageName = targetPackage,
            senderName = effectiveSender,
            winningLayer = winningLayer,
            engine = effective.engine,
            voiceId = effective.voiceId,
            speechRate = effective.speechRate,
            pitch = effective.pitch,
            volume = effective.volume
        )

        if (app == null && contact == null) return null
        return effective
    }
}
