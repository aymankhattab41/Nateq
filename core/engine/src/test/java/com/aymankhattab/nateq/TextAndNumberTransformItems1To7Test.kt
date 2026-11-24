package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.pipeline.DateStep
import com.aymankhattab.nateq.engine.pipeline.NumberStep
import com.aymankhattab.nateq.engine.pipeline.RomanNumeralStep
import com.aymankhattab.nateq.engine.pipeline.TimeStep
import com.aymankhattab.nateq.engine.pipeline.UnitStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبارات انحدار حزمة تحويل النص والأرقام (الأمر 1 من 6: البنود 1 إلى 7).
 */
class TextAndNumberTransformItems1To7Test {

    // ─── البند 1: أرقام الإصدارات في NumberStep ───
    @Test
    fun item1_versionMultiPartsAndContext() {
        assertEquals(
            "واحد فاصلة ستة فاصلة واحد",
            NumberStep.apply("1.6.1")
        )
        assertEquals(
            "واحد فاصلة ستة فاصلة مائة وأربعة وستون",
            NumberStep.apply("1.6.164")
        )
        assertEquals(
            "version واحد فاصلة ستة فاصلة مائة وأربعة وستون",
            NumberStep.apply("version 1.6.164")
        )
        assertEquals(
            "إصدار واحد فاصلة ستة فاصلة مائة وأربعة وستون",
            NumberStep.apply("إصدار 1.6.164")
        )
        assertEquals(
            "عشرة فاصلة عشرون فاصلة ثلاثمائة",
            NumberStep.apply("10.20.300")
        )
        // الأعداد العشرية وفواصل الآلاف وعناوين IP لا تُكسر
        assertEquals(
            "ألف ومائتان وأربعة وثلاثون فاصلة خمسة ستة",
            NumberStep.apply("1,234.56")
        )
        assertEquals(
            "ثلاثة فاصلة واحد أربعة واحد",
            NumberStep.apply("3.141")
        )
        assertEquals(
            "three point one four one",
            NumberStep.applyEnglish("3.141")
        )
        assertEquals(
            "192.168.1.1",
            NumberStep.apply("192.168.1.1")
        )
        assertEquals(
            "مليون ومائتان وأربعة وثلاثون ألفاً وخمسمائة وسبعة وستون",
            NumberStep.apply("1.234.567")
        )
    }

    // ─── البند 2: حدود الوقت في TimeStep ───
    @Test
    fun item2_timeBoundariesAndUrls() {
        // لا يطابق من وسط رقم أطول
        assertEquals("123:45", TimeStep.apply("123:45"))
        assertEquals("12:456", TimeStep.apply("12:456"))
        assertEquals("12:45:678", TimeStep.apply("12:45:678"))
        // لا يفسد عناوين URL
        assertEquals(
            "http://example.com:8080",
            TimeStep.apply("http://example.com:8080")
        )
        assertEquals(
            "https://example.com/12:45",
            TimeStep.apply("https://example.com/12:45")
        )
        // أوقات صحيحة وسط الجمل
        assertEquals(
            "الوقت الثانية والنصف مساءً الآن",
            TimeStep.apply("الوقت 14:30 الآن")
        )
        assertEquals(
            "الساعة الواحدة إلا ربع مساءً",
            TimeStep.apply("الساعة 12:45")
        )
        // قيم ثوانٍ غير صالحة تُترك دون مساس
        assertEquals("14:30:99", TimeStep.apply("14:30:99"))
    }

    // ─── البند 3: تقريب الساعة لصيغة إلا وعبر منتصف الليل ───
    @Test
    fun item3_timeOmissionAndMidnightCrossing() {
        assertEquals(
            "الثانية عشرة إلا ربع بعد منتصف الليل",
            TimeStep.apply("23:45")
        )
        assertEquals(
            "الثانية عشرة إلا عشر دقائق بعد منتصف الليل",
            TimeStep.apply("23:50")
        )
        assertEquals(
            "الثانية عشرة بعد منتصف الليل",
            TimeStep.apply("00:00")
        )
        assertEquals(
            "الثانية عشرة إلا عشر دقائق ظهراً",
            TimeStep.apply("11:50")
        )
        assertEquals(
            "الثانية عشرة ظهراً",
            TimeStep.apply("12:00")
        )
        assertEquals(
            "الحادية عشرة خمسون دقيقة عشر ثوانٍ مساءً",
            TimeStep.apply("23:50:10")
        )
    }

