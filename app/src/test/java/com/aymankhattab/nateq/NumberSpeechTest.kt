package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.NumberSpeech
import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات نطق الأرقام — جزء كتابي نقي يعمل عبر JVM بلا أجهزة. */
class NumberSpeechTest {

    @Test
    fun arabicWords_basicUnits() {
        assertEquals("صفر", NumberSpeech.toArabicWords(0))
        assertEquals("واحدة", NumberSpeech.toArabicWords(1))
        assertEquals("عشرون", NumberSpeech.toArabicWords(20))
        assertEquals("خمس وثلاثون", NumberSpeech.toArabicWords(35))
    }

    @Test
    fun arabicWords_beyondFiftyNine() {
        assertEquals("ثماني وستون", NumberSpeech.toArabicWords(68))
        assertEquals("تسعون", NumberSpeech.toArabicWords(90))
        assertEquals("مئة", NumberSpeech.toArabicWords(100))
    }

    @Test
    fun englishWords_beyondFiftyNine() {
        assertEquals("sixty eight", NumberSpeech.toEnglishWords(68))
        assertEquals("ninety", NumberSpeech.toEnglishWords(90))
        assertEquals("one hundred", NumberSpeech.toEnglishWords(100))
    }

    @Test
    fun toOrdinalHourWord_arabicFeminineOrdinal() {
        // الساعة تُنطق دائماً ترتيبية مؤنثة معرّفة بأل لكل الساعات 1–12
        assertEquals("الواحدة", NumberSpeech.toOrdinalHourWord(1))
        assertEquals("الثانية", NumberSpeech.toOrdinalHourWord(2))
        assertEquals("الثالثة", NumberSpeech.toOrdinalHourWord(3))
        assertEquals("الرابعة", NumberSpeech.toOrdinalHourWord(4))
        assertEquals("الخامسة", NumberSpeech.toOrdinalHourWord(5))
        assertEquals("السادسة", NumberSpeech.toOrdinalHourWord(6))
        assertEquals("السابعة", NumberSpeech.toOrdinalHourWord(7))
        assertEquals("الثامنة", NumberSpeech.toOrdinalHourWord(8))
        assertEquals("التاسعة", NumberSpeech.toOrdinalHourWord(9))
        assertEquals("العاشرة", NumberSpeech.toOrdinalHourWord(10))
        assertEquals("الحادية عشرة", NumberSpeech.toOrdinalHourWord(11))
        assertEquals("الثانية عشرة", NumberSpeech.toOrdinalHourWord(12))
    }

    @Test
    fun formatByMode_singleDigits() {
        assertEquals(
            "واحد اثنان ثلاثة",
            NumberSpeech.formatByMode(1, 123, false)
        )
    }

    @Test
    fun formatByMode_grouping() {
        assertEquals(
            "اثنا عشر, ثلاثة وثلاثون, خمسة وأربعون",
            NumberSpeech.formatByMode(2, 123345, false)
        )
    }

    @Test
    fun formatByMode_english() {
        assertEquals("one two", NumberSpeech.formatByMode(1, 12, true))
    }

    @Test
    fun arabicWords_largeNumbers() {
        assertEquals("ألف", NumberSpeech.toArabicWords(1000))
        assertEquals("ألفان", NumberSpeech.toArabicWords(2000))
        assertEquals("ألف وواحدة", NumberSpeech.toArabicWords(1001))
        assertEquals("خمسة آلاف", NumberSpeech.toArabicWords(5000))
        assertEquals("ألف ومئة", NumberSpeech.toArabicWords(1100))
    }

