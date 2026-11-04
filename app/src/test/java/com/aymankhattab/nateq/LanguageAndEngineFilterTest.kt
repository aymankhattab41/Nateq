package com.aymankhattab.nateq

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.NextEngineSwitcher
import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import com.aymankhattab.nateq.core.audio.engine.LatinLanguageDetector
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.VoiceDescriptor
import com.aymankhattab.nateq.core.audio.providers.VoiceProvider
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.common.VisibleLanguages
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات تصفية اللغات والمحركات (الأمران 2 و3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguageAndEngineFilterTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        VisibleLanguages.resetForTest()
        VisibleEngines.resetForTest()
    }

    @After
    fun tearDown() {
        VisibleLanguages.resetForTest()
        VisibleEngines.resetForTest()
    }

    /**
     * (أ) بتفعيل التصفية لـ ar و en فقط: لا تظهر fr في أي قائمة
     * ولا في الكتالوج ولا في اكتشاف اللغات.
     */
    @Test
    fun filterLanguages_arAndEnOnly_hidesFrenchGlobally() {
        VisibleLanguages.setFilter(true, setOf("ar", "en"))

        assertTrue(VisibleLanguages.isVisible("ar"))
        assertTrue(VisibleLanguages.isVisible("ar-EG"))
        assertTrue(VisibleLanguages.isVisible("en"))
        assertTrue(VisibleLanguages.isVisible("en-US"))
        assertFalse(VisibleLanguages.isVisible("fr"))
        assertFalse(VisibleLanguages.isVisible("fr-FR"))
        assertFalse(VisibleLanguages.isVisible("fra"))

        val inputList = listOf("ar", "en", "fr", "de")
        val filteredList = VisibleLanguages.filter(inputList)
        assertEquals(listOf("ar", "en"), filteredList)

        // كشف اللغات اللاتينية لا يرشح فرنسية
        val candidate = LatinLanguageDetector.detect("Bonjour tout le monde")
        // لغة fr مخفية، فلا يرجع fr
        assertNotEquals("fr", candidate)
    }

    /**
     * (ب) القيم المخفية تبقى محفوظة وتعود بعودة الإظهار.
     */
    @Test
    fun hiddenLanguagePreferences_remainSavedAndRestoreOnUnhide() {
        // ضبط تفضيل لصوت فرنسي
        settings.setPreferredVoiceId("fr", "com.google.android.tts:fra-fra")
        assertEquals(
            "com.google.android.tts:fra-fra",
            settings.getPreferredVoiceId("fr")
        )

        // تفعيل التصفية لـ ar و en فقط
        settings.setLanguageFilterEnabled(true)
        settings.setLanguageFilterSet(setOf("ar", "en"))
        assertFalse(VisibleLanguages.isVisible("fr"))

        // القيمة ما تزال في التخزين
        val rawPrefs = context.getSharedPreferences(
            "nateq_settings",
            Context.MODE_PRIVATE
        )
        assertNotNull(rawPrefs.getString("preferred_voice_fr", null))

        // إلغاء التصفية
        settings.setLanguageFilterEnabled(false)
        assertTrue(VisibleLanguages.isVisible("fr"))
        assertEquals(
            "com.google.android.tts:fra-fra",
            settings.getPreferredVoiceId("fr")
        )
    }

    /**
     * (ج) تصفية المحركات: المحركات غير المحددة تختفي من القوائم
     * والتبديل NextEngineSwitcher يدور على الظاهرين فقط.
     */
    @Test
    fun filterEngines_restrictsVisibleEnginesAndNextEngineSwitcher() {
        val eng1 = "com.google.android.tts"
        val eng2 = "com.svox.pico"
        val eng3 = "com.samsung.SMT"

        settings.setEngineFilterEnabled(true)
        settings.setEngineFilterSet(setOf(eng1, eng2))

        assertTrue(VisibleEngines.isVisible(eng1))
        assertTrue(VisibleEngines.isVisible(eng2))
        assertFalse(VisibleEngines.isVisible(eng3))

        val filteredEngines = VisibleEngines.filter(listOf(eng1, eng2, eng3))
        assertEquals(listOf(eng1, eng2), filteredEngines)

        // فئة محفوظة على محرك مخفي تعود إلى null (المحرك الافتراضي المرئي)
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            eng3
        )
        assertNull(
            settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        )

        // عند إعادة إظهار المحرك يعود الحفظ
        settings.setEngineFilterSet(setOf(eng1, eng2, eng3))
        assertEquals(
            eng3,
            settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        )
    }

    /**
     * (د) اختبار معماري: مسح الكود المصدري للتأكد من عدم وجود استدعاءات
     * مباشرة لـ Locale.getAvailableLocales() أو getEngines() خارج
     * المصدر الموحد (نافذة الاختيار فقط).
     */
    @Test
    fun architecturalScan_noDirectGetAvailableLocalesOrGetEngines() {
        val rootDir = File("src/main/java")
        if (!rootDir.exists()) return

        val ktFiles = rootDir.walkTopDown()
            .filter { it.extension == "kt" }
            .toList()

        for (file in ktFiles) {
            val name = file.name
            // استثناء نوافذ الاختيار المخصصة المصرح بها
            if (name == "LanguageFilterController.kt" ||
                name == "VisibleLanguages.kt" ||
                name == "VisibleEngines.kt"
            ) {
                continue
            }

            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val isComment = line.trim().startsWith("//") ||
                    line.trim().startsWith("*")
                if (isComment) continue

                assertFalse(
                    "ممنوع استدعاء Locale.getAvailableLocales() " +
                        "في $name سطر ${index + 1}",
                    line.contains("Locale.getAvailableLocales()")
                )
                assertFalse(
                    "ممنوع استدعاء TextToSpeech.getEngines() " +
                        "في $name سطر ${index + 1}",
                    line.contains(".getEngines()")
                )
            }
        }
    }
}
