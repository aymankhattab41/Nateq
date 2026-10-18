package com.aymankhattab.nateq.core.audio.accessibility

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SystemTtsEngineControllerTest {

    private lateinit var context: Context
    private lateinit var app: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context as Application
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
    }

    @Test
    fun hasPermission_reflectsSystemState() {
        assertFalse(SystemTtsEngineController.hasPermission(context))

        shadowOf(app).grantPermissions(
            Manifest.permission.WRITE_SECURE_SETTINGS
        )
        assertTrue(SystemTtsEngineController.hasPermission(context))
    }

    @Test
    fun setSystemEngine_failsWithoutPermission() {
        val result = SystemTtsEngineController.setSystemEngine(
            context,
            "com.google.android.tts"
        )
        assertFalse(result.success)
        assertNotNull(result.reason)
    }

    @Test
    fun setSystemEngine_succeedsAndPreservesRatePitchLocale() {
        shadowOf(app).grantPermissions(
            Manifest.permission.WRITE_SECURE_SETTINGS
        )

        val resolver = context.contentResolver
        Settings.Secure.putString(
            resolver,
            SystemTtsEngineController.KEY_TTS_DEFAULT_RATE,
            "120"
        )
        Settings.Secure.putString(
            resolver,
            SystemTtsEngineController.KEY_TTS_DEFAULT_PITCH,
            "90"
        )
        Settings.Secure.putString(
            resolver,
            SystemTtsEngineController.KEY_TTS_DEFAULT_LOCALE,
            "ar_EG"
        )

        val targetPkg = "com.google.android.tts"
        val result = SystemTtsEngineController.setSystemEngine(
            context,
            targetPkg
        )

        assertTrue(result.success)
        assertEquals(
            targetPkg,
            SystemTtsEngineController.currentSystemEngine(context)
        )
        assertEquals(
            "120",
            Settings.Secure.getString(
                resolver,
                SystemTtsEngineController.KEY_TTS_DEFAULT_RATE
            )
        )
        assertEquals(
            "90",
            Settings.Secure.getString(
                resolver,
                SystemTtsEngineController.KEY_TTS_DEFAULT_PITCH
            )
        )
        assertEquals(
            "ar_EG",
            Settings.Secure.getString(
                resolver,
                SystemTtsEngineController.KEY_TTS_DEFAULT_LOCALE
            )
        )
    }

    @Test
    fun getInstalledEngines_alwaysIncludesLordTts() {
        val engines = SystemTtsEngineController.getInstalledEngines(context)
        assertTrue(engines.any { it.packageName == context.packageName })
    }

    @Test
    fun switchEngine_preservesAllLordInternalSettingsExactly() {
        shadowOf(app).grantPermissions(
            Manifest.permission.WRITE_SECURE_SETTINGS
        )

        val settings = SettingsRepository.create(context)
        settings.setEnginePreferenceForLanguage(
            "ar", "com.vocalizer.tts", "Laila", 1.1f, 0.9f, 1.0f
        )
        settings.setEnginePreferenceForLanguage(
            "en", "com.google.android.tts", "en-us-x-sfg", 1.2f, 1.0f, 0.8f
        )
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS,
            "com.reecedunn.espeak"
        )
        settings.setAutoConvertEnabled(true)

        val exportedBefore = settings.exportSettings()
        val autoConvertBefore = settings.isAutoConvertEnabled()
        val notifEngineBefore = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )
        val arPrefBefore = settings.getEnginePreferenceForLanguage("ar")
        val enPrefBefore = settings.getEnginePreferenceForLanguage("en")

        NextEngineSwitcher.switchToNext(context)

        val exportedAfter = settings.exportSettings()
        val autoConvertAfter = settings.isAutoConvertEnabled()
        val notifEngineAfter = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
        )
        val arPrefAfter = settings.getEnginePreferenceForLanguage("ar")
        val enPrefAfter = settings.getEnginePreferenceForLanguage("en")

        assertEquals(exportedBefore, exportedAfter)
        assertEquals(autoConvertBefore, autoConvertAfter)
        assertEquals(notifEngineBefore, notifEngineAfter)
        assertEquals(arPrefBefore, arPrefAfter)
        assertEquals(enPrefBefore, enPrefAfter)
    }

    @Test
    fun switchToNext_withoutPermission_returnsNullAndChangesNothing() {
        val settings = SettingsRepository.create(context)
        val exportedBefore = settings.exportSettings()
        val initialEngine =
            SystemTtsEngineController.currentSystemEngine(context)

        val result = NextEngineSwitcher.switchToNext(context)
        assertNull(result)
        assertEquals(exportedBefore, settings.exportSettings())
        assertEquals(
            initialEngine,
            SystemTtsEngineController.currentSystemEngine(context)
        )
    }
}
