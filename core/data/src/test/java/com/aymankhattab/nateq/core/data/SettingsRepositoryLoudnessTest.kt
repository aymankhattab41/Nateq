package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات سياسة جهارة الأحداث [CategoryLoudnessConfig] وحفظ المستويات
 * المعلّقة في [SettingsRepository] (ع٤٢-2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsRepositoryLoudnessTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = SettingsRepository.create(context)
        repo.resetAllToDefault()
    }

    @After
    fun tearDown() {
        repo.clearPendingBoost()
        repo.resetAllToDefault()
    }

    @Test
    fun defaults_callerTimeBattery_forceMaxAndBypassSilent() {
        listOf(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER,
            SettingsRepository.VOICE_CATEGORY_TIME,
            SettingsRepository.VOICE_CATEGORY_BATTERY
        ).forEach { cat ->
            val config = repo.getCategoryLoudnessConfig(cat)
            assertTrue("فئة $cat ترفع للأقصى افتراضياً", config.forceMax)
            assertTrue("فئة $cat تتجاوز الصامت افتراضياً", config.bypassSilent)
            assertEquals(85, config.headsetCapPercent)
            assertEquals(StreamMode.AUTO, config.streamMode)
        }
    }

    @Test
    fun defaults_smsAndNotifications_doNotForceMaxNorBypass() {
        listOf(
            SettingsRepository.ANNOUNCE_CATEGORY_SMS,
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        ).forEach { cat ->
            val config = repo.getCategoryLoudnessConfig(cat)
            assertFalse("فئة $cat لا ترفع للأقصى افتراضياً", config.forceMax)
            assertFalse(
                "فئة $cat لا تتجاوز الصامت افتراضياً",
                config.bypassSilent
            )
            assertEquals(85, config.headsetCapPercent)
            assertEquals(StreamMode.AUTO, config.streamMode)
        }
    }

    @Test
    fun customConfig_roundtrip_persistsCorrectly() {
        val cat = "custom_event"
        val custom = CategoryLoudnessConfig(
            streamMode = StreamMode.ALARM,
            forceMax = false,
            bypassSilent = true,
            headsetCapPercent = 70
        )
        repo.setCategoryLoudnessConfig(cat, custom)

        val retrieved = repo.getCategoryLoudnessConfig(cat)
        assertEquals(StreamMode.ALARM, retrieved.streamMode)
        assertFalse(retrieved.forceMax)
        assertTrue(retrieved.bypassSilent)
        assertEquals(70, retrieved.headsetCapPercent)
    }

    @Test
    fun pendingBoost_saveAndClear_persistsCorrectly() {
        assertEquals(-1, repo.getPendingBoostStream())
        assertEquals(-1, repo.getPendingBoostVolume())

        repo.savePendingBoost(stream = 3, originalVolume = 7)
        assertEquals(3, repo.getPendingBoostStream())
        assertEquals(7, repo.getPendingBoostVolume())

        repo.clearPendingBoost()
        assertEquals(-1, repo.getPendingBoostStream())
        assertEquals(-1, repo.getPendingBoostVolume())
    }

    @Test
    fun exportSettings_includesLoudnessKeys() {
        repo.setLoudnessForceMaxEnabled("time", false)
        repo.setLoudnessHeadsetCapPercent("time", 65)

        val exported = repo.exportSettings()
        assertEquals(false, exported["loudness_force_max_time"])
        assertEquals(65, exported["loudness_headset_cap_time"])
    }
}
