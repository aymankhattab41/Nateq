package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** اختبارات القارئ الموحّد للغة الفئات الإعلانية:
 *  مفتاح النطق → لغة الفئة → لغة معرّف الصوت (المنطقي والمحرك
 *  المكتشف) → لغة التطبيق. */
class AnnouncementLanguageResolverTest {

    @Test
    fun forcedEnglish_wins() {
        assertEquals(
            AnnouncementLanguageResolver.ENGLISH,
            AnnouncementLanguageResolver.resolve(
                forced = "en",
                categoryLang = "ar",
                savedVoiceId = "com.google.android.tts:arb",
                appLanguage = "ar"
            )
        )
    }

    @Test
    fun forcedArabic_wins() {
        assertEquals(
            AnnouncementLanguageResolver.ARABIC,
            AnnouncementLanguageResolver.resolve(
                forced = "ar",
                categoryLang = "en",
                savedVoiceId = "com.google.android.tts:eng-usa",
                appLanguage = "en"
            )
        )
    }

    @Test
    fun forcedBlank_doesNotBlock() {
        assertEquals(
            AnnouncementLanguageResolver.ENGLISH,
            AnnouncementLanguageResolver.resolve(
                forced = "",
                categoryLang = "en",
                savedVoiceId = null,
                appLanguage = "ar"
            )
        )
    }

    @Test
    fun categoryLangEnglish_beatsSavedVoice() {
        assertEquals(
            AnnouncementLanguageResolver.ENGLISH,
            AnnouncementLanguageResolver.resolve(
                forced = null,
                categoryLang = "en",
                savedVoiceId = "ar-EG",
                appLanguage = "ar"
            )
        )
    }

    @Test
    fun categoryLangArabic_beatsSavedVoice() {
        assertEquals(
            AnnouncementLanguageResolver.ARABIC,
            AnnouncementLanguageResolver.resolve(
                forced = null,
                categoryLang = "ar",
                savedVoiceId = "com.samsung.SMT:en-us",
                appLanguage = "en"
            )
        )
    }

    @Test
    fun logicalEnglishVoiceIds_resolveEnglish() {
        listOf(
            "en-US",
            "nateq-en-int",
            "nateq-en-local",
            "en-local"
        ).forEach { voiceId ->
            assertEquals(
                voiceId,
                AnnouncementLanguageResolver.ENGLISH,
                AnnouncementLanguageResolver.languageOfVoiceId(voiceId)
            )
        }
    }

    @Test
    fun logicalArabicVoiceIds_resolveArabic() {
        listOf(
            "ar-EG",
            "nateq-ar-hq",
            "nateq-ar-local",
            "ar-local"
        ).forEach { voiceId ->
            assertEquals(
                voiceId,
                AnnouncementLanguageResolver.ARABIC,
                AnnouncementLanguageResolver.languageOfVoiceId(voiceId)
            )
        }
    }

    @Test
    fun discoveredEngineEnglishVoices_resolveEnglish() {
        listOf(
            "com.google.android.tts:eng-usa",
            "com.samsung.SMT:en-us",
            "com.ivona.tts:en_GB"
        ).forEach { voiceId ->
            assertEquals(
                voiceId,
                AnnouncementLanguageResolver.ENGLISH,
                AnnouncementLanguageResolver.languageOfVoiceId(voiceId)
            )
        }
    }

    @Test
    fun discoveredEngineArabicVoices_resolveArabic() {
        listOf(
            "com.google.android.tts:arb",
            "com.google.android.tts:ara-x-f",
            "com.samsung.SMT:ar-sa"
        ).forEach { voiceId ->
            assertEquals(
                voiceId,
                AnnouncementLanguageResolver.ARABIC,
                AnnouncementLanguageResolver.languageOfVoiceId(voiceId)
            )
        }
    }

    @Test
    fun unknownEngineVoiceName_returnsNull() {
        assertNull(
            AnnouncementLanguageResolver.languageOfVoiceId(
                "com.thirdparty.tts:custom-voice"
            )
        )
    }

    @Test
    fun blankAndNullVoiceIds_returnNull() {
        assertNull(AnnouncementLanguageResolver.languageOfVoiceId(null))
        assertNull(AnnouncementLanguageResolver.languageOfVoiceId(""))
    }

    @Test
    fun savedVoice_beatsAppLanguage() {
        assertEquals(
            AnnouncementLanguageResolver.ENGLISH,
            AnnouncementLanguageResolver.resolve(
                forced = null,
                categoryLang = null,
                savedVoiceId = "com.google.android.tts:eng-usa",
                appLanguage = "ar"
            )
        )
    }

    @Test
    fun appLanguage_fallbackWhenNothingElse() {
        assertEquals(
            AnnouncementLanguageResolver.ENGLISH,
            AnnouncementLanguageResolver.resolve(
                forced = null,
                categoryLang = null,
                savedVoiceId = null,
                appLanguage = "en"
            )
        )
        assertEquals(
            AnnouncementLanguageResolver.ARABIC,
            AnnouncementLanguageResolver.resolve(
                forced = null,
                categoryLang = null,
                savedVoiceId = null,
                appLanguage = "ar"
            )
        )
    }

    @Test
    fun unknownVoiceId_fallsBackToAppLanguage() {
        assertEquals(
            AnnouncementLanguageResolver.ARABIC,
            AnnouncementLanguageResolver.resolve(
                forced = null,
                categoryLang = null,
                savedVoiceId = "com.thirdparty.tts:custom-voice",
                appLanguage = "ar"
            )
        )
    }
}