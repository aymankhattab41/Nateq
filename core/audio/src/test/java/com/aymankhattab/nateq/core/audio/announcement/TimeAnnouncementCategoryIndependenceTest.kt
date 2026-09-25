package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** استقلال لغة الفئات (بند التصميم): مفتاح لغة نطق الأرقام العام
 *  (announcement_speech_language) ملك فئة الأرقام حصراً — فئة الساعة
 *  (وأي فئة غير الأرقام) تُحسم من لغتها المحفوظة ثم صوتها ثم لغة التطبيق
 *  بلا أي تأثير من مفتاح الأرقام العام. الاختبار الحاسم: لغة أرقام عربية
 *  مع فئة ساعة إنجليزية صريحة ⇒ ساعة إنجليزية؛ وبالعكس. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TimeAnnouncementCategoryIndependenceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun managerFor(settings: SettingsRepository):
            TimeAnnouncementManager {
        val catalog = VoiceCatalog(emptyList())
        return TimeAnnouncementManager(
            context,
            settings,
            catalog,
            SynthesisRequestHandler(catalog, settings)
        )
    }

    private fun clearTimeSettings(settings: SettingsRepository) {
        settings.setLanguageForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME, null
        )
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME, ""
        )
    }

    @Test
    fun `time announcement speaks english despite arabic number language`() {
        // لغة نطق الأرقام العام = عربي.
        val settings = SettingsRepository.create(context)
        settings.setAnnouncementSpeechLanguage(
            com.aymankhattab.nateq.util.LanguageCode.AR.tag
        )
        // فئة الساعة صراحةً = إنجليزي (لغةُ فئة + صوتٌ إنجليزي).
        clearTimeSettings(settings)
        settings.setLanguageForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME, "en"
        )
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME, "nateq-en-local"
        )
        val manager = managerFor(settings)
        assertEquals(
            "الساعة تُنطق إنجليزية رغم أن لغة الأرقام العام عربية",
            AnnouncementLanguageResolver.ENGLISH,
            manager.resolveTimeSpeechLanguage()
        )
    }

    @Test
    fun `time announcement speaks arabic despite english number language`() {
        // لغة نطق الأرقام العام = إنجليزي.
        val settings = SettingsRepository.create(context)
        settings.setAnnouncementSpeechLanguage(
            com.aymankhattab.nateq.util.LanguageCode.EN.tag
        )
        // فئة الساعة صراحةً = عربي (لغةُ فئة + صوتٌ عربي).
        clearTimeSettings(settings)
        settings.setLanguageForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME, "ar"
        )
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME, "nateq-ar-local"
        )
        val manager = managerFor(settings)
        assertEquals(
            "الساعة تُنطق عربية رغم أن لغة الأرقام العام إنجليزية",
            AnnouncementLanguageResolver.ARABIC,
            manager.resolveTimeSpeechLanguage()
        )
    }

    @Test
    fun `numbers category keeps honoring the global speech language`() {
        // مفتاح الأرقام العام يظل متصرفاً بفئة الأرقام: عربي ⇒ أرقامٌ عربية
        // حتى لو كانت لغة فئة الأرقام المحفوظة إنجليزية (المفتاح أسبق).
        val settings = SettingsRepository.create(context)
        settings.setAnnouncementSpeechLanguage(
            com.aymankhattab.nateq.util.LanguageCode.AR.tag
        )
        settings.setLanguageForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS, "en"
        )
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS, "nateq-en-local"
        )
        val manager = managerFor(settings)
        assertEquals(
            "فئة الأرقام تبقى تحت سطوة مفتاح لغة الأرقام العام",
            AnnouncementLanguageResolver.ARABIC,
            manager.resolveNumberSpeechLanguage()
        )
    }
}