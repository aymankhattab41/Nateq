package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.AccessibilityBridge
import com.aymankhattab.nateq.core.audio.accessibility.FeatureFlags
import com.aymankhattab.nateq.core.data.AppProfile
import com.aymankhattab.nateq.core.data.AppProfileStore
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات تطبيق ملف التطبيق في خدمة TTS واستثناء إعلانات النظام (ع٤٢-11).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NateqTtsServiceAppProfileTest {

    private val context =
        ApplicationProvider.getApplicationContext<Context>()

    private fun createService(
        block: (SettingsRepository) -> Unit
    ): NateqTtsService {
        val repo = SettingsRepository.create(context)
        block(repo)
        val service = NateqTtsService::class.java
            .getDeclaredConstructor().newInstance()
        val attachMethod = ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        attachMethod.isAccessible = true
        attachMethod.invoke(service, context)

        val field = NateqTtsService::class.java
            .getDeclaredField("settings")
        field.isAccessible = true
        field.set(service, repo)
        return service
    }

    @Test
    fun resolveActiveAppVoice_appliesForScreenReader_ignoresAnnouncement() {
        val store = AppProfileStore.create(context)
        store.put(
            AppProfile(
                packageName = "com.reader.app",
                voiceId = "readerVoice",
                speechRate = 1.4f
            )
        )
        AccessibilityBridge.updateForeground("com.reader.app", "MainActivity")

        val service = createService { repo ->
            repo.setAccessibilityFeatureEnabled(
                FeatureFlags.FEATURE_APP_PROFILES, true
            )
            repo.setAccessibilityFeatureEnabled(
                FeatureFlags.FEATURE_AUTO_PROFILE_SWITCH, true
            )
        }

        // قراءة الشاشة العادية (effectCategory فارغة) -> يطبق ملف التطبيق
        val screenVoice = service.resolveActiveAppVoice("")
        assertNotNull(screenVoice)
        assertEquals("readerVoice", screenVoice!!.voiceId)
        assertEquals(1.4f, screenVoice.speechRate!!, 0.001f)

        // إعلانات النظام (المكالمة، المنبه، البطارية، الرسائل) -> محميّة تماماً
        val callVoice = service.resolveActiveAppVoice("call")
        assertNull("إعلانات النظام لا تتأثر بملف التطبيق", callVoice)

        val smsVoice = service.resolveActiveAppVoice("sms")
        assertNull("إعلان الرسائل لا يتأثر بملف التطبيق", smsVoice)

        val clockVoice = service.resolveActiveAppVoice("time_chime")
        assertNull("إعلان الوقت لا يتأثر بملف التطبيق", clockVoice)
    }

    @Test
    fun resolveActiveAppVoice_disabledAppProfiles_returnsNull() {
        val store = AppProfileStore.create(context)
        store.put(
            AppProfile(
                packageName = "com.reader.app",
                voiceId = "readerVoice"
            )
        )
        AccessibilityBridge.updateForeground("com.reader.app", "MainActivity")

        val service = createService { repo ->
            repo.setAccessibilityFeatureEnabled(
                FeatureFlags.FEATURE_APP_PROFILES, false
            )
        }

        val screenVoice = service.resolveActiveAppVoice("")
        assertNull(screenVoice)
    }
}
