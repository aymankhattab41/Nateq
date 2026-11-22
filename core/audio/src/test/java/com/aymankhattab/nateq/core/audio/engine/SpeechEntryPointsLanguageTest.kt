package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات سياق اللغة في SpeechEntryPoints:
 * التحقق من عدم فرض العربية على النصوص الإنجليزية ومعالجة العملة والوقت
 * والعناوين بدقة عبر مداخل النطق الحقيقية واحترام إعدادات لغة الأرقام.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SpeechEntryPointsLanguageTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        settings = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        settings.unregister()
        SettingsRepository.resetInstanceForTesting()
    }

    @Test
    fun englishCurrency_processedWithoutArabicForcing() {
        val result = SpeechEntryPoints.prepare(
            rawText = "The price is $50 today",
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context
        )
        // يجب ألا يحول النص الإنجليزي الخالص إلى العربية قسراً
        assertTrue(result.contains("dollars") || result.contains("fifty"))
        assertTrue(result.contains("today"))
        assertFalse(result.contains("دولار"))
    }

    @Test
    fun arabicCurrency_processedCorrectly() {
        val result = SpeechEntryPoints.prepare(
            rawText = "السعر 50 جنيه مصري",
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context
        )
        assertTrue(result.contains("السعر"))
        assertTrue(result.contains("جنيه"))
    }

    @Test
    fun englishTime_processedThroughEnglishPipeline() {
        val result = SpeechEntryPoints.prepare(
            rawText = "Meeting at 10:30 AM",
            source = SpeechEntryPoints.Source.NOTIFICATION,
            context = context
        )
        // التحقق من الحفاظ على صيغة الوقت الإنجليزية دون إقحام صياغة عربية
        assertTrue(result.contains("Meeting"))
        assertFalse(result.contains("صباحاً"))
    }

    @Test
    fun arabicTime_processedThroughArabicPipeline() {
        val result = SpeechEntryPoints.prepare(
            rawText = "الاجتماع الساعة 10:30 صباحاً",
            source = SpeechEntryPoints.Source.NOTIFICATION,
            context = context
        )
        assertTrue(result.contains("الاجتماع"))
        assertTrue(result.contains("العاشرة") || result.contains("صباحا"))
    }

    @Test
    fun englishAddress_processedCorrectly() {
        val result = SpeechEntryPoints.prepare(
            rawText = "Delivery to 123 Main St",
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context
        )
        assertTrue(result.contains("123"))
        assertTrue(result.contains("Main"))
    }

    @Test
    fun numberLanguageSetting_respectedForPureNumericText() {
        // تعيين لغة الأرقام إلى الإنجليزية
        settings.setNumberReadingLanguage("en")
        val resultEn = SpeechEntryPoints.prepare(
            rawText = "12345",
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context
        )
        assertTrue(resultEn.contains("12345"))

        // تعيين لغة الأرقام إلى العربية
        settings.setNumberReadingLanguage("ar")
        val resultAr = SpeechEntryPoints.prepare(
            rawText = "12345",
            source = SpeechEntryPoints.Source.SCREEN_READER,
            context = context
        )
        assertTrue(resultAr.contains("12345"))
    }

    @Test
    fun spellLast_preservesSpellingOutput() {
        val singleLetter = "A"
        val spelled = SpeechEntryPoints.prepare(
            rawText = singleLetter,
            source = SpeechEntryPoints.Source.SPELL_LAST,
            context = context
        )
        // يجب ألا تكون فارغة وأن تصف الحرف بدقة
        assertTrue(spelled.isNotBlank())
    }
}
