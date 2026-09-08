package com.aymankhattab.nateq

import com.aymankhattab.nateq.util.LocaleUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات أدوات التوحيد اللغوي: تطبيع رموز اللغات/البلدان، كشف العربية،
 * تطبيع الأرقام الهندية/الفارسية، وكشف رموز التحقق (OTP) — كلها منطق
 * خالص (JVM) لا يتطلب أندرويد.
 */
class LocaleUtilsTest {

    // ===== تطبيع رموز اللغة (ISO-3 → ISO-2) =====

    @Test
    fun normalizeLanguageCode_iso3_knownMappings() {
        assertEquals("ar", LocaleUtils.normalizeLanguageCode("ara"))
        assertEquals("en", LocaleUtils.normalizeLanguageCode("eng"))
        assertEquals("fr", LocaleUtils.normalizeLanguageCode("fra"))
        assertEquals("de", LocaleUtils.normalizeLanguageCode("deu"))
        assertEquals("es", LocaleUtils.normalizeLanguageCode("spa"))
        assertEquals("it", LocaleUtils.normalizeLanguageCode("ita"))
        assertEquals("ru", LocaleUtils.normalizeLanguageCode("rus"))
        assertEquals("zh", LocaleUtils.normalizeLanguageCode("zho"))
        assertEquals("ja", LocaleUtils.normalizeLanguageCode("jpn"))
        assertEquals("ko", LocaleUtils.normalizeLanguageCode("kor"))
    }

    @Test
    fun normalizeLanguageCode_iso2_passedThrough() {
        // الرموز التي هي أصلاً ISO-2 (أو غير مقرونة) تمر كما هي بلا تحويل
        assertEquals("ar", LocaleUtils.normalizeLanguageCode("ar"))
        assertEquals("en", LocaleUtils.normalizeLanguageCode("en"))
        assertEquals("ur", LocaleUtils.normalizeLanguageCode("ur"))
    }

    @Test
    fun normalizeLanguageCode_caseInsensitive() {
        assertEquals("ar", LocaleUtils.normalizeLanguageCode("ARA"))
        assertEquals("en", LocaleUtils.normalizeLanguageCode("Eng"))
    }

    @Test
    fun normalizeLanguageCode_null_defaultsToArabic() {
        assertEquals("ar", LocaleUtils.normalizeLanguageCode(null))
    }

    // ===== تطبيع رموز البلد (ISO-3 → ISO-2) =====

    @Test
    fun normalizeCountryCode_iso3_knownMappings() {
        assertEquals("EG", LocaleUtils.normalizeCountryCode("EGY"))
        assertEquals("US", LocaleUtils.normalizeCountryCode("USA"))
        assertEquals("GB", LocaleUtils.normalizeCountryCode("GBR"))
        assertEquals("SA", LocaleUtils.normalizeCountryCode("SAU"))
        assertEquals("AE", LocaleUtils.normalizeCountryCode("ARE"))
        assertEquals("AU", LocaleUtils.normalizeCountryCode("AUS"))
        assertEquals("CA", LocaleUtils.normalizeCountryCode("CAN"))
        assertEquals("FR", LocaleUtils.normalizeCountryCode("FRA"))
        assertEquals("DE", LocaleUtils.normalizeCountryCode("DEU"))
        assertEquals("ES", LocaleUtils.normalizeCountryCode("ESP"))
        assertEquals("IT", LocaleUtils.normalizeCountryCode("ITA"))
    }

    @Test
    fun normalizeCountryCode_iso2_passedThrough() {
        assertEquals("EG", LocaleUtils.normalizeCountryCode("eg"))
        assertEquals("US", LocaleUtils.normalizeCountryCode("US"))
        assertEquals("SA", LocaleUtils.normalizeCountryCode("sa"))
    }

    @Test
    fun normalizeCountryCode_null_returnsNull() {
        assertEquals(null, LocaleUtils.normalizeCountryCode(null))
    }

    @Test
    fun normalizeCountryCode_caseInsensitive() {
        assertEquals("EG", LocaleUtils.normalizeCountryCode("egy"))
    }