    @Test
    fun arabicWords_hundreds() {
        assertEquals("مائتان", NumberSpeech.toArabicWords(200))
        assertEquals("ثلاثمائة", NumberSpeech.toArabicWords(300))
        assertEquals("أربعمائة", NumberSpeech.toArabicWords(400))
        assertEquals("خمسمائة", NumberSpeech.toArabicWords(500))
        assertEquals("ستمائة", NumberSpeech.toArabicWords(600))
        assertEquals("سبعمائة", NumberSpeech.toArabicWords(700))
        assertEquals("ثمانمائة", NumberSpeech.toArabicWords(800))
        assertEquals("تسعمائة", NumberSpeech.toArabicWords(900))
        assertEquals("مئة وخمس", NumberSpeech.toArabicWords(105))
        assertEquals("أربعمائة وعشر", NumberSpeech.toArabicWords(410))
        assertEquals(
            "خمسمائة وسبعة وسبعون",
            NumberSpeech.toArabicWords(577, isFeminine = false)
        )
    }

    @Test
    fun arabicWords_masculine() {
        assertEquals("واحد", NumberSpeech.toArabicWords(1, isFeminine = false))
        assertEquals("اثنان", NumberSpeech.toArabicWords(2, isFeminine = false))
        assertEquals("خمسة", NumberSpeech.toArabicWords(5, isFeminine = false))
        assertEquals(
            "واحد وخمسون",
            NumberSpeech.toArabicWords(51, isFeminine = false)
        )
        assertEquals(
            "أحد عشر",
            NumberSpeech.toArabicWords(11, isFeminine = false)
        )
    }

    @Test
    fun arabicWords_feminineCompound() {
        // مؤنث مركب فوق العشرات
        assertEquals(
            "إحدى وخمسون",
            NumberSpeech.toArabicWords(51, isFeminine = true)
        )
        assertEquals(
            "اثنتان وثلاثون",
            NumberSpeech.toArabicWords(32, isFeminine = true)
        )
        assertEquals(
            "خمس وستون",
            NumberSpeech.toArabicWords(65, isFeminine = true)
        )
    }

    @Test
    fun arabicWords_feminineTwo_nominative() {
        // مؤنث العدد 2 في موضع الرفع: «اثنتان» لا «اثنتين» (نصباً/جراً).
        assertEquals("اثنتان", NumberSpeech.toArabicWords(2, isFeminine = true))
        assertEquals(
            "اثنتا عشرة",
            NumberSpeech.toArabicWords(12, isFeminine = true)
        )
        assertEquals(
            "مئة واثنتان",
            NumberSpeech.toArabicWords(102, isFeminine = true)
        )
        assertEquals(
            "اثنتان وعشرون",
            NumberSpeech.toArabicWords(22, isFeminine = true)
        )
    }

    @Test
    fun arabicWords_thousandsGenitive() {
        // آحاد 1–2 معطوفة على مائة تجرّ التمييز: «مائة وواحد ألف» لا ألفاً.
        assertEquals(
            "مائة وواحد ألف",
            NumberSpeech.toArabicWords(101000)
        )
        assertEquals(
            "مائة واثنان ألف",
            NumberSpeech.toArabicWords(102000)
        )
    }

    @Test
    fun arabicWords_thousandsTens_remainAccusative() {
        // فوق العشرات يبقى منصوباً (محدّد: 111000).
        assertEquals(
            "مائة وأحد عشر ألفاً",
            NumberSpeech.toArabicWords(111000)
        )
    }

    @Test
    fun arabicWords_tenThousand_doesNotCrash() {
        // 10,000 كانت تنفجر من onesM[10] (خارج حدود المصفوفة).
        assertEquals("عشرة آلاف", NumberSpeech.toArabicWords(10000))
    }

    @Test
    fun arabicWords_tenMillion_doesNotCrash() {
        // 10,000,000 كانت تنفجر من onesM[10] (خارج حدود المصفوفة).
        assertEquals("عشرة ملايين", NumberSpeech.toArabicWords(10000000))
    }

    @Test
    fun arabicWords_multiplesOfHundredMillion_genitive() {
        // مئة مضبوطة أو بآحاد 1–2 تبقى مجرورة: «مائة مليون» لا مليوناً.
        assertEquals("مائة مليون", NumberSpeech.toArabicWords(100000000))
        assertEquals("مائة وواحد مليون", NumberSpeech.toArabicWords(101000000))
    }

