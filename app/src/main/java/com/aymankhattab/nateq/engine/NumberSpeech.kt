package com.aymankhattab.nateq.engine

/**
 * منطق نطق الأرقام بنمط التجميع المُختار (1=مفردة، 2=زوجي، 3..8=ثلاثي..ثماني)
 * وباللغة المطلوبة (عربية/إنجليزية).
 *
 * كائن نقيّ يُستخدم من TimeAnnouncementManager (النطق الفعلي) ومن شاشة
 * الإعدادات (معاينة نطق رقم) لضمان تطابق الناتج بين المعاينة والنطق الحقيقي.
 */
object NumberSpeech {

    /** تحويل رقم إلى كلمات إنجليزية (للأرقام المنفصلة) */
    fun toEnglishWords(number: Int): String {
        return when (number) {
            in 0..12 -> listOf(
                "zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "ten", "eleven", "twelve"
            )[number]
            in 13..19 -> listOf(
                "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
                "eighteen", "nineteen"
            )[number - 13]
            in 20..99 -> {
                val tens = listOf(
                    "twenty", "thirty", "forty", "fifty",
                    "sixty", "seventy", "eighty", "ninety"
                )[(number / 10) - 2]
                val ones = number % 10
                if (ones == 0) tens else "$tens ${toEnglishWords(ones)}"
            }
            100 -> "one hundred"
            else -> number.toString()
        }
    }

    /** تحويل رقم إلى كلمات عربية */
    fun toArabicWords(number: Int): String {
        return when (number) {
            0 -> "صفر"
            1 -> "واحدة"
            2 -> "اثنتين"
            3 -> "ثلاث"
            4 -> "أربع"
            5 -> "خمس"
            6 -> "ست"
            7 -> "سبع"
            8 -> "ثماني"
            9 -> "تسع"
            10 -> "عشر"
            11 -> "إحدى عشرة"
            12 -> "اثنتي عشرة"
            13 -> "ثلاث عشرة"
            14 -> "أربع عشرة"
            15 -> "خمس عشرة"
            16 -> "ست عشرة"
            17 -> "سبع عشرة"
            18 -> "ثماني عشرة"
            19 -> "تسع عشرة"
            20 -> "عشرون"
            21 -> "إحدى وعشرون"
            22 -> "اثنتان وعشرون"
            23 -> "ثلاث وعشرون"
            24 -> "أربع وعشرون"
            25 -> "خمس وعشرون"
            26 -> "ست وعشرون"
            27 -> "سبع وعشرون"
            28 -> "ثماني وعشرون"
            29 -> "تسع وعشرون"
            30 -> "ثلاثون"
            31 -> "إحدى وثلاثون"
            32 -> "اثنتان وثلاثون"
            33 -> "ثلاث وثلاثون"
            34 -> "أربع وثلاثون"
            35 -> "خمس وثلاثون"
            36 -> "ست وثلاثون"
            37 -> "سبع وثلاثون"
            38 -> "ثماني وثلاثون"
            39 -> "تسع وثلاثون"
            40 -> "أربعون"
            41 -> "إحدى وأربعون"
            42 -> "اثنتان وأربعون"
            43 -> "ثلاث وأربعون"
            44 -> "أربع وأربعون"
            45 -> "خمس وأربعون"
            46 -> "ست وأربعون"
            47 -> "سبع وأربعون"
            48 -> "ثماني وأربعون"
            49 -> "تسع وأربعون"
            50 -> "خمسون"
            51 -> "إحدى وخمسون"
            52 -> "اثنتان وخمسون"
            53 -> "ثلاث وخمسون"
            54 -> "أربع وخمسون"
            55 -> "خمس وخمسون"
            56 -> "ست وخمسون"
            57 -> "سبع وخمسون"
            58 -> "ثماني وخمسون"
            59 -> "تسع وخمسون"
            60 -> "ستون"
            in 61..99 -> {
                val tens = listOf(
                    "ستون", "سبعون", "ثمانون", "تسعون"
                )[(number / 10) - 6]
                val ones = number % 10
                if (ones == 0) tens else when (ones) {
                    1 -> "واحد و$tens"
                    2 -> "اثنان و$tens"
                    3 -> "ثلاثة و$tens"
                    4 -> "أربعة و$tens"
                    5 -> "خمسة و$tens"
                    6 -> "ستة و$tens"
                    7 -> "سبعة و$tens"
                    8 -> "ثمانية و$tens"
                    else -> "تسعة و$tens"
                }
            }
            100 -> "مئة"
            else -> number.toString()
        }
    }

    /**
     * تنسيق رقم في مجموعات أرقام حسب طريقة النطق (1..8).
     * 1=مفردة (رقم رقم)، 2=زوجي (رقمين كرقم واحد)، 3..8=ثلاثي..ثماني.
     */
    fun formatByMode(mode: Int, number: Int, isEnglish: Boolean): String {
        val safeMode = mode.coerceIn(1, 8)
        var sign = ""
        var n = number
        if (n < 0) {
            sign = if (isEnglish) "negative " else "سالب "
            n = -n
        }
        if (safeMode == 1) {
            val digits = n.toString().map { it.digitToInt() }
            val words = digits.joinToString(" ") {
                if (isEnglish) toEnglishWords(it) else toArabicWords(it)
            }
            return (sign + words).trim()
        }

        val digits = n.toString()
        val groupSize = safeMode
        val groups = mutableListOf<String>()
        val firstSize = digits.length % groupSize
        var index = 0
        if (firstSize > 0) {
            groups.add(digits.substring(0, firstSize))
            index = firstSize
        }
        while (index < digits.length) {
            groups.add(digits.substring(index, index + groupSize))
            index += groupSize
        }
        val words = groups.map { g ->
            val v = try { g.toInt() } catch (t: Throwable) { 0 }
            if (isEnglish) toEnglishWords(v) else toArabicWords(v)
        }.joinToString(", ")
        return (sign + words).trim()
    }
}