    // ===== كشف العربية (المجال الأساسي والممتد) =====

    @Test
    fun containsArabic_basicRange() {
        assertTrue(LocaleUtils.containsArabic("مرحبا"))
        assertTrue(LocaleUtils.containsArabic("hello مرحبا"))
        assertTrue(LocaleUtils.containsArabic("التشكيلَ يَقَعُ هنا"))
    }

    @Test
    fun containsArabic_noArabic_false() {
        assertFalse(LocaleUtils.containsArabic("hello world"))
        assertFalse(LocaleUtils.containsArabic("123 456"))
        assertFalse(LocaleUtils.containsArabic(""))
    }

    @Test
    fun containsArabic_extendedA() {
        // U+08A0 وما حوله: امتداد العربية -A (حروف/حركات من نطاق 08A0–08FF)
        assertTrue(LocaleUtils.containsArabic("\u08A0"))
    }

    @Test
    fun containsArabic_strikesRanges() {
        // العربية الممتد-أ (0750–077F) ونماذج العرض (FB50–FDFF)
        assertTrue(LocaleUtils.containsArabic("\u0750"))
        assertTrue(LocaleUtils.containsArabic("\uFDFA")) // سلّم
    }

    @Test
    fun containsArabic_indicDigits_shouldBeArabicRangesOnly() {
        // الأرقام الشرقية (٠–٩) من نطاق العربية 0600–06FF تُعد عربيةً عندنا
        assertTrue(LocaleUtils.containsArabic("\u0661")) // ١
        // الأرقام الفارسية (۰–۹) 06F0–06F9 أيضاً داخل نطاق العربية الأساسي
        assertTrue(LocaleUtils.containsArabic("\u06F0"))
    }

    // ===== تطبيع الأرقام الهندية والشرقية =====

    @Test
    fun normalizeIndicDigits_arabicEastern() {
        assertEquals("0123456789", LocaleUtils.normalizeIndicDigits("\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669"))
    }

    @Test
    fun normalizeIndicDigits_persian() {
        assertEquals("0123456789", LocaleUtils.normalizeIndicDigits("\u06F0\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8\u06F9"))
    }

    @Test
    fun normalizeIndicDigits_devanagari() {
        assertEquals("0123456789", LocaleUtils.normalizeIndicDigits("\u0966\u0967\u0968\u0969\u096A\u096B\u096C\u096D\u096E\u096F"))
    }

    @Test
    fun normalizeIndicDigits_mixedPreservesOthers() {
        assertEquals("0845", LocaleUtils.normalizeIndicDigits("\u0660\u0668\u0664\u0665"))
        assertEquals("مرحبا 123", LocaleUtils.normalizeIndicDigits("مرحبا 123"))
    }

    @Test
    fun normalizeIndicDigits_empty() {
        assertEquals("", LocaleUtils.normalizeIndicDigits(""))
    }

    // ===== كشف رمز التحقق (OTP) =====

    @Test
    fun containsOtp_arabicKeyword() {
        assertTrue(LocaleUtils.containsOtp("رمز التحقق: 482913"))
        assertTrue(LocaleUtils.containsOtp("كود التفعيل 273455"))
        assertTrue(LocaleUtils.containsOtp("كلمة المرور المؤقتة 90217"))
        assertTrue(LocaleUtils.containsOtp("الرقم السري 762341"))
    }

    @Test
    fun containsOtp_englishKeyword() {
        assertTrue(LocaleUtils.containsOtp("Your OTP is 493721"))
        assertTrue(LocaleUtils.containsOtp("verification code 581239"))
        assertTrue("one time password يقع", LocaleUtils.containsOtp("one time password 199483"))
        assertTrue(LocaleUtils.containsOtp("passcode 437890"))
    }

    @Test
    fun containsOtp_indicDigits() {
        // الأرقام الشرقية تُطبَّع قبل الكشف فتتعرف على الكود
        assertTrue(LocaleUtils.containsOtp("رمز التحقق: \u0664\u0668\u0662\u0669\u0661\u0663"))
    }

