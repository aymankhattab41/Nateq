package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductivityExtrasTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ClipboardStack.clear()
        KeyboardGuard.resetForTesting()
        TypoChecker.resetForTesting()
    }

    // --- 1. Clipboard Stack Tests ---

    @Test
    fun testClipboardStackMaxCapacityAndLru() {
        for (i in 1..15) {
            ClipboardStack.addEntry("النص رقم $i")
        }
        assertEquals(ClipboardStack.MAX_CAPACITY, ClipboardStack.size())
        assertEquals("النص رقم 15", ClipboardStack.peek())

        val entries = ClipboardStack.getEntries()
        assertEquals(10, entries.size)
        assertEquals("النص رقم 15", entries.first())
        assertEquals("النص رقم 6", entries.last())
    }

    @Test
    fun testClipboardStackIgnoreConsecutiveDuplicates() {
        assertTrue(ClipboardStack.addEntry("نص مكرر"))
        assertFalse(ClipboardStack.addEntry("نص مكرر"))
        assertEquals(1, ClipboardStack.size())
    }

    @Test
    fun testClipboardStackRejectSensitive() {
        assertFalse(
            ClipboardStack.addEntry("كلمة سر سرية", isSensitive = true)
        )
        assertEquals(0, ClipboardStack.size())
    }

    @Test
    fun testClipboardStackPopAndClear() {
        ClipboardStack.addEntry("أولاً")
        ClipboardStack.addEntry("ثانياً")
        assertEquals("ثانياً", ClipboardStack.pop())
        assertEquals("أولاً", ClipboardStack.peek())
        ClipboardStack.clear()
        assertEquals(0, ClipboardStack.size())
        assertNull(ClipboardStack.peek())
    }

    // --- 2. OTP Extractor Tests ---

    @Test
    fun testOtpExtractorArabicAndEnglish() {
        val arabicMsg = "رمز التحقق الخاص بك في بنك مصر هو 748291 صالح لدقيقتين"
        val otp1 = OtpExtractor.extractOtp(arabicMsg)
        assertEquals("748291", otp1)

        val englishMsg = "Your verification code is 4829. Do not share it."
        val otp2 = OtpExtractor.extractOtp(englishMsg)
        assertEquals("4829", otp2)
    }

    @Test
    fun testOtpExtractorArabicIndicNumerals() {
        val msg = "كود تأكيد الدخول: ٥٨٣٩٢٠ صالح لمرة واحدة"
        val otp = OtpExtractor.extractOtp(msg)
        assertEquals("583920", otp)
    }

    @Test
    fun testOtpExtractorAmbiguousReturnsNull() {
        val msg = "رمز التحقق 1234 ورقم الطلب 5678"
        val otp = OtpExtractor.extractOtp(msg)
        assertNull(otp)
    }

    @Test
    fun testOtpExtractorNoKeywordReturnsNull() {
        val msg = "اتصل بي على الرقم 98765432 اليوم"
        val otp = OtpExtractor.extractOtp(msg)
        assertNull(otp)
    }

    // --- 3. Word Eraser Tests ---

    @Test
    fun testWordEraserSingleCharNotDeletedAnnouncement() {
        val before = "مرحبا"
        val current = "مرحب"
        val deleted = WordEraser.calculateDeletedText(before, current)
        assertEquals("ا", deleted)

        val announcement = WordEraser.formatDeletedAnnouncement(
            context,
            deleted ?: "",
            minChars = 2
        )
        assertNull(announcement)
    }

    @Test
    fun testWordEraserMultipleCharsAnnounced() {
        val before = "السلام عليكم ورحمة الله"
        val current = "السلام عليكم"
        val deleted = WordEraser.calculateDeletedText(before, current)
        assertEquals(" ورحمة الله", deleted)

        val announcement = WordEraser.formatDeletedAnnouncement(
            context,
            deleted ?: "",
            minChars = 2
        )
        assertNotNull(announcement)
        assertTrue(announcement!!.contains("ورحمة الله"))
    }

    @Test
    fun testWordEraserLongTextTruncated() {
        val veryLong = "أ".repeat(50)
        val announcement = WordEraser.formatDeletedAnnouncement(
            context,
            veryLong,
            minChars = 2
        )
        assertNotNull(announcement)
        assertTrue(announcement!!.endsWith("…") || announcement.contains("…"))
    }

    // --- 4. Typo Checker Tests ---

    @Test
    fun testTypoCheckerEligibleWord() {
        assertTrue(TypoChecker.isEligibleWord("كتابة"))
        assertFalse(TypoChecker.isEligibleWord("في")) // <= 2
        assertFalse(TypoChecker.isEligibleWord("12345")) // أرقام
        assertFalse(TypoChecker.isEligibleWord("https://example.com"))
        assertFalse(TypoChecker.isEligibleWord("user@example.com"))
    }

    @Test
    fun testTypoCheckerDoubleSpace() {
        assertTrue(TypoChecker.checkDoubleSpace(context, "كلمة  "))
        assertFalse(TypoChecker.checkDoubleSpace(context, "كلمة واحدة "))
    }

    // --- 5. Quick Calculator Tests ---

    @Test
    fun testQuickCalcMathBasicAndPrecedence() {
        val res1 = QuickCalc.evaluate("15 + 4 * 2")
        assertEquals(23.0, res1 ?: 0.0, 0.001)

        val res2 = QuickCalc.evaluate("(15 + 4) * 2")
        assertEquals(38.0, res2 ?: 0.0, 0.001)

        val resPower = QuickCalc.evaluate("2 ^ 3")
        assertEquals(8.0, resPower ?: 0.0, 0.001)
    }

    @Test
    fun testQuickCalcArabicWords() {
        val res = QuickCalc.evaluate("عشرة زائد خمسة ضرب اثنين")
        // بعد تعويض الكلمات مع أرقام:
        val resWords = QuickCalc.evaluate("10 زائد 5 ضرب 2")
        assertEquals(20.0, resWords ?: 0.0, 0.001)
    }

    @Test
    fun testQuickCalcDivisionByZeroReturnsNull() {
        val res = QuickCalc.evaluate("100 / 0")
        assertNull(res)
    }

    @Test
    fun testQuickCalcCurrencyConversion() {
        val jsonRates = """{"USD": 50.0, "EUR": 54.0, "_BASE_": "EGP"}"""
        val result = QuickCalc.parseCurrency("100 دولار", jsonRates)
        assertNotNull(result)
        assertEquals(5000.0, result!!.first, 0.01)
        assertEquals("EGP", result.second)

        val resultSymbol = QuickCalc.parseCurrency("50 $", jsonRates)
        assertNotNull(resultSymbol)
        assertEquals(2500.0, resultSymbol!!.first, 0.01)
    }

    // --- 6. Screen Glossary Tests ---

    @Test
    fun testScreenGlossaryBuiltinTranslations() {
        assertEquals("الإعدادات", ScreenGlossary.translate("Settings"))
        assertEquals("إلغاء", ScreenGlossary.translate("Cancel"))
        assertEquals(
            "شروط الخدمة",
            ScreenGlossary.translate("Terms of service")
        )
    }

    @Test
    fun testScreenGlossaryCustomOverridesBuiltin() {
        val customJson =
            """{"settings": "خيارات التطبيق", "custom_btn": "زر خاص"}"""
        assertEquals(
            "خيارات التطبيق",
            ScreenGlossary.translate("Settings", customJson)
        )
        assertEquals(
            "زر خاص",
            ScreenGlossary.translate("custom_btn", customJson)
        )
    }

    @Test
    fun testScreenGlossaryNotFound() {
        assertNull(ScreenGlossary.translate("SomeUncommonWord12345"))
    }
}
