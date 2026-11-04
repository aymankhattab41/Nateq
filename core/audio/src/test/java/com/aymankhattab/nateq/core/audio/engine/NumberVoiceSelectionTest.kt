package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.prepareArabicForEngine
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات شاملة لمتطلبات الأمر 1:
 * نطق أي رقم بصوت الأرقام المختار في القراءة الحية
 * (مكتوباً بالأرقام أو بالحروف).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NumberVoiceSelectionTest {

    private lateinit var context: Context
    private lateinit var processor: TextProcessor
    private lateinit var segmenter: LanguageSegmenter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dict = PronunciationDictionary(context)
        processor = TextProcessor(context, injectedDict = dict)
        segmenter = LanguageSegmenter { "ar" }
    }

    private fun processAndSegment(
        text: String,
        lang: String = "ar"
    ): List<SegmentWithOffset> {
        val semantic = processor.processSemantics(
            text, lang, markNumbers = true
        )
        return segmenter.segmentWithOffsets(
            semantic,
            fallbackLanguage = lang,
            numberLanguage = "ar"
        )
    }

    @Test
    fun testItem5Of7Arabic() {
        val segs = processAndSegment("العنصر 5 من 7", "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(2, numSegs.size)
        assertEquals("5", numSegs[0].text)
        assertEquals("7", numSegs[1].text)
        val textSegs = segs.filter { it.role == SpeechRole.TEXT }
        assertTrue(textSegs.any { it.text.contains("العنصر") })
        assertTrue(textSegs.any { it.text.contains("من") })
    }

    @Test
    fun testItem5Of7English() {
        val segs = processAndSegment("Item 5 of 7", "en")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(2, numSegs.size)
        assertEquals("5", numSegs[0].text)
        assertEquals("7", numSegs[1].text)
    }

    @Test
    fun testItemCountWithTotal() {
        val input = "عدد العناصر 5 من 7 من إجمالي 8"
        val segs = processAndSegment(input, "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(3, numSegs.size)
        assertEquals("5", numSegs[0].text)
        assertEquals("7", numSegs[1].text)
        assertEquals("8", numSegs[2].text)
    }

    @Test
    fun testEasternArabicNumerals() {
        val segs = processAndSegment("صفحة ٣ من ١٠", "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(2, numSegs.size)
        // أرقام المشرق العربي تُوحّد عبر IndicDigitsStep
        assertEquals("3", numSegs[0].text)
        assertEquals("10", numSegs[1].text)
    }

    @Test
    fun testTab2Of4() {
        val segs = processAndSegment("tab 2 of 4", "en")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(2, numSegs.size)
        assertEquals("2", numSegs[0].text)
        assertEquals("4", numSegs[1].text)
    }

    @Test
    fun testSpelledOutNumbersArabic() {
        val segs = processAndSegment("خمسة من سبعة", "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(2, numSegs.size)
        assertEquals("خمسة", numSegs[0].text)
        assertEquals("ar", numSegs[0].languageTag)
        assertEquals("سبعة", numSegs[1].text)
        assertEquals("ar", numSegs[1].languageTag)
    }

    @Test
    fun testSpelledOutNumbersEnglish() {
        val segs = processAndSegment("five of seven", "en")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(2, numSegs.size)
        assertEquals("five", numSegs[0].text)
        assertEquals("en", numSegs[0].languageTag)
        assertEquals("seven", numSegs[1].text)
        assertEquals("en", numSegs[1].languageTag)
    }

    @Test
    fun testCurrencySeparation() {
        val segs = processAndSegment("50ج", "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertTrue(numSegs.isNotEmpty())
        val textSegs = segs.filter { it.role == SpeechRole.TEXT }
        assertTrue(textSegs.any { it.text.contains("جنيهاً") })
    }

    @Test
    fun testTimeSeparation() {
        val segs = processAndSegment("الساعة 5:10 م", "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertTrue(numSegs.isNotEmpty())
        val textSegs = segs.filter { it.role == SpeechRole.TEXT }
        assertTrue(
            textSegs.any { it.text.contains("مساءً") || it.text.contains("م") }
        )
    }

    @Test
    fun testPaymentAmountSeparation() {
        val segs = processAndSegment("تم الدفع 150 جنيه", "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(1, numSegs.size)
        assertEquals("150", numSegs[0].text)
        val textSegs = segs.filter { it.role == SpeechRole.TEXT }
        assertTrue(textSegs.any { it.text.contains("تم الدفع") })
        assertTrue(textSegs.any { it.text.contains("جنيه") })
    }

    @Test
    fun testPhoneNumberSeparation() {
        val segs = processAndSegment("رقمك 0123456789", "ar")
        val numSegs = segs.filter { it.role == SpeechRole.NUMBER }
        assertEquals(1, numSegs.size)
        assertEquals("0123456789", numSegs[0].text)
    }

    @Test
    fun testNoMarkerLeakInSpeechPlanner() {
        val items = SpeechPlanner.plan(
            rawText = "العنصر 5 من 7 — خمسة وعشرون جنيهاً",
            fallbackLanguage = "ar",
            segmenter = segmenter
        )
        for (item in items) {
            assertFalse(
                item.text.contains(EngineMarkers.NUMBER_MARK_START)
            )
            assertFalse(
                item.text.contains(EngineMarkers.NUMBER_MARK_END)
            )
        }
    }

    @Test
    fun testNoMarkerLeakInPrepareArabic() {
        val dirty = "نص ${EngineMarkers.NUMBER_MARK_START}5" +
            "${EngineMarkers.NUMBER_MARK_END} تجريبي"
        val cleanAr = prepareArabicForEngine(dirty, "ar")
        assertFalse(cleanAr.contains(EngineMarkers.NUMBER_MARK_START))
        assertFalse(cleanAr.contains(EngineMarkers.NUMBER_MARK_END))

        val cleanEn = prepareArabicForEngine(dirty, "en")
        assertFalse(cleanEn.contains(EngineMarkers.NUMBER_MARK_START))
        assertFalse(cleanEn.contains(EngineMarkers.NUMBER_MARK_END))
    }

    @Test
    fun testLanguageMatchingRuleInVoiceResolution() {
        // فئة الأرقام بصوت عربي
        val categoryVoiceAr = "ar-EG"
        // مقطع إنجليزي "five"
        val resolvedEn = VoiceResolutionPolicy.resolve(
            role = SpeechRole.NUMBER,
            languageTag = "en",
            categoryVoice = categoryVoiceAr,
            appVoice = null,
            contactVoice = null,
            languageDefaultVoice = "en-US"
        )
        // يجب أن يرجع SOURCE_CATEGORY_NUMBER_PARAMS ويستخدم صوت الإنجليزية
        assertEquals(
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER_PARAMS,
            resolvedEn.source
        )
        assertEquals("en-US", resolvedEn.voiceId)

        // مقطع عربي "خمسة"
        val resolvedAr = VoiceResolutionPolicy.resolve(
            role = SpeechRole.NUMBER,
            languageTag = "ar",
            categoryVoice = categoryVoiceAr,
            appVoice = null,
            contactVoice = null,
            languageDefaultVoice = "ar-EG-default"
        )
        // يجب أن يرجع SOURCE_CATEGORY_NUMBER ويستخدم صوت فئة الأرقام
        assertEquals(
            VoiceResolutionPolicy.SOURCE_CATEGORY_NUMBER,
            resolvedAr.source
        )
        assertEquals(categoryVoiceAr, resolvedAr.voiceId)
    }
}
