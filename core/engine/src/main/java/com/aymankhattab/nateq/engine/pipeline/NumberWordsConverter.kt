package com.aymankhattab.nateq.engine.pipeline

import java.math.BigDecimal

/**
 * محرك «رقم → كلمات عربية» المستقل عن معالجة النصوص، يدعم حتى التريليونات
 * والكسور العشرية. نُقل كاملاً من TextProcessor ليُعاد استخدامه من خطوات
 * عدة (التواريخ/العملات/الوحدات/الرومانية/الأرقام) دون استنساخ المنطق.
 */
internal object NumberWordsConverter {

    // كلمات الأعداد (آحاد/مراهقين/عشرات/مئات) — مرجعية مشتركة بين
    // المسارين الطويل (Long) والعشري (String).
    private val UNITS_WORDS = arrayOf("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة")
    private val TEENS_WORDS = arrayOf("عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر")
    private val TENS_WORDS = arrayOf("", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون")
    private val HUNDREDS_WORDS = arrayOf("", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة")

    /** تحويل رقم لكلمات عربية (يدعم حتى التريليونات، والكسور العشرية). */
    fun numberToWords(number: Number): String {
        // معالجة الكسور العشرية: فصل الجزء الصحيح والعشري ونطق "فاصلة" ثم الأرقام
        // نعتمد التمثيل العشري المباشر (BigDecimal.valueOf) بدل طرح الجزء الصحيح
        // من الديبل — الطرح كان يُدخل أخطاء الفاصلة العائمة (0.14000000000000012)
        // وتفقد الأصفار البادئة/الوسطية للكسر (3.05 تُنطق سابقاً «ثلاثة فاصلة خمسة»).
        if (number is Double || number is Float) {
            val d = number.toDouble()
            // صفر فيصفر/لا نهائي: BigDecimal.valueOf يرفع استثناء نحوله لنطق
            // صريح بدل الانهيار (SignatureSynthesis يتعامل معها بأمان لاحقاً).
            if (d.isNaN()) return "ليس رقماً"
            if (d.isInfinite()) return if (d > 0) "ما لا نهاية" else "ناقص ما لا نهاية"
            val negative = d < 0
            val abs = Math.abs(d)
            // تمثيل عشري نظيف بدون أصفار ختامية (مثل 3.05 → "3.05").
            val plain = BigDecimal.valueOf(abs).stripTrailingZeros().toPlainString()
            val dot = plain.indexOf('.')
            if (dot < 0) {
                val integerOnly = plain.toLong()
                return if (negative) "ناقص ${numberToWords(integerOnly)}" else numberToWords(integerOnly)
            }
            val integerPart = plain.substring(0, dot).toLong()
            // خانات الكسر كما وردت (الأصفار البادئة والوسطية محفوظة: "05" ،"009").
            val decimalDigits = plain.substring(dot + 1)
            val base = if (negative) "ناقص " else ""
            val intWord = numberToWords(integerPart)
            // نطق طبيعي للكسور الشائعة: «ونصف/وربع/وثلاثة أرباع» بدل «فاصلة ...»
            return when (decimalDigits) {
                "5" -> if (integerPart == 0L) "${base}نصف" else "$base$intWord ونصف"
                "25" -> if (integerPart == 0L) "${base}ربع" else "$base$intWord وربع"
                "75" -> if (integerPart == 0L) "${base}ثلاثة أرباع" else "$base$intWord وثلاثة أرباع"
                // غيرها: نطق الخانات رقماً رقماً مع إبقاء الأصفار («05» → صفر خمسة)
                else -> "$base$intWord فاصلة " + decimalDigits
                    .map { digit -> numberToWords(digit.toString().toLong()) }
                    .joinToString(" ")
            }
        }

        val num = number.toLong()
        if (num == 0L) return "صفر"
        if (num < 0L) {
            // -Long.MIN_VALUE يفيض (قيمته 2^63 خارج المدى الطويل الموجب)؛ ننطقه
            // عبر تمثيله العشري الصريح بدل نفيٍّ يفيض فلا يتجمّد ولا يغرق.
            if (num == Long.MIN_VALUE) {
                return "ناقص ${positiveWordsFromDecimal("9223372036854775808")}"
            }
            return "ناقص ${numberToWords(-num)}"
        }
        return positiveWordsFromDecimal(num.toString())
    }

    /** تحويل تمثيل عشري موجب (أرقام فقط) إلى كلمات عربية حتى الكوينتيليون. */
    private fun positiveWordsFromDecimal(s: String): String {
        if (s.all { it == '0' }) return "صفر"
        val groups = mutableListOf<Int>()
        var i = s.length
        while (i > 0) {
            val start = (i - 3).coerceAtLeast(0)
            groups.add(s.substring(start, i).toInt())
            i = start
        }
        var result = ""
        var groupIndex = 0
        for (group in groups) {
            if (group != 0) {
                val groupText = convertHundreds(group)
                val scaleText = scaleForGroup(groupIndex, group, groupText)
                val part = if (groupIndex == 0) groupText else scaleText
                result = if (result.isEmpty()) part else "$part و$result"
            }
            groupIndex++
        }
        return result
    }

    /** مقياس مجموعة الأرقام (آلاف/ملايين/مليارات/تريليونات/كوادريليون/كوينتيليون)
     *  مع تمييزٍ نحوي صحيح:
     *  - ساكن 1/2 ← مفرد/مثنى («ألف»، «ألفان»)،
     *  - 3–10 ← جمع («خمسة آلاف»)،
     *  - 11–99 ← مفرد منصوب («خمسة عشر ألفاً»)،
     *  - 100 ← إضافة مجرورة («مائة ألف»)، 200 ← «مائتا ألف» (حذف نون المثنى)،
     *  - مئات مضبوطة ← «ثلاثمائة ألف»، ومئات بآحاد 3–10 ← «مائة وخمسة آلاف». */
    private fun scaleForGroup(groupIndex: Int, group: Int, groupText: String): String {
        if (groupIndex == 0) return ""
        val scale = when (groupIndex) {
            1 -> ScaleWords("ألف", "ألفان", "آلاف", "ألفاً", "ألف")
            2 -> ScaleWords("مليون", "مليونان", "ملايين", "مليوناً", "مليون")
            3 -> ScaleWords("مليار", "ملياران", "مليارات", "ملياراً", "مليار")
            4 -> ScaleWords("تريليون", "تريليونان", "تريليونات", "تريليوناً", "تريليون")
            5 -> ScaleWords("كوادريليون", "كوادريليونان", "كوادريليونات", "كوادريليوناً", "كوادريليون")
            6 -> ScaleWords("كوينتيليون", "كوينتيليونان", "كوينتيليونات", "كوينتيليوناً", "كوينتيليون")
            else -> return ""
        }
        if (group in 1..2) return if (group == 1) scale.one else scale.two
        if (group in 3..10) return "$groupText ${scale.plural}"
        if (group in 11..99) return "$groupText ${scale.accusative}"
        val remainder = group % 100
        return when {
            group == 100 -> "مائة ${scale.inHundred}"
            group == 200 -> "مائتا ${scale.inHundred}"
            remainder == 0 -> "${convertHundreds(group)} ${scale.inHundred}"
            remainder in 3..10 -> "$groupText ${scale.plural}"
            else -> "$groupText ${scale.accusative}"
        }
    }

    /** تحويل جزء عددي (0–999) إلى كلمات. */
    private fun convertHundreds(n: Int): String {
        if (n == 0) return ""
        if (n < 10) return UNITS_WORDS[n]
        if (n < 20) return TEENS_WORDS[n - 10]
        if (n < 100) {
            val ten = n / 10
            val unit = n % 10
            return if (unit == 0) {
                TENS_WORDS[ten]
            } else {
                compoundTwoDigits(unit, ten)
            }
        }
        val hundred = n / 100
        val remainder = n % 100
        return if (remainder == 0) HUNDREDS_WORDS[hundred] else "${HUNDREDS_WORDS[hundred]} و${convertHundreds(remainder)}"
    }

    /** مساعد العدد المركّب (21–99): «أحد وعشرون»، «اثنان وثلاثون»، «خمسة وأربعون». */
    private fun compoundTwoDigits(unit: Int, ten: Int): String = when (unit) {
        1 -> "أحد و${TENS_WORDS[ten]}"
        2 -> "اثنان و${TENS_WORDS[ten]}"
        else -> "${UNITS_WORDS[unit]} و${TENS_WORDS[ten]}"
    }

    /**
     * لفظ العدد (3–10) مع مراعاة قاعدة العدد في العربية:
     * العدد يأخذ صيغة مؤنثة مع المعدود المذكر (خمسة كيلومترات)
     * وصيغة مذكرة مع المعدود المؤنث (خمس دقائق).
     */
    fun unitNumberWord(digit: Int, isFeminine: Boolean): String {
        // الفهرس يعادل الرقم بالضبط (لا انزياح): كان المصفوفة تحذف منزلة
        // (خمسة → «ستة أمتار») لغياب العنصر الأول.
        val forMasculine = arrayOf("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة", "عشرة")
        val forFeminine = arrayOf("", "واحدة", "اثنتان", "ثلاث", "أربع", "خمس", "ست", "سبع", "ثمان", "تسع", "عشر")
        val table = if (isFeminine) forFeminine else forMasculine
        return if (digit in 3..10) table[digit] else ""
    }
}