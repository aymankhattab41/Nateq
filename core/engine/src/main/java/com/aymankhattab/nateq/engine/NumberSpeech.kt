package com.aymankhattab.nateq.engine

/**
 * منطق نطق الأرقام بنمط التجميع المُختار (1=مفردة، 2=زوجي، 3..8=ثلاثي..ثماني)
 * وباللغة المطلوبة (عربية/إنجليزية).
 *
 * كائن نقيّ يُستخدم من TimeAnnouncementManager (النطق الفعلي) ومن شاشة
 * الإعدادات (معاينة نطق رقم) لضمان تطابق الناتج بين المعاينة والنطق الحقيقي.
 */
object NumberSpeech {

    /**
     * تحويل رقم إلى كلمات إنجليزية (للأرقام المنفصلة).
     * يدعم حتى 99,999,999 (8 خانات) لخدمة التجميع الخماسي..الثُماني.
     * معامل isFeminine غير مؤثر في الإنجليزية لكنه يبقى للتوافق مع
     * الاستدعاءات الموحّدة (لا جنس في الإنجليزية).
     *
     * **السالبة:** تُنطق «minus» فتُبعَد عن الاستدعاء التكراري اللانهائي
     * الذي كانت تغوص فيه (لا يطابق أيّ نطاق في `when` فيقع في فرع الملايين
     * ويتكرر بلا سقف مهيلاً المكدس). `Int.MIN_VALUE` تُعالَج عبر مسار
     * Long آمن (نفيها يتجاوز Int فيبقى سالباً).
     */
    fun toEnglishWords(number: Int, isFeminine: Boolean = false): String {
        if (number == Int.MIN_VALUE) {
            return "minus ${
                englishFromDigits(Int.MIN_VALUE.toLongSize().toString())
            }"
        }
        if (number < 0) return "minus ${toEnglishWords(-number)}"
        return toEnglishWordsPositive(number)
    }

    /** نسخة Long عامة (لخدمة الأعداد الـ 19 خانة في NumberStep): تقولب
     *  أي قيمة حتى Long.MAX_VALUE بنمط تجميع ثلاثي (thousand/million/…)
     *  مع معالجة الإشارة وLong.MIN_VALUE. */
    fun toEnglishWords(number: Long, isFeminine: Boolean = false): String {
        if (number == Long.MIN_VALUE) {
            // `Long.MAX_VALUE + 1L` كان يفيض حسابياً فيصبح -9223372036854775808
            // نصاً يحوي إشارة سالبة تخرّب تجزئة
            // الثلاثيات ورمزية toInt (انهيار).
            return "minus ${englishFromDigits("9223372036854775808")}"
        }
        if (number < 0) return "minus ${toEnglishWords(-number)}"
        if (number <= Int.MAX_VALUE) return toEnglishWords(number.toInt())
        return englishFromDigits(number.toString())
    }

    /** قيمة Int.MIN_VALUE بلا إشارة مخزنة باعتبارها غير سالبة (2147483648). */
    private fun Int.toLongSize(): Long = (this.toLong() and 0xFFFFFFFFL)

    private fun toEnglishWordsPositive(number: Int): String {
        return when (number) {
            in 0..12 -> listOf(
                "zero", "one", "two", "three", "four", "five", "six",
                "seven", "eight", "nine", "ten", "eleven", "twelve"
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
            in 100..999 -> {
                val hundreds = listOf(
                    "one", "two", "three", "four", "five", "six",
                    "seven", "eight", "nine"
                )[(number / 100) - 1]
                val remainder = number % 100
                if (remainder == 0) "$hundreds hundred"
                else "$hundreds hundred ${toEnglishWords(remainder)}"
            }
            in 1000..9999 -> {
                val thousands = listOf(
                    "one", "two", "three", "four", "five", "six",
                    "seven", "eight", "nine"
                )[(number / 1000) - 1]
                val remainder = number % 1000
                if (remainder == 0) "$thousands thousand"
                else "$thousands thousand ${toEnglishWords(remainder)}"
            }
            in 10000..999999 -> {
                val thousands = number / 1000
                val remainder = number % 1000
                val thousandWord = toEnglishWords(thousands)
                if (remainder == 0) "$thousandWord thousand"
                else "$thousandWord thousand ${toEnglishWords(remainder)}"
            }
            else -> {
                val millions = number / 1000000
                val remainder = number % 1000000
                val millionWord = toEnglishWords(millions)
                if (remainder == 0) "$millionWord million"
                else "$millionWord million ${toEnglishWords(remainder)}"
            }
        }
    }

    /** تحويل سلسلة أرقام (بلا إشارة) إلى كلمات إنجليزية بطريقة تجميع
     *  ثلاثي: تُقسَّم إلى خانات ثلاثة من اليمين وتُوسم كل خانة بـ scale
     *  (thousand/million/billion/…). يُستخدم لأبعاد أكبر من Int، ولا يقبل
     *  إلا أرقاماً طولها ضمن مقدرة [toLong] من المتصل. */
    private fun englishFromDigits(digits: String): String {
        val scales = arrayOf(
            "", "thousand", "million", "billion", "trillion",
            "quadrillion", "quintillion", "sextillion", "septillion",
            "octillion", "nonillion"
        )
        val trimmed = digits.trimStart('0').ifEmpty { "0" }
        // «0».repeat فقط — كان «000».repeat(n) يحشو 3n أصفاراً فينزاح
        // السُلَّم: أول خانتين صفريتين تُتخطيان وكل خانة تُوسم بسُلّمٍ أكبر
        // بواحدة…ثم اثنتين (مثل 9223372036854775808 → «nine hundred twenty two
        // quintillion» بدل «nine quintillion»).
        val padded = "0".repeat((3 - trimmed.length % 3) % 3) + trimmed
        val chunks = padded.chunked(3)
        val parts = chunks.mapIndexedNotNull { idx, chunk ->
            val value = chunk.toInt()
            if (value == 0) return@mapIndexedNotNull null
            val words = when {
                value < 100 -> toEnglishWordsPositive(value)
                else -> {
                    val hundred = value / 100
                    val remainder = value % 100
                    val head = "${toEnglishWordsPositive(hundred)} hundred"
                    if (remainder == 0) head
                    else "$head ${toEnglishWordsPositive(remainder)}"
                }
            }
            val scale = scales[chunks.size - 1 - idx]
            if (scale.isEmpty()) words else "$words $scale"
        }
        return parts.joinToString(" ").ifEmpty { "zero" }
    }

    /**
     * تحويل رقم إلى كلمات عربية. يدعم حتى 99,999,999 (8 خانات) لخدمة
     * التجميع الخماسي..الثُمَاني (كان الوثيقة تقول 9999).
     *
     * @param isFeminine عندما true تُنطق آحاد العدد بصيغة المعدود المؤنث
     *   («خمس دقائق»، «واحدة وخمسون»)، وعند false بصيغة المعدود المذكر
     *   («خمسة»، «خمسون» كأرقام مجردة) — تناسب numberToWords في TextProcessor
     *   وتزيل التناقض الذي كان ينتج «خمس» عند نطق الرقم 5 منفرداً.
     */
    fun toArabicWords(number: Int, isFeminine: Boolean = true): String {
        // **السالبة:** «سالب » + القيمة المطلقة — كانت القيمة السالبة تفلت
        // من كل نطاقات `when` وتسقط في فرع الملايين فتتكرر بلا سقف حتى
        // StackOverflow (وتُفهرَس مصفوفة الآحاد بفهرس سالب في نسخ سابقة).
        // `Int.MIN_VALUE` مستثناة لأن نفيها يفيض فيبقى سالباً — تُركَّب
        // يدوياً: ملياران + 147,483,648.
        if (number == Int.MIN_VALUE) {
            val leftover = 147_483_648
            return "سالب ملياران و${toArabicWordsPositive(
                leftover, isFeminine
            )}"
        }
        if (number < 0) return "سالب ${toArabicWords(-number, isFeminine)}"
        return toArabicWordsPositive(number, isFeminine)
    }

    private fun toArabicWordsPositive(
        number: Int,
        isFeminine: Boolean
    ): String {
        val onesF = arrayOf(
            "", "واحدة", "اثنتان", "ثلاث", "أربع", "خمس", "ست", "سبع",
            "ثماني", "تسع"
        )
        val onesM = arrayOf(
            "", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة",
            "ثمانية", "تسعة"
        )
        val ones = if (isFeminine) onesF else onesM
        val teensF = arrayOf(
            "عشر", "إحدى عشرة", "اثنتا عشرة", "ثلاث عشرة", "أربع عشرة",
            "خمس عشرة", "ست عشرة", "سبع عشرة", "ثماني عشرة", "تسع عشرة"
        )
        val teensM = arrayOf(
            "عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر",
            "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر"
        )
        val teens = if (isFeminine) teensF else teensM
        val tensNames = arrayOf(
            "", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون",
            "سبعون", "ثمانون", "تسعون"
        )
        // أسماء المئات؛ العدد 3-9 يخالف المعدود المؤنث (مائة) وجوباً
        // بحذف التاء المربوطة: «ثلاثمائة» لا «ثلاثةمائة».
        val hundredsTable = arrayOf(
            "", "", "", "ثلاثمائة", "أربعمائة", "خمسمائة",
            "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة"
        )

        fun under100(n: Int): String = when (n) {
            0 -> "صفر"
            in 1..9 -> ones[n]
            in 10..19 -> teens[n - 10]
            else -> {
                val ten = n / 10
                val one = n % 10
                if (one == 0) tensNames[ten]
                else if (isFeminine) {
                    // مؤنث: «إحدى وخمسون»، «اثنتان وخمسون»، «خمس وخمسون»
                    when (one) {
                        1 -> "إحدى و${tensNames[ten]}"
                        2 -> "اثنتان و${tensNames[ten]}"
                        else -> "${ones[one]} و${tensNames[ten]}"
                    }
                } else {
                    // مذكر: «أحد وخمسون»، «اثنان وخمسون»، «خمسة وخمسون»
                    when (one) {
                        1 -> "أحد و${tensNames[ten]}"
                        2 -> "اثنان و${tensNames[ten]}"
                        else -> "${ones[one]} و${tensNames[ten]}"
                    }
                }
            }
        }

        fun hundredsWord(h: Int): String = when (h) {
            1 -> if (isFeminine) "مئة" else "مائة"
            2 -> "مائتان"
            in 3..9 -> hundredsTable[h]
            else -> "${numberToWordsHelper(h)}مائة"
        }

        return when {
            number in 0..99 -> under100(number)
            number in 100..999 -> {
                val h = number / 100
                val r = number % 100
                if (r == 0) hundredsWord(h)
                else "${hundredsWord(h)} و${under100(r)}"
            }
            number in 1000..9999 -> {
                val t = number / 1000
                val r = number % 1000
                val thousand = when (t) {
                    1 -> "ألف"
                    2 -> "ألفان"
                    in 3..10 -> "${toArabicWords(t, isFeminine = false)} آلاف"
                    else -> "${toArabicWords(t, isFeminine = false)} ألفاً"
                }
                if (r == 0) thousand
                else if (r < 100) "$thousand و${under100(r)}"
                else "$thousand و${toArabicWords(r, isFeminine)}"
            }
            number in 10000..999999 -> {
                // تمييز الآلاف (1..999) بالأشكال النحوية الصحيحة:
                // «خمسة آلاف»، «خمسة عشر ألفاً»، «خمسمائة ألف»، «مائتا ألف»،
                // ومئة بآحاد 1–2 بالجرّ: «مائة وواحد ألف».
                fun thousandPart(t: Int): String {
                    val w = toArabicWords(t, isFeminine = false)
                    return when {
                        t == 1 -> "ألف"
                        t == 2 -> "ألفان"
                        t in 3..10 -> "$w آلاف"
                        t in 11..99 -> "$w ألفاً"
                        t == 100 -> "مائة ألف"
                        t == 200 -> "مائتا ألف"
                        t % 100 == 0 -> "$w ألف"
                        t % 100 in 1..2 -> "$w ألف"
                        t % 100 in 3..10 -> "$w آلاف"
                        else -> "$w ألفاً"
                    }
                }
                val t = number / 1000
                val r = number % 1000
                val thousand = thousandPart(t)
                if (r == 0) thousand
                else if (r < 100) "$thousand و${under100(r)}"
                else "$thousand و${toArabicWords(r, isFeminine)}"
            }
            else -> {
                // الملايين بعد 8 خانات (حتى 99,999,999): تصريف المليون مع
                // التمييز («مليون»، «مليونان»، «ملايين»، «مليوناً»، وبالجر:
                // «مائة مليون»، «مائة وواحد مليون»).
                val m = number / 1000000
                val r = number % 1000000
                val million = when (m) {
                    1 -> "مليون"
                    2 -> "مليونان"
                    in 3..10 -> "${toArabicWords(m, isFeminine = false)} ملايين"
                    else -> {
                        val w = toArabicWords(m, isFeminine = false)
                        when {
                            // **حذف نون المثنى عند الإضافة:** «مائتان» فوق
                            // تمييزٍ مثل المليون تحذف نونها فتصبح «مائتا» —
                            // «مائتا مليون» لا «مائتان مليون» (وقاعدتها
                            // العامة تتسع لـ«مئتان»→«مئتا»).
                            m % 100 == 0 -> "${
                                terminalHundreds(w)
                            } مليون"
                            m % 100 in 1..2 -> "$w مليون"
                            m % 100 in 3..10 -> "$w ملايين"
                            else -> "$w مليوناً"
                        }
                    }
                }
                if (r == 0) million
                else if (r < 100) "$million و${under100(r)}"
                else "$million و${toArabicWords(r, isFeminine)}"
            }
        }
    }

    /**
     * اسم الساعة بالصيغة الترتيبية المؤنثة المعرّفة بأل (للساعات 1–12):
     * «الواحدة، الثانية، الثالثة… العاشرة، الحادية عشرة، الثانية عشرة».
     * الساعة تُنطق دائماً بهذه الصيغة لا بالأعداد الأصلية المذكرة/المؤنثة
     * («اثنان والنصف» خطأ، والصواب «الثانية والنصف»).
     */
    fun toOrdinalHourWord(hour12: Int): String = when (hour12) {
        1 -> "الواحدة"
        2 -> "الثانية"
        3 -> "الثالثة"
        4 -> "الرابعة"
        5 -> "الخامسة"
        6 -> "السادسة"
        7 -> "السابعة"
        8 -> "الثامنة"
        9 -> "التاسعة"
        10 -> "العاشرة"
        11 -> "الحادية عشرة"
        12 -> "الثانية عشرة"
        else -> toArabicWords(hour12)
    }

    /** محوّل مؤقت لمئات أكبر من 10 (لا يُستخدم فعلياً إلا بصيغة مذكر). */
    private fun numberToWordsHelper(n: Int): String = when (n) {
        in 3..10 -> arrayOf(
            "", "", "", "ثلاث", "أربع", "خمس", "ست", "سبع", "ثمان", "تسع", "عشر"
        )[n]
        else -> n.toString()
    }

    /** حذف نون المثنى من «مائتان/مئتان» عند إضافتها فوق الاسم (التمييز):
     *  تُصبح «مائتا/مئتا» لتصحّ «مائتا مليون» و«مئتا ألف». بند 3.4:
     *  الاستبدالُ نهايةَ المركّب يلتقط «ألف ومائتان» ← «ألف ومائتا»
     *  (مثل 1_200_000_000) لا الكلمةَ المنفردة فقط. */
    private fun terminalHundreds(word: String): String =
        if (word.endsWith("مائتان")) {
            word.dropLast(6) + "مائتا"
        } else if (word.endsWith("مئتان")) {
            word.dropLast(5) + "مئتا"
        } else {
            word
        }

    /**
     * تنسيق رقم نصي (صحيح أو عشري أو طويل) في مجموعات أرقام (1..8).
     * 1=مفردة (رقم رقم)، 2=زوجي (رقمين كرقم واحد)، 3..8=ثلاثي..ثماني.
     * يدعم الكسور العشرية (مثل 30496.00) ويحافظ على الأصفار البادئة
     * والكسرية.
     */
    fun formatByMode(
        mode: Int,
        numberStr: String,
        isEnglish: Boolean
    ): String {
        val trimmed = numberStr.trim()
        if (trimmed.isEmpty()) return ""

        var sign = ""
        var s = trimmed
        if (s.startsWith("-")) {
            sign = if (isEnglish) "negative " else "سالب "
            s = s.substring(1).trim()
        } else if (s.startsWith("+")) {
            sign = if (isEnglish) "plus " else "زائد "
            s = s.substring(1).trim()
        }

        val dot = s.indexOf('.')
        val intDigits: String
        val fracDigits: String?
        if (dot >= 0) {
            intDigits = s.substring(0, dot).filter { it.isDigit() }
                .ifEmpty { "0" }
            fracDigits = s.substring(dot + 1).filter { it.isDigit() }
        } else {
            intDigits = s.filter { it.isDigit() }.ifEmpty { "0" }
            fracDigits = null
        }

        val safeMode = mode.coerceIn(1, 8)

        val intWords = formatDigitsByMode(safeMode, intDigits, isEnglish)
        val result = if (!fracDigits.isNullOrEmpty()) {
            val fracSep = if (isEnglish) " point " else " فاصلة "
            val fracWords = fracDigits.map { ch ->
                val d = ch.digitToInt()
                if (isEnglish) toEnglishWords(d)
                else toArabicWords(d, isFeminine = false)
            }.joinToString(" ")
            "$intWords$fracSep$fracWords"
        } else {
            intWords
        }
        return (sign + result).trim()
    }

    /** التحقق هل المتوالية رقم هاتف يُلزم النطق المفرد (7..15 خانة). */
    fun isPhoneNumber(raw: String, digits: String): Boolean {
        if (digits.length !in 7..15) return false
        val clean = raw.trimStart()
        if (clean.startsWith("+")) return true
        if (digits.startsWith("0")) return true
        return raw.any {
            it == '-' || it == '(' || it == ')' || it == '/'
        }
    }

    /** نسخة Long عامة تحافظ على الأعداد الكبيرة السالبة وإشارتها. */
    fun formatByMode(mode: Int, number: Long, isEnglish: Boolean): String {
        if (number == Long.MIN_VALUE) {
            val sign = if (isEnglish) "negative " else "سالب "
            val body = formatByMode(
                mode, "9223372036854775808", isEnglish
            )
            return sign + body
        }
        val sign = if (number < 0) {
            if (isEnglish) "negative " else "سالب "
        } else ""
        val absStr = if (number < 0) (-number).toString()
            else number.toString()
        val body = formatByMode(mode, absStr, isEnglish)
        return sign + body
    }

    /**
     * تنسيق رقم في مجموعات أرقام حسب طريقة النطق (1..8).
     * 1=مفردة (رقم رقم)، 2=زوجي (رقمين كرقم واحد)، 3..8=ثلاثي..ثماني.
     */
    fun formatByMode(mode: Int, number: Int, isEnglish: Boolean): String =
        formatByMode(mode, number.toLong(), isEnglish)

    private fun formatDigitsByMode(
        safeMode: Int,
        digits: String,
        isEnglish: Boolean
    ): String {
        if (safeMode == 1) {
            return digits.map { ch ->
                val d = ch.digitToInt()
                if (isEnglish) {
                    toEnglishWords(d)
                } else {
                    toArabicWords(d, isFeminine = false)
                }
            }.joinToString(" ")
        }

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
        return groups.map { g ->
            if (g.startsWith("0")) {
                g.map { ch ->
                    val d = ch.digitToInt()
                    if (isEnglish) {
                        toEnglishWords(d)
                    } else {
                        toArabicWords(d, isFeminine = false)
                    }
                }.joinToString(" ")
            } else {
                val v = try { g.toInt() } catch (t: Throwable) { 0 }
                if (isEnglish) {
                    toEnglishWords(v)
                } else {
                    toArabicWords(v, isFeminine = false)
                }
            }
        }.joinToString(", ")
    }
}