package com.aymankhattab.nateq

import com.aymankhattab.nateq.util.LanguageCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات [LanguageCode]: قيم الطوابع BCP-47 للغات الثابتة، وكواشف اللغة
 * المتسامحة مع الحالة، والحلّ العكسي — منطق خالص (JVM) بلا أندرويد.
 */
class LanguageCodeTest {

    @Test
    fun tags_areBcp47() {
        assertEquals("ar", LanguageCode.AR.tag)
        assertEquals("en", LanguageCode.EN.tag)
    }

    @Test
    fun isArabic_acceptsMappedAndRegionalTags() {
        assertTrue(LanguageCode.isArabic("ar"))
        assertTrue(LanguageCode.isArabic("ar-EG"))
        assertTrue(LanguageCode.isArabic("AR"))
        assertTrue(LanguageCode.isArabic("Ar-EG"))
    }

    @Test
    fun isArabic_rejectsNonArabic() {
        assertFalse(LanguageCode.isArabic("en"))
        assertFalse(LanguageCode.isArabic("fr"))
        assertFalse(LanguageCode.isArabic(""))
    }

    @Test
    fun isArabic_pureOneWordRejected() {
        // **بند 6.1:** القبول الصارم لـ ar|ara وامتداديهما؛ أي كلمة تبدأ
        // بـ ar* دون عربية تُرفض (كانت تُقبل سابقاً عبر startsWith).
        assertFalse(LanguageCode.isArabic("arise"))
        assertFalse(LanguageCode.isArabic("aroma"))
        assertFalse(LanguageCode.isArabic("around"))
        assertFalse(LanguageCode.isArabic("aro"))
        assertFalse(LanguageCode.isArabic("ary"))
        assertFalse(LanguageCode.isArabic("arz"))
    }

    @Test
    fun isArabic_acceptsAraEtymology() {
        // «ara» وبادئتها مقبولة (الصيغة الثنائية التاريخية للعربية).
        assertTrue(LanguageCode.isArabic("ara"))
        assertTrue(LanguageCode.isArabic("ara-EG"))
        assertTrue(LanguageCode.isArabic("ara_EG"))
    }

    @Test
    fun isEnglish_acceptsMappedAndRegionalTags() {
        assertTrue(LanguageCode.isEnglish("en"))
        assertTrue(LanguageCode.isEnglish("en-US"))
        assertTrue(LanguageCode.isEnglish("EN"))
    }

    @Test
    fun isEnglish_rejectsNonEnglish() {
        assertFalse(LanguageCode.isEnglish("ar"))
        assertFalse(LanguageCode.isEnglish("de"))
    }

    @Test
    fun isEnglish_pureOneWordRejected() {
        // **بند 6.1:** نظيرُ العربية بالإنجليزية — الرفض الصارم للبادئ
        // en* التي لا تمثل إنجليزية (england، ent…).
        assertFalse(LanguageCode.isEnglish("england"))
        assertFalse(LanguageCode.isEnglish("entity"))
        assertFalse(LanguageCode.isEnglish("enroute"))
        assertFalse(LanguageCode.isEnglish("engk"))
    }

    @Test
    fun isEnglish_acceptsEngEtymology() {
        assertTrue(LanguageCode.isEnglish("eng"))
        assertTrue(LanguageCode.isEnglish("eng-US"))
        assertTrue(LanguageCode.isEnglish("eng_GB"))
    }

    @Test
    fun fromTagOrNull_knownTags() {
        assertEquals(LanguageCode.AR, LanguageCode.fromTagOrNull("ar"))
        assertEquals(LanguageCode.EN, LanguageCode.fromTagOrNull("en"))
        assertEquals(LanguageCode.AR, LanguageCode.fromTagOrNull("AR"))
    }

    @Test
    fun fromTagOrNull_unknownReturnsNull() {
        assertNull(LanguageCode.fromTagOrNull("fr"))
        assertNull(LanguageCode.fromTagOrNull("ar-EG"))
        assertNull(LanguageCode.fromTagOrNull(""))
    }
}