package com.aymankhattab.nateq.settings.phone

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
 * اختبارات سلامة وتعقّل إعدادات الهاتف والتسجيل في SettingsRepository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PhoneSettingsSanitizationTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = SettingsRepository(context)
    }

    @Test
    fun phoneSettings_defaultsAreSafeAndAccessible() {
        assertEquals(
            SettingsRepository.PHONE_MODE_BLIND,
            repo.getPhoneMode()
        )
        assertEquals(
            SettingsRepository.PHONE_ACTIVATION_LIFT,
            repo.getPhoneActivation()
        )
        assertFalse(repo.isPhoneAnnounceAfterLiftEnabled())
        assertFalse(repo.isPhoneHapticEnabled())
        assertTrue(repo.isPhoneSoundEnabled())
        assertFalse(repo.isPhoneRecordEnabled())
        assertEquals(
            SettingsRepository.PHONE_RECORD_MODE_MANUAL,
            repo.getPhoneRecordMode()
        )
        assertTrue(repo.isPhoneShowRecordButton())
        assertTrue(repo.isPhoneRecordBoostVolumeEnabled())
        assertFalse(repo.isPhoneAutoSpeakerProximityEnabled())
        assertTrue(repo.isPhoneHardwareKeysAnswerEnabled())
        assertEquals(
            SettingsRepository.INCOMING_CALL_UI_AUTO,
            repo.getIncomingCallUiMode()
        )
    }

    @Test
    fun phoneSettings_importSanitization_correctsInvalidValues() {
        val badMap = mapOf(
            SettingsRepository.KEY_PHONE_MODE to "invalid_mode",
            SettingsRepository.KEY_PHONE_ACTIVATION to "invalid_activation",
            SettingsRepository.KEY_PHONE_RECORD_MODE to "invalid_record_mode",
            SettingsRepository.KEY_INCOMING_CALL_UI_MODE to "invalid_incoming"
        )

        repo.importSettings(badMap)

        assertEquals(
            SettingsRepository.PHONE_MODE_BLIND,
            repo.getPhoneMode()
        )
        assertEquals(
            SettingsRepository.PHONE_ACTIVATION_LIFT,
            repo.getPhoneActivation()
        )
        assertEquals(
            SettingsRepository.PHONE_RECORD_MODE_MANUAL,
            repo.getPhoneRecordMode()
        )
        assertEquals(
            SettingsRepository.INCOMING_CALL_UI_AUTO,
            repo.getIncomingCallUiMode()
        )
    }

    @Test
    fun phoneSettings_exportSettings_includesAllPhoneKeys() {
        repo.setPhoneMode(SettingsRepository.PHONE_MODE_LOW_VISION)
        repo.setPhoneActivation(SettingsRepository.PHONE_ACTIVATION_DOUBLE_TAP)
        repo.setPhoneRecordEnabled(true)
        repo.setPhoneRecordMode(SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL)
        repo.setPhoneAutoSpeakerProximityEnabled(true)

        val exported = repo.exportSettings()

        assertEquals(
            SettingsRepository.PHONE_MODE_LOW_VISION,
            exported[SettingsRepository.KEY_PHONE_MODE]
        )
        assertEquals(
            SettingsRepository.PHONE_ACTIVATION_DOUBLE_TAP,
            exported[SettingsRepository.KEY_PHONE_ACTIVATION]
        )
        assertEquals(
            true,
            exported[SettingsRepository.KEY_PHONE_RECORD_ENABLED]
        )
        assertEquals(
            SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL,
            exported[SettingsRepository.KEY_PHONE_RECORD_MODE]
        )
        assertEquals(
            true,
            exported[SettingsRepository.KEY_PHONE_AUTO_SPEAKER_PROXIMITY]
        )
    }
}