    @Test
    fun arabicWords_billionTerminalHundreds_genitive() {
        // بند 3.4: «مائتان» النهائية في مركّب (ملايين/آلاف) تُجرّ النون:
        // 1_200_000_000 = «ألف ومائتا مليون» لا «ألف ومائتان مليون»،
        // و200_000 = «مائتا ألف» لا «مائتان ألف».
        assertEquals(
            "ألف ومائتا مليون",
            NumberSpeech.toArabicWords(1_200_000_000)
        )
        assertEquals("مائتا ألف", NumberSpeech.toArabicWords(200_000))
    }

    @Test
    fun englishWords_basicNumbers() {
        assertEquals("zero", NumberSpeech.toEnglishWords(0))
        assertEquals("one", NumberSpeech.toEnglishWords(1))
        assertEquals("twelve", NumberSpeech.toEnglishWords(12))
        assertEquals("thirteen", NumberSpeech.toEnglishWords(13))
        assertEquals("twenty", NumberSpeech.toEnglishWords(20))
        assertEquals("twenty one", NumberSpeech.toEnglishWords(21))
    }

    @Test
    fun englishWords_largeNumbers() {
        assertEquals("one thousand", NumberSpeech.toEnglishWords(1000))
        assertEquals(
            "two thousand five hundred",
            NumberSpeech.toEnglishWords(2500)
        )
        assertEquals(
            "nine thousand nine hundred ninety nine",
            NumberSpeech.toEnglishWords(9999)
        )
    }

    @Test
    fun englishWords_hundreds() {
        assertEquals("one hundred", NumberSpeech.toEnglishWords(100))
        assertEquals("one hundred five", NumberSpeech.toEnglishWords(105))
        assertEquals(
            "three hundred forty one",
            NumberSpeech.toEnglishWords(341)
        )
    }

    @Test
    fun formatByMode_groupingWithLeadingGroup() {
        // ungrouped تقسيم المجموعات مع مجموعة أولى أصغر (123 → 1, 23)
        assertEquals(
            "واحد, ثلاثة وعشرون",
            NumberSpeech.formatByMode(2, 123, false)
        )
        assertEquals(
            "one, twenty three",
            NumberSpeech.formatByMode(2, 123, true)
        )
    }

    @Test
    fun formatByMode_negativeNumbers() {
        assertEquals("سالب واحد", NumberSpeech.formatByMode(1, -1, false))
        assertEquals("negative one", NumberSpeech.formatByMode(1, -1, true))
    }

    @Test
    fun englishWords_longMinValue_doesNotCrash() {
        // Long.MAX_VALUE + 1L كان يفيض حسابياً فيصير -9223372036854775808
        // نصاً يحوي إشارة سالبة تخرّب تجزئة الثلاثيات (انهيار) — يُركَّب
        // الآن بنصٍّ صريح بلا إشارة فتُنطق القيمة كاملة.
        assertEquals(
            "minus nine quintillion two hundred twenty three quadrillion " +
                "three hundred seventy two trillion thirty six billion " +
                "eight hundred fifty four million seven hundred seventy " +
                "five thousand eight hundred eight",
            NumberSpeech.toEnglishWords(Long.MIN_VALUE)
        )
    }

    @Test
    fun englishWords_intMinValue_doesNotCrash() {
        // نفي Int.MIN_VALUE يفيض فيبقى سالباً — تُركَّب يدوياً (ملياران +
        // الباقي) فتنجو من الحلقة اللانهائية.
        assertEquals(
            "minus two billion one hundred forty seven million " +
                "four hundred eighty three thousand six hundred forty eight",
            NumberSpeech.toEnglishWords(Int.MIN_VALUE)
        )
    }

    @Test
    fun formatByMode_largeGrouping() {
        assertEquals(
            "مائة وثلاثة وعشرون, مائة وثلاثة وعشرون",
            NumberSpeech.formatByMode(3, 123123, false)
        )
    }

