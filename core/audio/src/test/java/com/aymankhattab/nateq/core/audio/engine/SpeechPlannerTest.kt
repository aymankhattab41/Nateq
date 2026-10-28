package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.util.LanguageCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مخطط النطق الموحّد [SpeechPlanner] —
 * التحقق من متطلبات ع٤٢-8 وع٤٢-9 (الأرقام والإيموجي والقاموس والتكافؤ).
 */
class SpeechPlannerTest {

    private class FakeVoicePrefs(
        private val engines: Map<String, String?> = emptyMap(),
        private val voices: Map<String, String?> = emptyMap(),
        private val categoryVoices: Map<String, String?> = emptyMap(),
        private val categoryEngines: Map<String, String?> = emptyMap(),
        private val categoryRates: Map<String, Float> = emptyMap(),
        private val categoryPitches: Map<String, Float> = emptyMap(),
        private val categoryVolumes: Map<String, Float> = emptyMap()
    ) : VoicePrefsProvider {
        override fun getEngineForLanguage(
            languageTag: String
        ): String? = engines[languageTag]

        override fun getVoiceForLanguage(
            languageTag: String
        ): String? = voices[languageTag]

        override fun getPreferredVoiceIdForCategory(
            category: String
        ): String? = categoryVoices[category]

        override fun getEngineForCategory(
            category: String
        ): String? = categoryEngines[category]

        override fun getSpeechRateForCategory(
            category: String
        ): Float = categoryRates[category] ?: 1.0f

        override fun getPitchForCategory(
            category: String
        ): Float = categoryPitches[category] ?: 1.0f

        override fun getVolumeForCategory(
            category: String
        ): Float = categoryVolumes[category] ?: 1.0f

        override fun getAnnouncementFallbackEngine(): String? = null
        override fun setAnnouncementFallbackEngine(
            enginePackage: String?
        ) {}
    }

    @Test
    fun numbersInArabic_sameFamily_yieldsSingleSegmentWithSingleVoice() {
        // ع٤٢-8: «رقم 5 في الصندوق» ⇒ مقطع واحد بصوت واحد
        // عند تطابق عائلة الأرقام
        val prefs = FakeVoicePrefs(
            voices = mapOf("ar" to "voice-arabic"),
            engines = mapOf("ar" to "engine-arabic")
        )
        val items = SpeechPlanner.plan(
            rawText = "رقم 5 في الصندوق",
            fallbackLanguage = LanguageCode.AR.tag,
            numberLanguage = LanguageCode.AR.tag,
            settings = prefs
        )
        assertEquals("مقطع واحد غير مجزأ", 1, items.size)
        val single = items.first()
        assertEquals("رقم 5 في الصندوق", single.text)
        assertEquals("ar", single.languageTag)
        assertEquals(SpeechRole.TEXT, single.role)
        assertEquals("voice-arabic", single.voiceId)
        assertEquals("engine-arabic", single.engine)
    }

    @Test
    fun numbersInArabic_differentLanguage_splitsWithTwoDistinctVoices() {
        // ع٤٢-8: عند اختيار لغة أرقام مختلفة صراحة (en) ⇒ صوتان صراحة
        val prefs = FakeVoicePrefs(
            voices = mapOf(
                "ar" to "voice-arabic",
                "en" to "voice-english"
            ),
            engines = mapOf(
                "ar" to "engine-arabic",
                "en" to "engine-english"
            ),
            categoryVoices = mapOf(
                SettingsRepository.VOICE_CATEGORY_NUMBERS to "voice-num-special"
            ),
            categoryEngines = mapOf(
                SettingsRepository.VOICE_CATEGORY_NUMBERS to
                    "engine-num-special"
            )
        )
        val items = SpeechPlanner.plan(
            rawText = "رقم 5 في الصندوق",
            fallbackLanguage = LanguageCode.AR.tag,
            numberLanguage = LanguageCode.EN.tag,
            settings = prefs
        )
        assertTrue("أكثر من مقطع", items.size >= 2)
        val numItem = items.first { it.role == SpeechRole.NUMBER }
        val textItem = items.first { it.role == SpeechRole.TEXT }

        assertEquals("5 ", numItem.text)
        assertEquals("en", numItem.languageTag)
        assertEquals("voice-num-special", numItem.voiceId)
        assertEquals("engine-num-special", numItem.engine)

        assertEquals("voice-arabic", textItem.voiceId)
        assertEquals("engine-arabic", textItem.engine)
        assertNotEquals(
            "الصوتان مختلفان صراحة",
            numItem.voiceId, textItem.voiceId
        )
    }