    @Test
    fun containsOtp_lengthBoundaries() {
        // 4 خانات مسموحة
        assertTrue(LocaleUtils.containsOtp("رمز التحقق 4829"))
        // 8 خانات مسموحة
        assertTrue(LocaleUtils.containsOtp("رمز التحقق 48291367"))
        // 3 خانات مرفوضة (الكود قصير جداً)
        assertFalse(LocaleUtils.containsOtp("رمز التحقق 482"))
        // 9 خانات مرفوضة (الكود طويل جداً)
        assertFalse(LocaleUtils.containsOtp("رمز التحقق 482913673"))
    }

    @Test
    fun containsOtp_requiresBoth_verify() {
        // الرمز دون كلمة تحقق ليس OTP
        assertFalse(LocaleUtils.containsOtp("رقم الحساب 482913"))
        // كلمة التحقق دون رمز متجاور ليس OTP
        assertFalse(LocaleUtils.containsOtp("رمز التحقق"))
        // 4–8 أرقام داخلة في عدد أطول (بلا حد كلمة) تُعتبر جزءاً — لكن العبارة
        // «تحقق» بلا رقم فعلي متجاور مضبوط مرفوضة أصلاً.
        assertFalse(LocaleUtils.containsOtp(""))
    }

    @Test
    fun containsOtp_matchesAnyStandaloneNumberInRange() {
        // أي رقم مستقل بطول 4–8 يُعتمد بجوار الكلمة، مهما كان تركيبه
        assertTrue(LocaleUtils.containsOtp("رمز التحقق 4829001"))
        assertTrue(LocaleUtils.containsOtp("رمز التحقق 450000"))
    }

    @Test
    fun containsOtp_numberAdjacentInsideLongerNotMatchedAsCode() {
        // «ora 482913673» طوله 9 فلا يُكشف (حد {4,8})
        assertFalse(LocaleUtils.containsOtp("رمز التحقق 482913673"))
        // الأرقام الملتصقة بالكلمة بلا مسافة لا تنطبق عليها «رقم متجاور» مستقلة
        assertFalse(LocaleUtils.containsOtp("مرحبا رمزتحقق"))
    }

    @Test
    fun containsOtp_blank() {
        assertFalse(LocaleUtils.containsOtp("   "))
    }

    @Test
    fun containsOtp_advancedKeywords() {
        assertTrue(LocaleUtils.containsOtp("رمز الأمان 482913"))
        assertTrue(LocaleUtils.containsOtp("كود الأمان 273455"))
        assertTrue(LocaleUtils.containsOtp("رمز التأكيد 762341"))
        assertTrue(LocaleUtils.containsOtp("كود التأكيد 90217"))
        assertTrue(LocaleUtils.containsOtp("الرمز السري 199483"))
        assertTrue(LocaleUtils.containsOtp("Your confirmation code is 581239"))
    }

    @Test
    fun containsOtp_separatedCodeLayouts() {
        // فواصل شرطية أو مسافات بين خانات الكود تُكشف ضمن حدود 4-8 خانات
        assertTrue(LocaleUtils.containsOtp("رمز التأكيد 123-456"))
        assertTrue(LocaleUtils.containsOtp("كود التأكيد 1234 5678"))
        assertTrue(LocaleUtils.containsOtp("رمز التحقق G-123456"))
        assertTrue(LocaleUtils.containsOtp("رمز الأمان 12-34-56"))
    }

    @Test
    fun containsOtp_separatedStillRequiresBoth() {
        // الرقم المفصول دون كلمة تحقق ليس OTP — تبقى قاعدة الشرطين
        assertFalse(LocaleUtils.containsOtp("المبلغ 123-456"))
        assertFalse(LocaleUtils.containsOtp("التاريخ 10-23-1987"))
        // الكلمة دون رقم متجاور حقيقي ليست OTP
        assertFalse(LocaleUtils.containsOtp("رمز التأكيد"))
    }
}