    @Test
    fun formatByMode_leadingZerosPreserved() {
        // الرمز 102 في التجميع الزوجي: «02» صفر بادئ لا يُفقد
        val expectedTwo = "واحد, صفر اثنان"
        assertEquals(expectedTwo, NumberSpeech.formatByMode(2, 102, false))
        assertEquals("one, zero two", NumberSpeech.formatByMode(2, 102, true))
        // تجميع ثلاثي لـ 1002: «002» ثلاثة أصفار بادئة
        assertEquals(
            "واحد, صفر صفر اثنان",
            NumberSpeech.formatByMode(3, 1002, false)
        )
        assertEquals(
            "one, zero zero two",
            NumberSpeech.formatByMode(3, 1002, true)
        )
    }

    @Test
    fun formatByMode_groupingUpToEightDigits() {
        // تجميع خماسي/ثماني يدعم 8 خانات بلا انهيار (كان محدوداً بـ 9999)
        assertEquals(
            "ألف ومائتان وأربعة وثلاثون, ستة وخمسون ألفاً" +
                " وسبعمائة وتسعة وثمانون",
            NumberSpeech.formatByMode(5, 123456789, false)
        )
        assertEquals(
            "اثنا عشر مليوناً وثلاثمائة وخمسة وأربعون ألفاً" +
                " وستمائة وثمانية وسبعون",
            NumberSpeech.formatByMode(8, 12345678, false)
        )
        assertEquals(
            "twelve million three hundred forty five thousand" +
            " six hundred seventy eight",
            NumberSpeech.formatByMode(8, 12345678, true)
        )
    }

    @Test
    fun formatByMode_decimalNumbers_mode1_mode5() {
        // اختبار الرقم العشري مثل 30496.00 بالمفرد والزوجي والخماسي
        assertEquals(
            "ثلاثة صفر أربعة تسعة ستة فاصلة صفر صفر",
            NumberSpeech.formatByMode(1, "30496.00", false)
        )
        assertEquals(
            "ثلاثون ألفاً وأربعمائة وستة وتسعون فاصلة صفر صفر",
            NumberSpeech.formatByMode(5, "30496.00", false)
        )
        assertEquals(
            "ثلاثة, صفر أربعة, ستة وتسعون فاصلة صفر صفر",
            NumberSpeech.formatByMode(2, "30496.00", false)
        )
        assertEquals(
            "thirty thousand four hundred ninety six point zero zero",
            NumberSpeech.formatByMode(5, "30496.00", true)
        )
    }

    @Test
    fun formatByMode_phoneNumbersFollowSelectedMode() {
        // أرقام الهواتف تتبع نمط القراءة المختار (مفردة/ثلاثية/رباعية..)
        assertEquals(
            "صفر واحد صفر واحد اثنان ثلاثة أربعة خمسة ستة سبعة ثمانية",
            NumberSpeech.formatByMode(1, "01012345678", false)
        )
        // ثلاثي: 01, 012, 345, 678
        assertEquals(
            "صفر واحد, صفر واحد اثنان, ثلاثمائة وخمسة وأربعون, " +
                "ستمائة وثمانية وسبعون",
            NumberSpeech.formatByMode(3, "01012345678", false)
        )
        // ثلاثي مع زائد: +20 11 5552442 -> 20, 115, 552, 442
        assertEquals(
            "زائد عشرون, مائة وخمسة عشر, خمسمائة واثنان وخمسون, " +
                "أربعمائة واثنان وأربعون",
            NumberSpeech.formatByMode(3, "+20 11 5552442", false)
        )
        // رباعي إنجليزي مع زائد: +20 11 5552442 -> 201, 1555, 2442
        assertEquals(
            "plus two hundred one, one thousand five hundred fifty five, " +
                "two thousand four hundred forty two",
            NumberSpeech.formatByMode(4, "+20 11 5552442", true)
        )
        // مفرد إنجليزي مع زائد
        assertEquals(
            "plus two zero one one five five five two four four two",
            NumberSpeech.formatByMode(1, "+20 11 5552442", true)
        )
    }
}