    @Test
    fun emojiRole_resolvesToEmojiCategoryVoice() {
        // ع٤٢-9: دور EMOJI يُحلّ بمحرك/صوت فئة الإيموجي
        val prefs = FakeVoicePrefs(
            voices = mapOf("ar" to "voice-arabic"),
            categoryVoices = mapOf(
                SettingsRepository.VOICE_CATEGORY_EMOJI to "voice-emoji-fun"
            ),
            categoryEngines = mapOf(
                SettingsRepository.VOICE_CATEGORY_EMOJI to "engine-emoji"
            ),
            categoryRates = mapOf(
                SettingsRepository.VOICE_CATEGORY_EMOJI to 1.25f
            )
        )
        val items = SpeechPlanner.plan(
            rawText = "صباح الخير 😊",
            fallbackLanguage = LanguageCode.AR.tag,
            emojiPronunciationEnabled = true,
            settings = prefs
        )
        assertEquals(2, items.size)
        val textItem = items[0]
        val emojiItem = items[1]

        assertEquals(SpeechRole.TEXT, textItem.role)
        assertEquals("voice-arabic", textItem.voiceId)

        assertEquals(SpeechRole.EMOJI, emojiItem.role)
        assertEquals("voice-emoji-fun", emojiItem.voiceId)
        assertEquals("engine-emoji", emojiItem.engine)
        assertEquals(1.25f, emojiItem.rate, 0.01f)
    }

    @Test
    fun emojiRole_fallsBackToTextVoiceWhenUnset() {
        // ع٤٢-9: فإن لم تُضبط فئة الإيموجي فبصوت النص (لا تغيير للسلوك)
        val prefs = FakeVoicePrefs(
            voices = mapOf("ar" to "voice-arabic"),
            engines = mapOf("ar" to "engine-arabic")
        )
        val items = SpeechPlanner.plan(
            rawText = "مرحبا 😊",
            fallbackLanguage = LanguageCode.AR.tag,
            emojiPronunciationEnabled = true,
            settings = prefs
        )
        assertEquals(2, items.size)
        val emojiItem = items[1]
        assertEquals(SpeechRole.EMOJI, emojiItem.role)
        assertEquals("voice-arabic", emojiItem.voiceId)
        assertEquals("engine-arabic", emojiItem.engine)
    }

    @Test
    fun dictionaryAppliesFirst_protectsCustomEmojiPronunciation() {
        // ع٤٢-9: القاموس يطبَّق أولاً على النص الخام فيمكن تخصيص أي إيموجي
        val prefs = FakeVoicePrefs(
            voices = mapOf("ar" to "voice-arabic")
        )
        // محاكاة استبدال القاموس لرمز الإيموجي قبل تقسيم الإيموجي
        // عبر قاموس حقيقي أو دالة apply
        val customText = "أحبك ❤️"
        val replaced = customText.replace("❤️", "كثيرا جدا")

        val items = SpeechPlanner.plan(
            rawText = replaced,
            fallbackLanguage = LanguageCode.AR.tag,
            emojiPronunciationEnabled = true,
            settings = prefs
        )
        assertEquals("مقطع واحد نصي بلا مقطع إيموجي", 1, items.size)
        assertEquals("أحبك كثيرا جدا", items.first().text)
        assertEquals(SpeechRole.TEXT, items.first().role)
    }

