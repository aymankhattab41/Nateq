package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.LastSpokenTextsTracker
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات انحدار للأمر 23:
 * ربط تنفيذ READ_SCREEN وREAD_CLIPBOARD بمفتاحي FEATURE_READ_SCREEN_NOW
 * وFEATURE_CLIPBOARD_READING في نقطة مركزية تغطي اختصارات المفاتيح
 * والقائمة السريعة وأي مدخل آخر.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadScreenAndClipboardFeatureFlagsTest {

    private lateinit var context: Context
    private lateinit var service: AccessibilityService
    private lateinit var settings: SettingsRepository
    private var stateStore: A11yStateStore? = null
    private lateinit var router: KeyShortcutRouter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LastSpokenTextsTracker.clear()
        stateStore = A11yStateStore.getInstance(context)
        stateStore?.setConnected(true)
        stateStore?.setPaused(false)

        settings = SettingsRepository.create(context)
        // تفعيل اختصارات المفاتيح العامة
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_KEY_SHORTCUTS,
            true
        )
        // في الوضع الافتراضي، الميزات معطلة
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_READ_SCREEN_NOW,
            false
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CLIPBOARD_READING,
            false
        )

        service = Robolectric.buildService(
            NateqAccessibilityService::class.java
        ).create().get()

        router = KeyShortcutRouter(service, settings, stateStore)
    }

    @After
    fun tearDown() {
        stateStore?.setPaused(false)
        stateStore?.setConnected(false)
        router.reset()
        LastSpokenTextsTracker.clear()
    }

    @Test
    fun readScreen_whenFeatureDisabled_announcesDisabledMessage() {
        LastSpokenTextsTracker.clear()

        router.executeAction(ShortcutAction.READ_SCREEN)

        val recent = LastSpokenTextsTracker.getRecent()
        assertFalse(
            "يجب تسجيل الإعلان في السجل عند التعطيل",
            recent.isEmpty()
        )
        assertEquals("ميزة قراءة الشاشة غير مفعّلة", recent.first())
    }

    @Test
    fun readClipboard_whenFeatureDisabled_announcesDisabledMessage() {
        LastSpokenTextsTracker.clear()

        router.executeAction(ShortcutAction.READ_CLIPBOARD)

        val recent = LastSpokenTextsTracker.getRecent()
        assertFalse(
            "يجب تسجيل الإعلان في السجل عند التعطيل",
            recent.isEmpty()
        )
        assertEquals("ميزة قراءة الحافظة غير مفعّلة", recent.first())
    }

    @Test
    fun readScreen_whenFeatureEnabled_doesNotSayDisabledMessage() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_READ_SCREEN_NOW,
            true
        )
        LastSpokenTextsTracker.clear()

        router.executeAction(ShortcutAction.READ_SCREEN)

        val recent = LastSpokenTextsTracker.getRecent()
        val announced = recent.firstOrNull()
        assertFalse(
            "لا يجب أن يُعلن أن ميزة قراءة الشاشة غير مفعّلة: $announced",
            announced?.contains("ميزة قراءة الشاشة غير مفعّلة") == true
        )
    }

    @Test
    fun readClipboard_whenFeatureEnabled_doesNotSayDisabledMessage() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CLIPBOARD_READING,
            true
        )
        LastSpokenTextsTracker.clear()

        router.executeAction(ShortcutAction.READ_CLIPBOARD)

        val recent = LastSpokenTextsTracker.getRecent()
        val announced = recent.firstOrNull()
        assertFalse(
            "لا يجب أن يُعلن أن ميزة الحافظة غير مفعّلة: $announced",
            announced?.contains("ميزة قراءة الحافظة غير مفعّلة") == true
        )
        assertEquals("الحافظة فارغة", announced)
    }

    @Test
    fun features_respectGeneralPausedState() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_READ_SCREEN_NOW,
            true
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CLIPBOARD_READING,
            true
        )
        stateStore?.setPaused(true)

        assertFalse(
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_READ_SCREEN_NOW,
                context = context,
                settings = settings,
                stateStore = stateStore
            )
        )
        assertFalse(
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_CLIPBOARD_READING,
                context = context,
                settings = settings,
                stateStore = stateStore
            )
        )
    }

    @Test
    fun features_respectAppPolicy() {
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_READ_SCREEN_NOW,
            true
        )
        settings.setAccessibilityFeatureEnabled(
            FeatureFlags.FEATURE_CLIPBOARD_READING,
            true
        )

        assertTrue(
            FeatureFlags.isActive(
                FeatureFlags.FEATURE_READ_SCREEN_NOW,
                packageName = "com.whatsapp",
                context = context,
                settings = settings,
                stateStore = stateStore
            )
        )
    }
}
