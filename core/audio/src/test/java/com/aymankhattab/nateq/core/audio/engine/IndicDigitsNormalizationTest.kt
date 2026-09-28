package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.util.LocaleUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * توسيع تطبيع الأرقام الشرقية (بند 6-7): كانت الأنظمة الهندية الباقية
 * (بنغالية/تاميلية/تيلوغوية/ملايالامية/تايلندية/خميرية…) بلا تحويلٍ
 * فتصل للمحرك مكتوبةً بنظامها فلا تُقرأ أرقاماً — أصبحت تُطبع غربيةً
 * كالعربية-الهندية واللاتينية، مع الإبقاء على تحويل الفاصلتين.
 */
class IndicDigitsNormalizationTest {

    @Test
    fun arabicFamily_converts() {
        assertEquals(
            "0123",
            LocaleUtils.normalizeIndicDigits("\u0660\u0661\u0662\u0663")
        )
        assertEquals(
            "0123",
            LocaleUtils.normalizeIndicDigits("\u06F0\u06F1\u06F2\u06F3")
        )
        assertEquals(
            "0967",
            LocaleUtils.normalizeIndicDigits("\u0966\u096F\u096C\u096D")
        )
    }

    @Test
    fun otherIndicSystems_convertsToWestern() {
        // بنغالية: ০৯৪৭
        assertEquals(
            "0947",
            LocaleUtils.normalizeIndicDigits("\u09E6\u09EF\u09EA\u09ED")
        )
        // تاميلية: ௩௨௧
        assertEquals(
            "321",
            LocaleUtils.normalizeIndicDigits("\u0BE9\u0BE8\u0BE7")
        )
        // تيلوغوية: ౩౨౧
        assertEquals(
            "321",
            LocaleUtils.normalizeIndicDigits("\u0C69\u0C68\u0C67")
        )
        // ملايالامية: ൩൨൧
        assertEquals(
            "321",
            LocaleUtils.normalizeIndicDigits("\u0D69\u0D68\u0D67")
        )
        // تايلندية: ๓๒๑
        assertEquals(
            "321",
            LocaleUtils.normalizeIndicDigits("\u0E53\u0E52\u0E51")
        )
    }

    @Test
    fun arabicSeparators_converted() {
        assertEquals("1.5", LocaleUtils.normalizeIndicDigits("١٫٥"))
        assertEquals("1,500", LocaleUtils.normalizeIndicDigits("١٬٥٠٠"))
    }

    @Test
    fun plainLatin_untouched() {
        assertEquals("12 كيلو", LocaleUtils.normalizeIndicDigits("12 كيلو"))
    }
}