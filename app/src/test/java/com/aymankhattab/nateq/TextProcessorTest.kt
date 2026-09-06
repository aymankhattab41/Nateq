package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** اختبارات معالج النصوص الذكي (TextProcessor). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextProcessorTest {

    private lateinit var processor: TextProcessor

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        processor = TextProcessor(context)
    }

    @Test
    fun blankText_isReturned() {
        assertEquals("", processor.process("", "ar"))
        assertEquals("   ", processor.process("   ", "ar"))
    }

    @Test
    fun nonArabicText_isUnchanged() {
        assertEquals("hello world 123", processor.process("hello world 123", "en"))
        assertEquals("the number 42", processor.process("the number 42", "en-US"))
    }

    @Test
    fun plainArabic_isUnchanged() {
        // نص عربي صافٍ بلا أرقام يمر عبر fast-path دون تحويلات
        assertEquals("مرحبا بالعالم", processor.process("مرحبا بالعالم", "ar"))
    }

    @Test
    fun hijriDateConversion_disabled_usesGregorian() {
        // عند تعطيل الهجري: تاريخ ميلادي عادي
        val out = processor.process("15/06/2024", "ar")
        assertEquals("خمسة عشر يونيو ألفان وأربعة وعشرون", out)
    }

    @Test
    fun gregorianDate_ymdFormat() {
        val out = processor.process("2024/01/15", "ar")
        assertEquals("خمسة عشر يناير ألفان وأربعة وعشرون", out)
    }

    @Test
    fun time_arabicNatural() {
        // 14:30 → الثانية عشرة محوَّلة لـ 2 والنصف مساءً
        val out = processor.process("14:30", "ar")
        assertEquals("اثنان والنصف مساءً", out)
    }

    @Test
    fun phoneNumber_arabicContext_spokenDigitByDigit() {
        // السياق عربي (languageTag=ar) فتُنطق رقماً رقماً عربياً حتى لو كان النص أرقاماً فقط
        val out = processor.process("01001234567", "ar")
        assertEquals(
            "صفر واحد صفر صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة",
            out
        )
    }

    @Test
    fun phoneNumber_mixedArabicText_spokenDigitByDigit() {
        // حتى بوجود نص عربي حول الرقم
        val out = processor.process("اتصل بـ 01001234567", "ar")
        assertTrue(out.contains("صفر واحد صفر صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة"))
    }

    @Test
    fun phoneNumber_englishContext_leftForSpeechEngine() {
        // النص الإنجليزي يُعاد كما هو من process() دون تحويل أو نطق عربي
        val out = processor.process("Call 01001234567 now", "en-US")
        assertEquals("Call 01001234567 now", out)
    }

    @Test
    fun currencyAndNumber_arabic() {
        // «جنيه» في القاموس الشخصي → «جنيه مصري»
        val out = processor.process("100 جنيه", "ar")
        assertEquals("مائة جنيه مصري", out)
    }

    @Test
    fun importedTashkeel_removedInFastPath() {
        // التشكيل يُجرّد في fast-path وأنماط المطابقة
        val out = processor.process("كَيْفَ", "ar")
        assertEquals("كيف", out)
    }

    @Test
    fun emoji_removed() {
        val out = processor.process("مرحبا 😊", "ar")
        // يُنظّف الإيموجي؛ النص المتبقي يبقى أو يُرجَّع بعد تنظيف المسافات
        assertEquals("مرحبا", out)
    }
}