package com.aymankhattab.nateq.core.audio.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 50 و51 — الأمر 3:
 * حماية أسماء الأشهر الهجرية وتجاوز المحارف غير المرئية وتتبع المسار كاملاً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HijriMonthAndTraceDictTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dict = PronunciationDictionary(context)
        dict.clear()
        dict.setStandaloneMaxLength(1)
    }

    @Test
    fun singleCharKey_doesNotMatchInsideHijriMonths_inAutoMode() {
        dict.addEntry(
            abbreviation = "ج",
            pronunciation = "جنيه",
            languageTag = "ar",
            matchMode = "AUTO"
        )

        val processor = TextProcessor(context, injectedDict = dict)

        // (أ) النص العادي
        val jumada1 = processor.applyDictionaryFirst("جُمادى الأولى", "ar")
        assertEquals("جُمادى الأولى", jumada1)

        // (ب) النص مع U+200B مسافة صفرية بعد الجيم
        val withZeroWidth = "ج\u200Bُمادى الأولى"
        val resZeroWidth = processor.applyDictionaryFirst(withZeroWidth, "ar")
        assertFalse(
            "يجب ألا تظهر كلمة جنيه داخل جُمادى مع U+200B",
            resZeroWidth.contains("جنيه")
        )

        // (ج) النص مع علامات الاتجاه U+200F و U+061C
        val withDirMarks = "ج\u200F\u061Cُمادى الأولى"
        val resDirMarks = processor.applyDictionaryFirst(withDirMarks, "ar")
        assertFalse(
            "يجب ألا تظهر كلمة جنيه داخل جُمادى مع علامات الاتجاه",
            resDirMarks.contains("جنيه")
        )

        // (د) رسالة واتساب مركّبة
        val whatsAppMsg = "ديوان جُمادى الأولى ١٤٤٧"
        val resWhatsApp = processor.applyDictionaryFirst(whatsAppMsg, "ar")
        assertFalse(
            "رسالة واتساب المركبة يجب ألا تحوي جنيهمادى",
            resWhatsApp.contains("جنيه")
        )

        val friday = processor.applyDictionaryFirst("الجمعة", "ar")
        assertEquals("الجمعة", friday)

        val ramadan = processor.applyDictionaryFirst("رمضان", "ar")
        assertEquals("رمضان", ramadan)

        // بينما الحرف المنفرد أو اللاحق للأرقام يُبدَّل
        val standalone = processor.applyDictionaryFirst("ج", "ar")
        assertEquals("جنيه", standalone)

        val amount1 = processor.applyDictionaryFirst("50 ج", "ar")
        assertEquals("50 جنيه", amount1)

        val amount2 = processor.applyDictionaryFirst("٥٠ج", "ar")
        assertEquals("٥٠جنيه", amount2)
    }

    @Test
    fun singleCharKey_doesNotCorruptProtectedWords_evenInAnywhereMode() {
        dict.addEntry(
            abbreviation = "ج",
            pronunciation = "جنيه",
            languageTag = "ar",
            matchMode = "ANYWHERE"
        )

        val processor = TextProcessor(context, injectedDict = dict)
        val jumada = processor.applyDictionaryFirst("جمادى الأولى", "ar")
        assertEquals("جمادى الأولى", jumada)
    }

    @Test
    fun traceMatches_providesReasonsForAcceptAndReject() {
        dict.addEntry(
            abbreviation = "ج",
            pronunciation = "جنيه",
            languageTag = "ar",
            matchMode = "AUTO"
        )

        val traces = dict.traceMatches("جُمادى الأولى", "ar")
        val rejectedTrace = traces.firstOrNull { it.key == "ج" }
        assertNotNull(rejectedTrace)
        assertFalse(rejectedTrace?.accepted ?: true)
        assertTrue(
            rejectedTrace?.reason?.contains("محمية") == true ||
                rejectedTrace?.reason?.contains("جار") == true
        )
    }

    @Test
    fun processWithTrace_returnsAllPipelineStages() {
        dict.addEntry(
            abbreviation = "ج",
            pronunciation = "جنيه",
            languageTag = "ar",
            matchMode = "AUTO"
        )

        val processor = TextProcessor(context, injectedDict = dict)
        val trace = processor.processWithTrace("ديوان جُمادى الأولى ١٤٤٧", "ar")

        assertTrue("يجب أن يعيد التتبع مراحل متعددة", trace.size >= 5)
        assertTrue(
            "المرحلة الأولى تعرض النص الخام ومحارفه",
            trace[0].first.contains("النص الخام")
        )
        assertTrue(
            "المرحلة الثانية تعرض القاموس",
            trace[1].first.contains("القاموس")
        )
    }
}