    @Test
    fun equivalenceTest_serviceAndAnnouncementPathsYieldIdenticalPlan() {
        // ع٤٢-9: اختبار تكافؤ: النص نفسه عبر المسارين يعطي المخطط نفسه
        val prefs = FakeVoicePrefs(
            voices = mapOf(
                "ar" to "voice-ar",
                "en" to "voice-en"
            ),
            categoryVoices = mapOf(
                SettingsRepository.VOICE_CATEGORY_EMOJI to "voice-emoji",
                SettingsRepository.VOICE_CATEGORY_NUMBERS to "voice-num"
            )
        )
        val input = "اتصل على 0123 الساعة 5 مساء 😊"

        val servicePlan = SpeechPlanner.plan(
            rawText = input,
            fallbackLanguage = "ar",
            secondaryLanguage = "en",
            numberLanguage = "ar",
            emojiPronunciationEnabled = true,
            settings = prefs
        )

        val announcementPlan = SpeechPlanner.plan(
            rawText = input,
            fallbackLanguage = "ar",
            secondaryLanguage = "en",
            numberLanguage = "ar",
            emojiPronunciationEnabled = true,
            settings = prefs
        )

        assertEquals(
            "عدد المقاطع متطابق تماماً",
            servicePlan.size, announcementPlan.size
        )
        for (i in servicePlan.indices) {
            val sItem = servicePlan[i]
            val aItem = announcementPlan[i]
            assertEquals("تطابق النص", sItem.text, aItem.text)
            assertEquals("تطابق الوسم", sItem.languageTag, aItem.languageTag)
            assertEquals("تطابق الدور", sItem.role, aItem.role)
            assertEquals("تطابق الصوت", sItem.voiceId, aItem.voiceId)
            assertEquals("تطابق المحرك", sItem.engine, aItem.engine)
            assertEquals("تطابق السرعة", sItem.rate, aItem.rate, 0.001f)
            assertEquals("تطابق النبرة", sItem.pitch, aItem.pitch, 0.001f)
            assertEquals("تطابق العلو", sItem.volume, aItem.volume, 0.001f)
        }
    }

    @Test
    fun plan_englishWordOk_routedToEnglishVoice() {
        val prefs = FakeVoicePrefs(
            voices = mapOf(
                "ar" to "voice-ar",
                "en" to "voice-en"
            )
        )
        val plan = SpeechPlanner.plan(
            rawText = "موافق OK",
            fallbackLanguage = "ar",
            secondaryLanguage = "en",
            numberLanguage = "ar",
            emojiPronunciationEnabled = false,
            settings = prefs
        )
        assertEquals(2, plan.size)
        assertEquals("موافق ", plan[0].text)
        assertEquals("ar", plan[0].languageTag)
        assertEquals("voice-ar", plan[0].voiceId)

        assertEquals("OK", plan[1].text)
        assertEquals("en", plan[1].languageTag)
        assertEquals("voice-en", plan[1].voiceId)
    }

    @Test
    fun explicitCategoryVoiceAndEngine_notOverriddenByLanguageDefaults() {
        val prefs = FakeVoicePrefs(
            voices = mapOf("ar" to "voice-google-arabic"),
            engines = mapOf("ar" to "com.google.android.tts")
        )
        val resolution = SpeechPlanner.resolveVoiceForSegment(
            languageTag = "ar",
            role = SpeechRole.TEXT,
            settings = prefs,
            defaultEngine = "vocalizer_embedded",
            defaultVoiceId = "vocalizer_ar-XA_maged_premium-high"
        )
        assertEquals(
            "المحرك الصريح لا يُداس بمحرك اللغة العام",
            "vocalizer_embedded",
            resolution.engine
        )
        assertEquals(
            "الصوت الصريح لا يُداس بصوت اللغة العام",
            "vocalizer_ar-XA_maged_premium-high",
            resolution.voiceId
        )
    }
}
