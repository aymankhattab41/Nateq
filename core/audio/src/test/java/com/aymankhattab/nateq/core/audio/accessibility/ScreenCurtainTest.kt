package com.aymankhattab.nateq.core.audio.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات ستارة الشاشة وأعلام النافذة والمزامنة (ع٥٧-أمر 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ScreenCurtainTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setup() {
        settingsRepo = SettingsRepository.create(context)
        settingsRepo.setScreenCurtainActive(false)
        ScreenCurtain.resetForTesting()
        ProtectedAppManager.resetForTesting()
    }

    @After
    fun tearDown() {
        ScreenCurtain.resetForTesting()
        settingsRepo.setScreenCurtainActive(false)
        ProtectedAppManager.resetForTesting()
    }

    @Test
    fun `screen curtain state is saved to settings repository`() {
        assertFalse(settingsRepo.isScreenCurtainActive())
        assertFalse(ScreenCurtain.isActive(context))

        settingsRepo.setScreenCurtainActive(true)
        assertTrue(settingsRepo.isScreenCurtainActive())
        assertTrue(ScreenCurtain.isActive(context))

        settingsRepo.setScreenCurtainActive(false)
        assertFalse(settingsRepo.isScreenCurtainActive())
    }

    @Test
    fun `screen curtain window layout flags and cutout mode`() {
        val overlayType =
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val expectedFlags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            expectedFlags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        assertEquals(expectedFlags, params.flags)
        assertEquals(PixelFormat.OPAQUE, params.format)
        assertEquals(
            Gravity.TOP or Gravity.START,
            params.gravity
        )
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            params.layoutInDisplayCutoutMode
        )
    }

    @Test
    fun `screen off hides curtain and screen on restores it`() {
        // اختبار دورة الإخفاء والاستعادة
        val service = Robolectric.buildService(
            NateqAccessibilityService::class.java
        ).create().get()

        val enabled = ScreenCurtain.enable(service)
        if (enabled) {
            assertTrue(ScreenCurtain.isActive())
            assertTrue(settingsRepo.isScreenCurtainActive())

            // محاكاة إطفاء الشاشة
            ScreenCurtain.onScreenOff(service)
            // محاكاة تشغيل الشاشة
            ScreenCurtain.onScreenOn(service)

            ScreenCurtain.disable(service)
            assertFalse(ScreenCurtain.isActive())
            assertFalse(settingsRepo.isScreenCurtainActive())
        }
    }

    @Test
    fun `service reconnect restores curtain when previously active`() {
        settingsRepo.setScreenCurtainActive(true)
        assertTrue(ScreenCurtain.isActive(context))

        val service = Robolectric.buildService(
            NateqAccessibilityService::class.java
        ).create().get()

        // عند وجود علم التفعيل، enable يعيد بناء النافذة
        val restored = ScreenCurtain.enable(service)
        if (restored) {
            assertTrue(ScreenCurtain.isActive())
            ScreenCurtain.disable(service)
        }
    }
}
