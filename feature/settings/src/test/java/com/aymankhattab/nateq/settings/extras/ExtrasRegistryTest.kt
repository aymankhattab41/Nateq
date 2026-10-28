package com.aymankhattab.nateq.settings.extras

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtrasRegistryTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepo = SettingsRepository(context)
        settingsRepo.resetAllToDefault()
    }

    @Test
    fun testAllKeysAreUnique() {
        val keys = ExtrasRegistry.all.map { it.key }
        val uniqueKeys = keys.toSet()
        assertEquals(
            "All extra feature keys must be unique",
            keys.size,
            uniqueKeys.size
        )
        assertEquals("Total extra features must be 29", 29, keys.size)
    }

    @Test
    fun testAllEntriesHaveValidTitlesAndDescriptionsInBothLocales() {
        val locales = listOf(Locale("ar"), Locale("en"))
        for (locale in locales) {
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localizedContext = context.createConfigurationContext(config)

            for (feature in ExtrasRegistry.all) {
                val title = localizedContext.getString(feature.titleRes)
                val desc = localizedContext.getString(feature.descRes)

                assertNotNull(
                    "Feature ${feature.key} title should not be null",
                    title
                )
                assertTrue(
                    "Feature ${feature.key} title should not be empty",
                    title.isNotBlank()
                )
                assertNotNull(
                    "Feature ${feature.key} desc should not be null",
                    desc
                )
                assertTrue(
                    "Feature ${feature.key} desc should not be empty",
                    desc.isNotBlank()
                )
            }
        }
    }

    @Test
    fun testDefaultStateIsDisabled() {
        for (feature in ExtrasRegistry.all) {
            assertFalse(
                "Feature ${feature.key} should be disabled by default",
                settingsRepo.isExtraEnabled(feature.key)
            )
        }
    }

    @Test
    fun testSetExtraEnabledAndExport() {
        val testFeature = ExtrasRegistry.all.first()
        settingsRepo.setExtraEnabled(testFeature.key, true)
        assertTrue(settingsRepo.isExtraEnabled(testFeature.key))

        val exported = settingsRepo.exportSettings()
        assertTrue(
            "Exported settings should contain enabled extra",
            exported.containsKey(testFeature.key)
        )
        assertEquals(true, exported[testFeature.key])
    }

    @Test
    fun testResetAllExtras() {
        val allKeys = ExtrasRegistry.all.map { it.key }
        settingsRepo.enableAllExtras(allKeys)
        for (key in allKeys) {
            assertTrue(settingsRepo.isExtraEnabled(key))
        }

        settingsRepo.resetAllExtras(allKeys)
        for (key in allKeys) {
            assertFalse(settingsRepo.isExtraEnabled(key))
        }
    }

    @Test
    fun testResetAllToDefaultClearsExtras() {
        val testKey = "extra_screen_rotor"
        settingsRepo.setExtraEnabled(testKey, true)
        assertTrue(settingsRepo.isExtraEnabled(testKey))

        settingsRepo.resetAllToDefault()
        assertFalse(settingsRepo.isExtraEnabled(testKey))
    }

    @Test
    fun testImportSettingsSanitizesExtrasToFalse() {
        val map = mapOf(
            "extra_screen_rotor" to true,
            "extra_screen_search" to true,
            "extra_password_shield" to true
        )
        settingsRepo.importSettings(map)

        assertFalse(settingsRepo.isExtraEnabled("extra_screen_rotor"))
        assertFalse(settingsRepo.isExtraEnabled("extra_screen_search"))
        assertFalse(settingsRepo.isExtraEnabled("extra_password_shield"))
    }
}
