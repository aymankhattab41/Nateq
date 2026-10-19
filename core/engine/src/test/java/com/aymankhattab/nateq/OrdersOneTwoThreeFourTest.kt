package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.EmojiLanguageResolver
import com.aymankhattab.nateq.core.audio.engine.LanguageSegmenter
import com.aymankhattab.nateq.core.audio.engine.LatinLanguageDetector
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات شاملة تغطي متطلبات الأوامر 1 و2 و3 و4.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OrdersOneTwoThreeFourTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary
    private lateinit var processor: TextProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dict = PronunciationDictionary(context)
        processor = TextProcessor(context, injectedDict = dict)
    }

    @After
    fun tearDown() {
        dict.clear()
    }

    // --- اختبارات الأمر 1: القاموس أولاً والمطابقة المطبّعة ---

    @Test
    fun `order 1 - 50j with key j becomes 50 guinea`() {
        dict.addEntry("ج", "جنيه")
        val result = processor.applyDictionaryFirst("٥٠ج", "ar")
        assertEquals("٥٠جنيه", result)
    }

    @Test
    fun `order 1 - key with latin 50j matches indic 50j`() {
        dict.addEntry("50ج", "خمسون جنيها")
        val result = dict.apply("٥٠ج")
        assertEquals("خمسون جنيها", result)
    }

    @Test
    fun `order 1 - vocalized word matches unvocalized key`() {
        dict.addEntry("مكتب", "ديوان")
        val result = dict.apply("مَكْتَبٌ")
        assertEquals("ديوان", result)
    }

    @Test
    fun `order 1 - emoji key matches before emoji expansion`() {
        dict.addEntry("😀", "ابتسامة عريضة")
        val result = processor.applyDictionaryFirst("مرحبا 😀 بكم", "ar")
        assertEquals("مرحبا ابتسامة عريضة بكم", result)
    }

    @Test
    fun `order 1 - language layer scoping separates arabic and english`() {
        dict.addEntry("سيارة", "مركبة", "en")
        dict.addEntry("car", "vehicle", "ar")
        assertEquals("هذه سيارة جميلة", dict.apply("هذه سيارة جميلة", "ar"))
        assertEquals("this car is fast", dict.apply("this car is fast", "en"))

        dict.addEntry("car", "مركبة", "en")
        assertEquals(
            "this مركبة is fast", dict.apply("this car is fast", "en")
        )
    }

    @Test
    fun `order 1 - single application prevents double replacement`() {
        dict.addEntry("م", "متر")
        val first = processor.applyDictionaryFirst("5 م", "ar")
        assertEquals("5 متر", first)
        val full = processor.process(first, "ar", applyDictionary = false)
        assertTrue(!full.contains("مترتر"))
    }

    @Test
    fun `order 1 - french text gets dictionary applied`() {
        dict.addEntry("bonjour", "أهلا")
        val result = processor.applyDictionaryFirst("bonjour le monde", "fr")
        assertEquals("أهلا le monde", result)
    }

    // --- اختبارات الأمر 2: الحرف الواحد المنفرد وأوضاع التطبيق ---

    @Test
    fun `order 2 - single letter m does not corrupt words`() {
        dict.addEntry("م", "متر")
        assertEquals("مرحبا", dict.apply("مرحبا"))
        assertEquals("5 متر", dict.apply("5 م"))
        assertEquals("٥متر", dict.apply("٥م"))
        assertEquals("متر", dict.apply("م"))
        assertEquals("محمد متر.", dict.apply("محمد م."))
    }

    @Test
    fun `order 2 - single letter k matches alone and next to digits`() {
        dict.addEntry("k", "كيلو")
        assertEquals("kg", dict.apply("kg"))
        assertEquals("5كيلو", dict.apply("5k"))
        assertEquals("can't", dict.apply("can't"))
        assertEquals("كيلو", dict.apply("k"))
    }

    @Test
    fun `order 2 - vocalized single letter saad matches when standalone`() {
        dict.addEntry("ص", "صفحة")
        assertEquals("صفحة", dict.apply("صَ"))
    }

    @Test
    fun `order 2 - keys m and mm together pick longest match mm first`() {
        dict.addEntry("م", "متر")
        dict.addEntry("مم", "ملم")
        assertEquals("5 ملم", dict.apply("5 مم"))
        assertEquals("5 متر", dict.apply("5 م"))
    }

    @Test
    fun `order 2 - standalone max length 0 restores anywhere match`() {
        dict.setStandaloneMaxLength(0)
        dict.addEntry("م", "متر")
        assertEquals("متررحبا", dict.apply("مرحبا"))
    }

    @Test
    fun `order 2 - WHOLE mode for 3-letter key avoids longer word`() {
        dict.addEntry("عرب", "عربات", null, "WHOLE")
        assertEquals("عربات", dict.apply("عرب"))
        assertEquals("يعربون", dict.apply("يعربون"))
    }

    // --- اختبارات الأمر 3: قفل اللغة والأرقام التلقائية والكاشف اللاتيني ---

    @Test
    fun `order 3 - language lock markers are parsed correctly`() {
        val segmenter = LanguageSegmenter()
        val text = "كلمة " +
            "${LanguageSegmenter.LOCK_START}en" +
            "${LanguageSegmenter.LOCK_TAG_END}word" +
            "${LanguageSegmenter.LOCK_END} بعد"
        val segs = segmenter.segment(text, "ar")
        val locked = segs.firstOrNull { it.text == "word" }
        assertEquals("en", locked?.languageTag)
    }

    @Test
    fun `order 3 - latin detector rejects common words blacklist`() {
        val detected = LatinLanguageDetector.detect("hotel taxi restaurant")
        assertNotEquals("fr", detected)
        assertNotEquals("es", detected)
        assertNotEquals("de", detected)
    }

    @Test
    fun `order 3 - latin detector identifies decisive diacritics`() {
        assertEquals("de", LatinLanguageDetector.detect("schön"))
        assertEquals("es", LatinLanguageDetector.detect("señor"))
        assertEquals("fr", LatinLanguageDetector.detect("garçon"))
    }

    @Test
    fun `order 3 - number language auto tracks sentence language`() {
        val segmenter = LanguageSegmenter()
        val enInAr = segmenter.segment(
            "I have 5 books",
            fallbackLanguage = "ar",
            numberLanguage = LanguageSegmenter.NUMBER_LANG_AUTO
        )
        assertEquals(listOf("I have 5 books"), enInAr.map { it.text })
        assertEquals(listOf("en"), enInAr.map { it.languageTag })

        val arInEn = segmenter.segment(
            "عندي 5 كتب",
            fallbackLanguage = "en",
            numberLanguage = LanguageSegmenter.NUMBER_LANG_AUTO
        )
        assertEquals(listOf("عندي 5 كتب"), arInEn.map { it.text })
        assertEquals(listOf("ar"), arInEn.map { it.languageTag })
    }

    // --- اختبارات الأمر 4: حل لغة الإيموجي الموحد ---

    @Test
    fun `order 4 - emoji language resolver follows settings`() {
        assertEquals(
            "ar",
            EmojiLanguageResolver.resolve("AR", "en")
        )
        assertEquals(
            "en",
            EmojiLanguageResolver.resolve("EN", "ar")
        )
        assertEquals(
            "ar",
            EmojiLanguageResolver.resolve("AUTO", "ar")
        )
        assertEquals(
            "en",
            EmojiLanguageResolver.resolve("AUTO", "en")
        )
        assertEquals(
            "en",
            EmojiLanguageResolver.resolve(
                "AUTO", "ar", emojiVoiceId = "vocalizer:en_US_ava"
            )
        )
    }
}
