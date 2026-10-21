package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات إعدادات لوحة المفاتيح الميسّرة في SettingsRepository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
    }

    @Test
    fun keyboardMode_defaultsToBlindMode() {
        val mode = settings.getKeyboardMode()
        assertEquals(SettingsRepository.KEYBOARD_MODE_BLIND, mode)
    }

    @Test
    fun keyboardMode_canBeUpdatedToLowVision() {
        settings.setKeyboardMode(SettingsRepository.KEYBOARD_MODE_LOW_VISION)
        assertEquals(
            SettingsRepository.KEYBOARD_MODE_LOW_VISION,
            settings.getKeyboardMode()
        )
    }

    @Test
    fun keyboardActivation_defaultsToLift() {
        val activation = settings.getKeyboardActivation()
        assertEquals(SettingsRepository.KEYBOARD_ACTIVATION_LIFT, activation)
    }

    @Test
    fun keyboardActivation_canBeUpdatedToDoubleTap() {
        settings.setKeyboardActivation(
            SettingsRepository.KEYBOARD_ACTIVATION_DOUBLE_TAP
        )
        assertEquals(
            SettingsRepository.KEYBOARD_ACTIVATION_DOUBLE_TAP,
            settings.getKeyboardActivation()
        )
    }

    @Test
    fun keyboardAnnounceAfterLift_defaultsToFalse() {
        // اختياري وغير مفروض افتراضياً
        assertFalse(settings.isKeyboardAnnounceAfterLift())
        settings.setKeyboardAnnounceAfterLift(true)
        assertTrue(settings.isKeyboardAnnounceAfterLift())
    }

    @Test
    fun keyboardHaptic_defaultsToFalse_andCanBeToggled() {
        assertFalse(settings.isKeyboardHapticEnabled())
        settings.setKeyboardHapticEnabled(true)
        assertTrue(settings.isKeyboardHapticEnabled())
        settings.setKeyboardHapticEnabled(false)
        assertFalse(settings.isKeyboardHapticEnabled())
    }

    @Test
    fun keyboardSoundAndPhonetic_defaultToTrue() {
        assertTrue(settings.isKeyboardSoundEnabled())
        assertTrue(settings.isKeyboardPhoneticEnabled())

        settings.setKeyboardSoundEnabled(false)
        assertFalse(settings.isKeyboardSoundEnabled())

        settings.setKeyboardPhoneticEnabled(false)
        assertFalse(settings.isKeyboardPhoneticEnabled())
    }
}
