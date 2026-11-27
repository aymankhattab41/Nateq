package com.aymankhattab.nateq.core.audio.accessibility

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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
    fun currentEnginePackage_fallsBackThroughLanguages() {
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

        // العربية لها أولوية على الإنجليزية في حسم المحرك الحالي.
        settings.setEnginePreferenceForLanguage(
            "ar", "com.vocalizer.tts", null, 1f, 1f, 1f
        )
        assertEquals(
            "com.vocalizer.tts",
            NextEngineSwitcher.currentEnginePackage(settings)
        )

        // فئة «الافتراضي» مُزالة من حل الفئات فلا تُستعمل كمصدر تراجع.
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            "com.reecedunn.espeak"
        )
        assertEquals(
            "com.vocalizer.tts",
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

    @Test
    fun resolveTargetEngine_unknownEngineWithNext_returnsFirstEngine() {
        val target = NextEngineSwitcher.resolveTargetEngine(
            engines = engines,
            currentPackage = "unknown.package",
            steps = 1
        )
        assertEquals("one", target?.packageName)
    }

    @Test
    fun resolveTargetEngine_unknownEngineWithPrevious_returnsLastEngine() {
        val target = NextEngineSwitcher.resolveTargetEngine(
            engines = engines,
            currentPackage = "unknown.package",
            steps = -1
        )
        assertEquals("three", target?.packageName)
    }

    @Test
    fun isSameEngine_recognizesLordTtsAndDefaultSynonyms() {
        assertTrue(
            NextEngineSwitcher.isSameEngine(
                "com.aymankhattab.nateq.default",
                "com.aymankhattab.nateq"
            )
        )
        assertTrue(
            NextEngineSwitcher.isSameEngine(
                "com.aymankhattab.nateq",
                "com.aymankhattab.nateq.default"
            )
        )
        assertFalse(
            NextEngineSwitcher.isSameEngine(
                "com.google.android.tts",
                "com.aymankhattab.nateq"
            )
        )
    }

    @Test
    fun selectedEngineLabel_returnsLordTtsForDefaultSynonym() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.TTS_DEFAULT_SYNTH,
            "com.aymankhattab.nateq.default"
        )
        val labelDefault = NextEngineSwitcher.selectedEngineLabel(context)
        assertEquals("Lord TTS", labelDefault)

        android.provider.Settings.Secure.putString(
            context.contentResolver,
            android.provider.Settings.Secure.TTS_DEFAULT_SYNTH,
            NextEngineSwitcher.LORD_TTS_PACKAGE
        )
        val labelDirect = NextEngineSwitcher.selectedEngineLabel(context)
        assertEquals("Lord TTS", labelDirect)
    }

    @Test
    fun setSystemEngine_missingPermission_opensGuideActivity() =
        kotlinx.coroutines.test.runTest {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val result = SystemTtsEngineController.setSystemEngine(
                app,
                "com.google.android.tts"
            )
            assertFalse(result.success)

            val startedIntent = shadowOf(app).nextStartedActivity
            assertEquals(
                "com.aymankhattab.nateq.settings." +
                    "WriteSecureSettingsGuideActivity",
                startedIntent?.component?.className
            )
        }
}
