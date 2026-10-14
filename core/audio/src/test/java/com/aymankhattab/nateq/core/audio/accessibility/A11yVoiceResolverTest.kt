package com.aymankhattab.nateq.core.audio.accessibility

import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.AppProfile
import com.aymankhattab.nateq.core.data.AppProfileStore
import com.aymankhattab.nateq.core.data.ContactVoiceStore
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoiceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات حسم أصوات إمكانية الوصول وفصل المفاتيح (ع٤٢-11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class A11yVoiceResolverTest {

    private val context =
        ApplicationProvider.getApplicationContext<android.content.Context>()
    private val settings = SettingsRepository.create(context)

    @Test
    fun singleFlagSufficiency_appProfilesAloneResolvesExplicitPackage() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_APP_PROFILES, true
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH, false
        )
        val store = AppProfileStore.create(context)
        store.put(
            AppProfile(
                packageName = "com.test.app",
                voiceId = "testVoice",
                speechRate = 1.3f
            )
        )

        val resolved = A11yVoiceResolver.resolve(
            context = context,
            settings = settings,
            packageName = "com.test.app",
            senderName = null
        )
        assertNotNull(resolved)
        assertEquals("testVoice", resolved!!.voiceId)
        assertEquals(1.3f, resolved.speechRate!!, 0.001f)
        assertNull(resolved.pitch)
    }

    @Test
    fun autoSwitch_fallsBackToForegroundPackageWhenEnabled() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_APP_PROFILES, true
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH, true
        )
        val store = AppProfileStore.create(context)
        store.put(
            AppProfile(
                packageName = "com.foreground.app",
                voiceId = "fgVoice"
            )
        )
        AccessibilityBridge.updateForeground(
            "com.foreground.app", "MainActivity"
        )

        val resolved = A11yVoiceResolver.resolve(
            context = context,
            settings = settings,
            packageName = null,
            senderName = null
        )
        assertNotNull(resolved)
        assertEquals("fgVoice", resolved!!.voiceId)
    }

    @Test
    fun autoSwitch_returnsNullWhenDisabledAndPackageNull() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_APP_PROFILES, true
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH, false
        )
        AccessibilityBridge.updateForeground(
            "com.foreground.app", "MainActivity"
        )

        val resolved = A11yVoiceResolver.resolve(
            context = context,
            settings = settings,
            packageName = null,
            senderName = null
        )
        assertNull(resolved)
    }

    @Test
    fun contactWithoutProfile_resolvesContactAlone() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_APP_PROFILES, false
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CONTACT_VOICES, true
        )
        val contactStore = ContactVoiceStore.create(context)
        contactStore.put("أحمد", VoiceProfile(voiceId = "ahmedVoice"))

        val resolved = A11yVoiceResolver.resolve(
            context = context,
            settings = settings,
            packageName = "com.whatsapp",
            senderName = "أحمد"
        )
        assertNotNull(resolved)
        assertEquals("ahmedVoice", resolved!!.voiceId)
        assertNull(resolved.engine)
        assertNull(resolved.speechRate)
    }
}
