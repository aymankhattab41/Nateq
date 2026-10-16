package com.aymankhattab.nateq.core.audio.phone

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
 * اختبارات وحدة لمدير تسجيل المكالمات وتضخيم الصوت البرمجي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CallRecordManagerTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepo = SettingsRepository(context)
    }

    @Test
    fun boostPcmSamples_multiplierOne_leavesSamplesUnchanged() {
        val buffer = shortArrayOf(100, -200, 300, -400)
        val original = buffer.clone()

        CallRecordManager.boostPcmSamples(buffer, buffer.size, 1.0f)
        for (i in buffer.indices) {
            assertEquals(original[i], buffer[i])
        }
    }

    @Test
    fun boostPcmSamples_multiplierTwoPointFive_amplifiesCorrectly() {
        val buffer = shortArrayOf(100, -200, 300, -400)

        CallRecordManager.boostPcmSamples(buffer, buffer.size, 2.5f)
        assertEquals(250.toShort(), buffer[0])
        assertEquals((-500).toShort(), buffer[1])
        assertEquals(750.toShort(), buffer[2])
        assertEquals((-1000).toShort(), buffer[3])
    }

    @Test
    fun boostPcmSamples_preventsClippingBeyondShortBounds() {
        val buffer = shortArrayOf(20000, -25000)

        CallRecordManager.boostPcmSamples(buffer, buffer.size, 2.5f)
        assertEquals(Short.MAX_VALUE, buffer[0])
        assertEquals(Short.MIN_VALUE, buffer[1])
    }

    @Test
    fun shouldAutoRecord_whenDisabled_alwaysReturnsFalse() {
        settingsRepo.setPhoneRecordEnabled(false)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL
        )

        assertFalse(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = false
            )
        )
    }

    @Test
    fun shouldAutoRecord_whenManual_alwaysReturnsFalse() {
        settingsRepo.setPhoneRecordEnabled(true)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_MANUAL
        )

        assertFalse(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = false
            )
        )
    }

    @Test
    fun shouldAutoRecord_whenAutoAll_returnsTrueForKnownAndUnsaved() {
        settingsRepo.setPhoneRecordEnabled(true)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL
        )

        assertTrue(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = true
            )
        )
        assertTrue(
            CallRecordManager.shouldAutoRecord(
                context,
                "0987654321",
                isKnownContact = false
            )
        )
    }

    @Test
    fun shouldAutoRecord_whenAutoUnsaved_recordsOnlyUnsavedContacts() {
        settingsRepo.setPhoneRecordEnabled(true)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_AUTO_UNSAVED
        )

        assertFalse(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = true
            )
        )
        assertTrue(
            CallRecordManager.shouldAutoRecord(
                context,
                "0987654321",
                isKnownContact = false
            )
        )
    }
}
