package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات ضمان توريث واحتفاظ سرعة النطق والنبرة ومستوى الصوت
 * للفئات غير المخصصة صراحة من الفئة الافتراضية [VOICE_CATEGORY_DEFAULT].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CategorySpeechRateRetentionTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        repo = SettingsRepository(context)
    }

    @Test
    fun unconfiguredCategories_inheritDefaultSpeechRate() {
        // الافتراضي في البداية 1.0
        val timeCat = SettingsRepository.VOICE_CATEGORY_TIME
        val numCat = SettingsRepository.VOICE_CATEGORY_NUMBERS
        val emojiCat = SettingsRepository.VOICE_CATEGORY_EMOJI
        val batteryCat = SettingsRepository.VOICE_CATEGORY_BATTERY

        assertEquals(1.0f, repo.getSpeechRateForCategory(timeCat), 0.001f)
        assertEquals(1.0f, repo.getSpeechRateForCategory(numCat), 0.001f)

        // يغير المستخدم السرعة العامة الافتراضية إلى 1.8x
        repo.setDefaultSpeechRate(1.8f)

        // الفئات غير المخصصة ترث 1.8x ولا تسقط إلى 1.0x إطلاقاً
        assertEquals(1.8f, repo.getSpeechRateForCategory(timeCat), 0.001f)
        assertEquals(1.8f, repo.getSpeechRateForCategory(numCat), 0.001f)
        assertEquals(1.8f, repo.getSpeechRateForCategory(emojiCat), 0.001f)
        assertEquals(1.8f, repo.getSpeechRateForCategory(batteryCat), 0.001f)
        assertEquals(1.8f, repo.getDefaultSpeechRate(), 0.001f)
    }

    @Test
    fun explicitCategoryRate_overridesDefaultRate() {
        repo.setDefaultSpeechRate(1.5f)
        val timeCat = SettingsRepository.VOICE_CATEGORY_TIME
        val numCat = SettingsRepository.VOICE_CATEGORY_NUMBERS

        // تخصيص صريح لفئة الأرقام فقط
        repo.setSpeechRateForCategory(numCat, 2.0f)

        assertTrue(repo.hasExplicitSpeechRateForCategory(numCat))
        assertFalse(repo.hasExplicitSpeechRateForCategory(timeCat))

        // فئة الأرقام تحتفظ بـ 2.0x بينما فئة الوقت ترث 1.5x
        assertEquals(2.0f, repo.getSpeechRateForCategory(numCat), 0.001f)
        assertEquals(1.5f, repo.getSpeechRateForCategory(timeCat), 0.001f)
    }

    @Test
    fun pitchAndVolume_inheritDefaultValues() {
        repo.setDefaultPitch(1.3f)
        repo.setDefaultVolume(0.7f)
        val timeCat = SettingsRepository.VOICE_CATEGORY_TIME

        assertEquals(1.3f, repo.getPitchForCategory(timeCat), 0.001f)
        assertEquals(0.7f, repo.getVolumeForCategory(timeCat), 0.001f)
    }
}