    // ─── البند 4: التحقق الكامل من صحة التاريخ في DateStep ───
    @Test
    fun item4_gregorianDateFullValidation() {
        // قيم غير صالحة تُترك دون تحويل مضلل
        assertEquals("2023-02-29", DateStep().apply("2023-02-29"))
        assertEquals("31/04/2024", DateStep().apply("31/04/2024"))
        assertEquals("1900-02-29", DateStep().apply("1900-02-29"))
        // قيم صحيحة
        assertEquals(
            "تسعة وعشرون فبراير ألفان وأربعة وعشرون",
            DateStep().apply("2024-02-29")
        )
        assertEquals(
            "تسعة وعشرون فبراير ألفان",
            DateStep().apply("2000-02-29")
        )
        // صيغ اليوم/الشهر/السنة والسنة/الشهر/اليوم
        assertEquals(
            "تسعة وعشرون فبراير ألفان وأربعة وعشرون",
            DateStep().apply("29/02/2024")
        )
        assertEquals("29/02/2023", DateStep().apply("29/02/2023"))
    }

    // ─── البند 5: قواعد الترتيب والطرح والتكرار في RomanNumeralStep ───
    @Test
    fun item5_romanOrderAndSubtractionRules() {
        // أشكال صحيحة مع مؤشر صريح
        assertEquals("الفصل أربعة", RomanNumeralStep.apply("الفصل IV"))
        assertEquals("الفصل تسعة", RomanNumeralStep.apply("الفصل IX"))
        assertEquals("الفصل أربعون", RomanNumeralStep.apply("الفصل XL"))
        assertEquals("الفصل تسعون", RomanNumeralStep.apply("الفصل XC"))
        assertEquals("الفصل أربعمائة", RomanNumeralStep.apply("الفصل CD"))
        assertEquals("الفصل تسعمائة", RomanNumeralStep.apply("الفصل CM"))
        // تراكيب غير قانونية تُرفض دون تحويل
        assertEquals("chapter IL", RomanNumeralStep.apply("chapter IL"))
        assertEquals("chapter IC", RomanNumeralStep.apply("chapter IC"))
        assertEquals("chapter IIII", RomanNumeralStep.apply("chapter IIII"))
        assertEquals("chapter VV", RomanNumeralStep.apply("chapter VV"))
    }

    // ─── البند 6: أولوية السياق الصريح للرقم الروماني ───
    @Test
    fun item6_romanExplicitIndicatorPriority() {
        assertEquals("chapter واحد", RomanNumeralStep.apply("chapter I"))
        assertEquals("الفصل واحد", RomanNumeralStep.apply("الفصل I"))
        assertEquals("chapter اثنان", RomanNumeralStep.apply("chapter II"))
        assertEquals(
            "الفصل ألف وتسعة",
            RomanNumeralStep.apply("الفصل MIX")
        )
        // كلمات إنجليزية عادية عند غياب المؤشر الصريح
        assertEquals("I am here", RomanNumeralStep.apply("I am here"))
        assertEquals(
            "a mix of colors",
            RomanNumeralStep.apply("a mix of colors")
        )
        assertEquals("play the CD", RomanNumeralStep.apply("play the CD"))
        assertEquals("my ID number", RomanNumeralStep.apply("my ID number"))
    }

    // ─── البند 7: التمييز بين البت والبايت ومعدلاتها في UnitStep ───
    @Test
    fun item7_unitBitVsByteAndRates() {
        assertEquals("خمسة ميجابايتات", UnitStep.apply("5 MB"))
        assertEquals("خمسة ميجابتات", UnitStep.apply("5 Mb"))
        assertEquals("خمسة كيلوبايتات", UnitStep.apply("5 KB"))
        assertEquals("خمسة كيلوبتات", UnitStep.apply("5 Kb"))
        assertEquals(
            "خمسة ميجابايتات في الثانية",
            UnitStep.apply("5 MB/s")
        )
        assertEquals(
            "خمسة ميجابتات في الثانية",
            UnitStep.apply("5 Mb/s")
        )
        assertEquals(
            "خمسة كيلوبايتات في الثانية",
            UnitStep.apply("5 KB/s")
        )
        assertEquals(
            "خمسة كيلوبتات في الثانية",
            UnitStep.apply("5 Kb/s")
        )
        // الأسماء العربية ووحدات البت والبايت
        assertEquals("خمسة ميجابايتات", UnitStep.apply("5 ميجابايت"))
        assertEquals("خمسة ميجابتات", UnitStep.apply("5 ميجابت"))
        assertEquals("خمسة كيلوبايتات", UnitStep.apply("5 كيلوبايت"))
        assertEquals("خمسة كيلوبتات", UnitStep.apply("5 كيلوبت"))
        assertEquals("خمسة بايتات", UnitStep.apply("5 بايت"))
        assertEquals("خمسة بتات", UnitStep.apply("5 بت"))
    }
}
