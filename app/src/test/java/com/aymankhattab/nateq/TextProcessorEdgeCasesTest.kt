package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حالات حافة إضافية في TextProcessor تكمّل ما تغطّيه TextProcessorTest الأساسي:
 * الروابط (URLs)، توافق العدد والمعدود للوحدات والعملات، والكسور العشرية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextProcessorEdgeCasesTest {

    private lateinit var processor: TextProcessor

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        processor = TextProcessor(context)
    }

    // ═══════════════════════ الروابط (URLs) ═══════════════════════

    @Test
    fun url_https_simplifiedToDomain() {
        assertEquals(
            "تفضل بزيارة موقع example",
            processor.process("تفضل بزيارة https://example.com", "ar")
        )
    }

    @Test
    fun url_www_removesPath() {
        assertEquals(
            "تفقد موقع google",
            processor.process("تفقد www.google.com/path?q=1", "ar")
        )
    }

    @Test
    fun url_http_removesPort() {
        assertEquals(
            "رابط موقع site",
            processor.process("رابط http://site.net:8080/x", "ar")
        )
    }

    @Test
    fun url_nonArabicContext_notProcessed() {
        // السياق الإنجليزي لا يمر عبر خط المعالجة الثقيلة
        assertEquals(
            "Check https://example.com now",
            processor.process("Check https://example.com now", "en")
        )
    }

    @Test
    fun url_noSchemeNoWww_notMatched() {
        // يمين بدون http:// أو www. لا يُعتبر رابطاً
        assertEquals(
            "visit example.com",
            processor.process("visit example.com", "ar")
        )
    }

    // ═══════════════════════ توافق العدد والمعدود ═══════════════════════

    @Test
    fun unit_teensMasculine() {
        assertEquals("أحد عشر كيلوغرام", processor.process("11 kg", "ar"))
    }

    @Test
    fun unit_teensFeminine() {
        assertEquals("اثنتي عشرة ساعة", processor.process("12 س", "ar"))
    }

    @Test
    fun unit_compoundFeminine() {
        assertEquals("أربع عشرة سنة", processor.process("14 سنة", "ar"))
    }

    @Test
    fun unit_compoundFeminine25() {
        assertEquals("خمس وعشرون سنة", processor.process("25 سنة", "ar"))
    }

    @Test
    fun unit_hundredMasculine() {
        assertEquals("مائة كيلومتر", processor.process("100 km", "ar"))
    }

    @Test
    fun unit_twoHundredMasculine() {
        assertEquals("مائتان كيلومتر", processor.process("200 km", "ar"))
    }

    // ═══════════════════════ توافق العملات ═══════════════════════

    @Test
    fun currency_dualEuro() {
        assertEquals("يوروان", processor.process("2€", "ar"))
    }

    @Test
    fun currency_dualOmani() {
        assertEquals("ريالان عمانيان", processor.process("ر.ع 2", "ar"))
    }

    @Test
    fun currency_thousandsWithFraction() {
        assertEquals(
            "ألف دولار وخمسة وسبعون سنت",
            processor.process("$1,000.75", "ar")
        )
    }

    @Test
    fun currency_codeUsd() {
        assertEquals("مائة دولار أمريكي", processor.process("USD 100", "ar"))
    }

    @Test
    fun currency_codeSaudiaRiyal() {
        assertEquals("خمسمائة ريال سعودي", processor.process("SAR 500", "ar"))
    }

    // ═══════════════════════ الكسور العشرية المتقدمة ═══════════════════════

    @Test
    fun decimal_fractionWithLeadingZero() {
        assertEquals("ثلاثة فاصلة صفر خمسة", processor.process("3.05", "ar"))
    }

    @Test
    fun decimal_fractionThreeDigits() {
        assertEquals("ثلاثة فاصلة واحد أربعة واحد", processor.process("3.141", "ar"))
    }

    @Test
    fun decimal_negativeFraction() {
        assertEquals("ناقص واحد ونصف", processor.process("-1.5", "ar"))
    }

    @Test
    fun decimal_half() {
        assertEquals("واحد ونصف", processor.process("1.5", "ar"))
    }

    @Test
    fun decimal_quarter() {
        assertEquals("اثنان وربع", processor.process("2.25", "ar"))
    }

    @Test
    fun decimal_zeroAndThreeQuarters() {
        assertEquals("ثلاثة أرباع", processor.process("0.75", "ar"))
    }

    // ═══════════════════════ الأرقام الرومانية عبر المعالج ═══════════════════════

    @Test
    fun roman_largeNumber_withIndicator() {
        assertEquals(
            "الفصل ألفان وأربعة وعشرون",
            processor.process("الفصل MMXXIV", "ar")
        )
    }

    @Test
    fun roman_mixedWithArabic() {
        assertEquals("الفصل خمسة", processor.process("الفصل V", "ar"))
    }
}