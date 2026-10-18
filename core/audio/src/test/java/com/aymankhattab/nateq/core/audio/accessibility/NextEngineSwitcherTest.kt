package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NextEngineSwitcherTest {

    private val engines = listOf(
        EnginePicker.InstalledEngine("one", "One"),
        EnginePicker.InstalledEngine("two", "Two"),
        EnginePicker.InstalledEngine("three", "Three")
    )

    @Test
    fun nextEngine_keepsTheSystemDiscoveryOrder() {
        val next = NextEngineSwitcher.nextEngine(engines, "one")

        assertEquals("two", next?.packageName)
    }

    @Test
    fun nextEngine_wrapsAfterTheLastSystemEngine() {
        val next = NextEngineSwitcher.nextEngine(engines, "three")

        assertEquals("one", next?.packageName)
    }

    @Test
    fun nextEngine_missingSavedEngine_startsWithFirstSystemEngine() {
        val next = NextEngineSwitcher.nextEngine(engines, "removed")

        assertEquals("one", next?.packageName)
    }

    @Test
    fun nextEngine_emptySystemList_doesNotInventAnEngine() {
        assertNull(NextEngineSwitcher.nextEngine(emptyList(), null))
    }

    @Test
    fun previousEngine_keepsTheReverseSystemOrder() {
        val prev = NextEngineSwitcher.previousEngine(engines, "three")

        assertEquals("two", prev?.packageName)
    }

    @Test
    fun previousEngine_wrapsBeforeTheFirstSystemEngine() {
        val prev = NextEngineSwitcher.previousEngine(engines, "one")

        assertEquals("three", prev?.packageName)
    }

    @Test
    fun previousEngine_missingSavedEngine_startsWithLastSystemEngine() {
        val prev = NextEngineSwitcher.previousEngine(engines, "removed")

        assertEquals("three", prev?.packageName)
    }

    @Test
    fun previousEngine_emptySystemList_doesNotInventAnEngine() {
        assertNull(NextEngineSwitcher.previousEngine(emptyList(), null))
    }

    @Test
    fun currentEnginePackage_fallsBackThroughCategoryAndLanguages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository.create(context)

        assertNull(NextEngineSwitcher.currentEnginePackage(settings))

        settings.setEnginePreferenceForLanguage(
            "en", "com.google.android.tts", null, 1f, 1f, 1f
        )
        assertEquals(
            "com.google.android.tts",
            NextEngineSwitcher.currentEnginePackage(settings)
        )

        settings.setEnginePreferenceForLanguage(
            "ar", "com.vocalizer.tts", null, 1f, 1f, 1f
        )
        assertEquals(
            "com.vocalizer.tts",
            NextEngineSwitcher.currentEnginePackage(settings)
        )

        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            "com.reecedunn.espeak"
        )
        assertEquals(
            "com.reecedunn.espeak",
            NextEngineSwitcher.currentEnginePackage(settings)
        )
    }

    @Test
    fun availableEngines_startsWithLordTts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engines = NextEngineSwitcher.availableEngines(context)
        assertEquals(
            context.packageName,
            engines.first().packageName
        )
        assertEquals("Lord TTS", engines.first().label)
    }

    @Test
    fun nextEngine_wrapsThroughLordTts() {
        val list = listOf(
            EnginePicker.InstalledEngine(
                NextEngineSwitcher.LORD_TTS_PACKAGE, "Lord TTS"
            ),
            EnginePicker.InstalledEngine("one", "One")
        )
        val fromLord = NextEngineSwitcher.nextEngine(list, null)
        assertEquals("one", fromLord?.packageName)

        val fromOne = NextEngineSwitcher.nextEngine(list, "one")
        assertEquals(
            NextEngineSwitcher.LORD_TTS_PACKAGE,
            fromOne?.packageName
        )
    }
}
