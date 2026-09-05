package com.aymankhattab.nateq.engine

import android.content.Context
import java.math.BigDecimal
import java.text.Normalizer
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * معالج النصوص الذكي - يحول النصوص الخام إلى نصوص قابلة للنطق طبيعياً
 * يدعم: الأرقام، التواريخ، الأوقات، العملات، الوحدات، الاختصارات
 */
class TextProcessor(private val context: Context) {

    companion object {
        // ======================================================
        // أنماط Regex مُجمَّعة مسبقاً (مرة واحدة عند تحميل الكلاس)
        // بدلاً من Pattern.compile() داخل كل استدعاء للدوال
        // ======================================================

// أنماط التواريخ
        private val PATTERN_DATE_YMD  = Pattern.compile("""(\d{4})[-/](\d{1,2})[-/](\d{1,2})""")
        private val PATTERN_DATE_DMY  = Pattern.compile("""(\d{1,2})[-/](\d{1,2})[-/](\d{4})""")
        private val PATTERN_DATE_DOTY = Pattern.compile("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")

        // أشهر السنة الهجرية (بها 12 شهراً كالميلادية)
        private val HIJRI_MONTHS = arrayOf(
            "", "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى",
            "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"
        )

        // أنماط الأوقات
        private val PATTERN_TIME = Pattern.compile("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

        // أنماط الأرقام
        private val PATTERN_NUMBER = Pattern.compile("""(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d+)?)""")

        // أنماط كود العملة
        private val PATTERN_CURRENCY_CODE = Pattern.compile(
            """\b(USD|EUR|GBP|SAR|AED|KWD|QAR|OMR|BHD|EGP|TND|DZD|MAD|JPY|CNY|INR|KRW|RUB)\s+(\d+(?:[.,]\d+)?)\b"""
        )

// أنماط تنظيف المسافات
        private val PATTERN_MULTI_SPACE  = Pattern.compile("""\s+""")
        private val PATTERN_SPACE_BEFORE = Pattern.compile("""\s+([،؛.!?])""")

        // ======================================================
        // جداول الرموز/العملات/الوحدات وأنماطها المُجمَّعة مرة واحدة
        // (بدل Pattern.compile()/Regex داخل كل استدعاء process، التي كانت
        //  تُنشئ ~30 نمطاً في كل جملة عربية).
        // ======================================================

        private val CURRENCY_SYMBOLS = mapOf<String, String>(
            "\$" to "دولار",
            "€" to "يورو",
            "£" to "جنيه استرليني",
            "¥" to "ين ياباني",
            "₹" to "روبية هندية",
            "₽" to "روبل روسي",
            "₩" to "وون كوري",
            "﷼" to "ريال",
            "د.إ" to "درهم إماراتي",
            "ر.س" to "ريال سعودي",
            "د.ك" to "دينار كويتي",
            "ر.ق" to "ريال قطري",
            "ر.ع" to "ريال عماني",
            "د.ب" to "دينار بحريني",
            "ج.م" to "جنيه مصري",
            "د.ت" to "دينار تونسي",
            "د.ج" to "دينار جزائري",
            "ر.م" to "ريال مغربي"
        )

        private val CURRENCY_PATTERNS_BEFORE = CURRENCY_SYMBOLS.map { (symbol, name) ->
            Pattern.compile("""${Pattern.quote(symbol)}\s*(\d+(?:[.,]\d+)?)\b""") to name
        }
        private val CURRENCY_PATTERNS_AFTER = CURRENCY_SYMBOLS.map { (symbol, name) ->
            Pattern.compile("""\b(\d+(?:[.,]\d+)?)\s*${Pattern.quote(symbol)}""") to name
        }

        private val UNIT_NAMES = listOf(
            // طول
            "km" to "كيلومتر",
            "م" to "متر",
            "سم" to "سنتيمتر",
            "مم" to "مليمتر",
            "inch" to "بوصة",
            "ft" to "قدم",
            "yd" to "ياردة",
            "mi" to "ميل",

            // وزن
            "kg" to "كيلوغرام",
            "غ" to "غرام",
            "ملغ" to "مليغرام",
            "lb" to "رطل",
            "oz" to "أونصة",

            // حجم
            "لتر" to "لتر",
            "مل" to "مليلتر",
            "غالون" to "غالون",

            // حرارة
            "°C" to "درجة مئوية",
            "°F" to "درجة فهرنهايت",
            "K" to "كلفن",

            // سرعة
            "كم/س" to "كيلومتر في الساعة",
            "م/ث" to "متر في الثانية",

            // بيانات
            "KB" to "كيلوبايت",
            "MB" to "ميجابايت",
            "GB" to "جيجابايت",
            "TB" to "تيرابايت",
            "كبت" to "كيلوبت",
            "مبت" to "ميجابت",
            "جببت" to "جيجابت",

            // وقت
            "ث" to "ثانية",
            "د" to "دقيقة",
            "س" to "ساعة",
            "ي" to "يوم",
            "أسبوع" to "أسبوع",
            "شهر" to "شهر",
            "سنة" to "سنة"
        )

        private val UNIT_PATTERNS = UNIT_NAMES.map { (unit, name) ->
            Pattern.compile("""\b(\d+(?:[.,]\d+)?)\s*${Pattern.quote(unit)}\b""") to name
        }

        private val SYMBOL_NAMES = mapOf(
            "%" to "بالمائة",
            "٪" to "بالمائة",
            "°" to "درجة",
            "°C" to "درجة مئوية",
            "°F" to "درجة فهرنهايت",
            "+" to "زائد",
            "-" to "ناقص",
            "×" to "مضروب في",
            "÷" to "مقسوم على",
            "=" to "يساوي",
            ">" to "أكبر من",
            "<" to "أصغر من",
            "≥" to "أكبر من أو يساوي",
            "≤" to "أصغر من أو يساوي",
            "≠" to "لا يساوي",
            "≈" to "تقريباً",
            "∞" to "ما لا نهاية",
            "√" to "جذر",
            "π" to "باي",
            "#" to "رقم",
            "@" to "عند",
            "&" to "و",
            "/" to "على",
            "\\" to "مائل عكسي",
            "|" to "أو",
            "~" to "تقريباً",
            "*" to "نجمة",
            "_" to "شرطة سفلية"
        )

        private val SYMBOL_PATTERNS = SYMBOL_NAMES.map { (symbol, replacement) ->
            Pattern.compile(Pattern.quote(symbol)) to replacement
        }
    }

private val pronunciationDict = PronunciationDictionary(context)

    /**
     * معالجة نص كامل وتحويله لصيغة نطق طبيعية.
* @param languageTag كود اللغة (مثلاً "ar"، "en"، "ar-EG")
     *                    — المعالجة مخصصة للغة العربية فقط؛ اللغات الأخرى تُعاد كما هي.
     */
    fun process(text: String, languageTag: String = "ar"): String {
        if (text.isBlank()) return text

        // المعالجة مخصصة للعربية فقط؛ الإنجليزية واللغات الأخرى تُعاد كما هي
        // (لا يجوز تحويل أرقام إنجليزية إلى كلمات عربية)
        if (languageTag.startsWith("ar").not()) return text

        // 0. تطبيع الأرقام الشرقية (٠١٢٣٤٥٦٧٨٩) إلى غربية (0123456789)
        //    لأن أنماط \d في Java لا تطابق الأرقام الشرقية
        var result = normalizeIndicDigits(text)

        // 0.1 معالجة الإيموجي: إزالتها وتنظيف التركيبات المعقدة حتى لا تشوّش النطق
        result = processEmojis(result)

        // 1. تطبيق القاموس الشخصي أولاً (أعلى أولوية)
        result = pronunciationDict.apply(result)

        // 2. معالجة التواريخ
        result = processDates(result)

        // 3. معالجة الأوقات
        result = processTimes(result)

        // 4. معالجة العملات
        result = processCurrencies(result)

        // 5. معالجة الوحدات
        result = processUnits(result)

        // 6. معالجة الأرقام العادية
        result = processNumbers(result)

        // 7. معالجة الرموز الشائعة
        result = processSymbols(result)

        // 8. تنظيف المسافات الزائدة
        result = cleanupSpaces(result)

        return result
    }

    /** معالجة التواريخ: 2024-03-15 → 15 مارس 2024 */
    private fun processDates(text: String): String {
        // isYMD=true → group1=year,group2=month,group3=day | isYMD=false → group1=day,group2=month,group3=year
        val patterns = listOf(
            PATTERN_DATE_YMD  to true,   // YYYY-MM-DD أو YYYY/MM/DD
            PATTERN_DATE_DMY  to false,  // DD-MM-YYYY أو DD/MM/YYYY
            PATTERN_DATE_DOTY to false   // DD.MM.YYYY
        )

        var result = text
        for ((pattern, isYMD) in patterns) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val day: String
                val month: String
                val year: String
                if (isYMD) {
                    // YYYY-MM-DD format
                    year = matcher.group(1)!!
                    month = matcher.group(2)!!
                    day = matcher.group(3)!!
} else {
                    // DD-MM-YYYY format
                    day = matcher.group(1)!!
                    month = matcher.group(2)!!
                    year = matcher.group(3)!!
                }
                // حماية من تاريخ شاذ (شهر 13-99 أو يوم 32+) التي تُسقط `months[month]`
                val dayNum = day.toInt()
                val monthNum = month.toInt()
                if (monthNum !in 1..12 || dayNum !in 1..31) {
                    matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)!!))
                    continue
                }
                val dateText = formatDate(dayNum, monthNum, year.toInt())
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(dateText))
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }
        return result
    }

    /** معالجة الأوقات: 14:30 → الثانية والنصف ظهراً */
    private fun processTimes(text: String): String {
        val matcher = PATTERN_TIME.matcher(text)  // استخدام النمط المُجمَّع مسبقاً
        val buffer = StringBuffer()

        while (matcher.find()) {
            val hour = matcher.group(1)!!.toInt()
            val minute = matcher.group(2)!!.toInt()
            val timeText = formatTime(hour, minute)
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(timeText))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

/** معالجة العملات: $100 → مائة دولار، 50€ → خمسون يورو */
    private fun processCurrencies(text: String): String {
        var result = text

        // رموز قبل المبلغ: $100
        for ((pattern, name) in CURRENCY_PATTERNS_BEFORE) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val amount = matcher.group(1)!!.replace(',', '.').toDouble()
                val amountText = numberToWords(amount)
                matcher.appendReplacement(buffer, Matcher.quoteReplacement("$amountText $name"))
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }

        // رموز بعد المبلغ: 100$
        for ((pattern, name) in CURRENCY_PATTERNS_AFTER) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val amount = matcher.group(1)!!.replace(',', '.').toDouble()
                val amountText = numberToWords(amount)
                matcher.appendReplacement(buffer, Matcher.quoteReplacement("$amountText $name"))
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }

        // أكواد العملة: USD 100
        // استخدام النمط المُجمَّع مسبقاً من companion object
        val codeMatcher = PATTERN_CURRENCY_CODE.matcher(result)
        val codeBuffer = StringBuffer()
        while (codeMatcher.find()) {
            val code = codeMatcher.group(1)!!
            val amount = codeMatcher.group(2)!!.replace(',', '.').toDouble()
            val name = when (code) {
                "USD" -> "دولار أمريكي"
                "EUR" -> "يورو"
                "GBP" -> "جنيه استرليني"
                "SAR" -> "ريال سعودي"
                "AED" -> "درهم إماراتي"
                "KWD" -> "دينار كويتي"
                "QAR" -> "ريال قطري"
                "OMR" -> "ريال عماني"
                "BHD" -> "دينار بحريني"
                "EGP" -> "جنيه مصري"
                "TND" -> "دينار تونسي"
                "DZD" -> "دينار جزائري"
                "MAD" -> "درهم مغربي"
                "JPY" -> "ين ياباني"
                "CNY" -> "يوان صيني"
                "INR" -> "روبية هندية"
                "KRW" -> "وون كوري"
                "RUB" -> "روبل روسي"
                else -> code
            }
            val amountText = numberToWords(amount)
            codeMatcher.appendReplacement(codeBuffer, Matcher.quoteReplacement("$amountText $name"))
        }
        codeMatcher.appendTail(codeBuffer)
        result = codeBuffer.toString()

        return result
    }

    /** معالجة الوحدات: 5km → خمسة كيلومترات، 25°C → خمس وعشرون درجة مئوية */
    private fun processUnits(text: String): String {
        var result = text
        for ((pattern, name) in UNIT_PATTERNS) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val number = matcher.group(1)!!.replace(',', '.').toDouble()
                val numberText = numberToWords(number)
                matcher.appendReplacement(buffer, Matcher.quoteReplacement("$numberText $name"))
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }
        return result
    }

    /** معالجة الأرقام العادية: 1234 → ألف ومائتان وأربعة وثلاثون */
    private fun processNumbers(text: String): String {
        val matcher = PATTERN_NUMBER.matcher(text)  // استخدام النمط المُجمَّع مسبقاً
        val buffer = StringBuffer()

        while (matcher.find()) {
            val numberStr = matcher.group(1)!!
            // تجاهل الأرقام التي جزء من تاريخ/وقت/عملة تمت معالجتها
            val start = matcher.start()
            val end = matcher.end()
            val before = if (start > 0) text[start - 1] else ' '
            val after = if (end < text.length) text[end] else ' '

            // إذا محاط برموز عملة أو وقت، تخطيه
            val currencySymbols = setOf('$', '€', '£', '¥', '₹', '₽', '₩', '﷼')
            if (before in currencySymbols || after in currencySymbols) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(numberStr))
                continue
            }
            if (before == ':' || after == ':') {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(numberStr))
                continue
            }

            val numberText = parseNumberText(numberStr)
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(numberText))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * يفصل بين فواصل الآلاف والفاصلة العشرية:
     *  - 1,234 → 1234 (فاصلة آلاف)
     *  - 1,234.56 → 1234.56 (فاصلة آلاف + فاصلة عشرية)
     *  - 3.14 → 3.14 (عشري)
     */
    private fun parseNumberText(numberStr: String): String {
        // آخر نقطة هي الفاصلة العشرية؛ ما قبلها فواصل الآلاف
        val number: Double = numberStr.replace(",", "").toDoubleOrNull() ?: return numberStr
        return numberToWords(number)
    }

/** معالجة الرموز الشائعة */
    private fun processSymbols(text: String): String {
        var result = text
        for ((pattern, replacement) in SYMBOL_PATTERNS) {
            result = pattern.matcher(result).replaceAll(replacement)
        }
        return result
    }

    /** تنظيف المسافات الزائدة */
    private fun cleanupSpaces(text: String): String {
        // استخدام الأنماط المُجمَّعة مسبقاً من companion object
        return PATTERN_SPACE_BEFORE.matcher(
            PATTERN_MULTI_SPACE.matcher(text).replaceAll(" ")
        ).replaceAll("$1").trim()
    }

/** تنسيق التاريخ بالعربية (ميلادي أو هجري حسب إعداد المستخدم) */
    private fun formatDate(day: Int, month: Int, year: Int): String {
        if (month !in 1..12) return "التاريخ غير صالح"
        if (runCatching {
                com.aymankhattab.nateq.settings.SettingsRepository(context).isHijriDateEnabled()
            }.getOrDefault(false)
        ) {
            val (hDay, hMonth, hYear) = runCatching { toHijri(day, month, year) }
                .getOrDefault(Triple(day, month, year))
            if (hMonth in 1..12) {
                return "${numberToWords(hDay.toLong())} ${HIJRI_MONTHS[hMonth]} " +
                    "${numberToWords(hYear.toLong())}"
            }
        }
        val months = arrayOf(
            "", "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
        )
        val dayText = numberToWords(day.toLong())
        val yearText = numberToWords(year.toLong())
        return "$dayText ${months[month]} $yearText"
    }

    /** تحويل تاريخ ميلادي إلى هجري (تقويم أم القرى المدعوم من أندرويد 7 فما فوق) */
    private fun toHijri(day: Int, month: Int, year: Int): Triple<Int, Int, Int> {
        val gregorian = Calendar.getInstance(Locale.US).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }
        val hijri = android.icu.util.IslamicCalendar.getInstance(
            android.icu.util.TimeZone.getDefault(),
            // وسم تقويم أم القرى: العلامة تحدد نمط الحساب تلقائياً داخل ICU
            android.icu.util.ULocale("en_US@calendar=islamic-umalqura")
        ).apply {
            time = gregorian.time
        }
        return Triple(
            hijri.get(android.icu.util.Calendar.DAY_OF_MONTH),
            hijri.get(android.icu.util.Calendar.MONTH) + 1,
            hijri.get(android.icu.util.Calendar.YEAR)
        )
    }

    /** تنسيق الوقت بالعربية */
    private fun formatTime(hour: Int, minute: Int): String {
        val hour12 = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val period = if (hour < 12) "صباحاً" else "مساءً"
        val hourText = numberToWords(hour12.toLong())

        return when (minute) {
            0 -> "$hourText $period"
            15 -> "$hourText والربع $period"
            30 -> "$hourText والنصف $period"
            45 -> {
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextHourText = numberToWords(nextHour.toLong())
                "$nextHourText إلا ربع $period"
            }
            in 1..29 -> "$hourText و ${numberToWords(minute.toLong())} دقيقة $period"
            in 31..44 -> "$hourText و ${numberToWords(minute.toLong())} دقيقة $period"
            in 46..59 -> {
                val remaining = 60 - minute
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextHourText = numberToWords(nextHour.toLong())
                "$nextHourText إلا ${numberToWords(remaining.toLong())} دقيقة $period"
            }
            else -> "$hourText $period"
        }
    }

/** تطبيع الأرقام الشرقية والفارسية والهندية إلى غربية — يفوّض إلى util المشترك */
    private fun normalizeIndicDigits(text: String): String {
        return com.aymankhattab.nateq.util.LocaleUtils.normalizeIndicDigits(text)
    }

    /**
     * معالجة الإيموجي: إزالة الإيموجي وتركيباتها المعقدة (ZWJ, skin tone modifiers,
     * variation selectors, flags) بطريقة آمنة حتى لا تشوّش النطق.
     * تُستبدل بمسافة للحفاظ على الفصل بين الكلمات.
     */
    private fun processEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val len = text.length
        while (i < len) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)

            // نطاقات الإيموجي الأساسي (ما عدا العربية والعامة)
            val isEmoji = isEmojiCodePoint(cp)

            // تعديلات لون البشرة و variation selectors و ZWJ
            val isModifier = cp in 0x1F3FB..0x1F3FF || cp in 0xFE00..0xFE0F || cp == 0x200D

            if (isEmoji) {
                // استبدال الإيموجي بمسافة
                sb.append(' ')
            } else if (isModifier) {
                // إزالة المعدّلات بصمت
                // لا شيء يضاف
            } else {
                sb.append(text, i, i + chars)
            }
            i += chars
        }
        // تطبيع NFC لضمان اتساق الكودات
        return Normalizer.normalize(sb.toString().trim(), Normalizer.Form.NFC)
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        // نطاقات الإيموجي الشائعة (صفحات متنوعة)
        return cp in 0x1F300..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2B00..0x2BFF ||
            cp in 0x1F000..0x1F02F ||
            cp in 0xFE0F.toInt()..0xFE0F.toInt() ||
            cp in 0x1F1E6..0x1F1FF  // أعلام الدول
    }

    /** تحويل رقم لكلمات عربية (يدعم حتى التريليونات، والكسور العشرية) */
    fun numberToWords(number: Number): String {
        // معالجة الكسور العشرية: فصل الجزء الصحيح والعشري ونطق "فاصلة" ثم الأرقام
        if (number is Double || number is Float) {
            val d = number.toDouble()
            val negative = d < 0
            val abs = Math.abs(d)
            val integerPart = abs.toLong()

            // استخراج الأرقام العشرية بعد الفاصلة كسلسلة (بدون صفر متكرر ختامي)
            var decimalStr = formatDecimal(abs - integerPart)
            val decimalDigits = decimalStr.trimEnd('0')
            if (decimalDigits.isEmpty()) {
                return if (negative) "ناقص ${numberToWords(integerPart)}" else numberToWords(integerPart)
            }
            val base = if (negative) "ناقص " else ""
            // نطق الأرقام العشرية واحداً واحداً (مثل النطق الطبيعي للفاصلة)
            val digitWord = numberToWords(decimalDigits.toLong())
            return "$base${numberToWords(integerPart)} فاصلة $digitWord"
        }

        val num = number.toLong()
        if (num == 0L) return "صفر"
        if (num < 0L) return "ناقص ${numberToWords(-num)}"

        val units = arrayOf("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة")
        val teens = arrayOf("عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر")
        val tens = arrayOf("", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون")
        val hundreds = arrayOf("", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة")

        fun convertHundreds(n: Long): String {
            if (n == 0L) return ""
            if (n < 10) return units[n.toInt()]
            if (n < 20) return teens[(n - 10).toInt()]
            if (n < 100) {
                val ten = n / 10
                val unit = n % 10
                return when {
                    unit == 0L -> tens[ten.toInt()]
                    // "أحد وعشرون" وليس "واحد و عشرون" (قاعدة العدد المركب)
                    unit == 1L -> "أحد و${tens[ten.toInt()]}"
                    unit == 2L -> "اثنان و${tens[ten.toInt()]}"
                    else -> "${units[unit.toInt()]} و${tens[ten.toInt()]}"
                }
            }
            val hundred = n / 100
            val remainder = n % 100
            return if (remainder == 0L) hundreds[hundred.toInt()] else "${hundreds[hundred.toInt()]} و${convertHundreds(remainder)}"
        }

        if (num < 1000) return convertHundreds(num)

        var result = ""
        var n = num
        var groupIndex = 0

        while (n > 0) {
            val group = n % 1000
            if (group != 0L) {
                val groupText = convertHundreds(group)
                val scaleText = when (groupIndex) {
                    1 -> when (group) { 1L -> "ألف"; 2L -> "ألفان"; in 3L..10L -> "$groupText آلاف"; else -> "$groupText ألفاً" }
                    2 -> when (group) { 1L -> "مليون"; 2L -> "مليونان"; in 3L..10L -> "$groupText ملايين"; else -> "$groupText مليوناً" }
                    3 -> when (group) { 1L -> "مليار"; 2L -> "ملياران"; in 3L..10L -> "$groupText مليارات"; else -> "$groupText ملياراً" }
                    4 -> when (group) { 1L -> "تريليون"; 2L -> "تريليونان"; in 3L..10L -> "$groupText تريليونات"; else -> "$groupText تريليوناً" }
                    else -> ""
                }
                val part = when {
                    groupIndex == 0 -> groupText
                    group == 1L || group == 2L -> scaleText  // "ألف" أو "ألفان" بدون "واحد"
                    else -> scaleText
                }
                result = if (result.isEmpty()) part else "$part و$result"
            }
            n /= 1000
            groupIndex++
        }
        return result
    }

/** تحويل الكسر العشري إلى سلسلة أرقام (بلا البادئة 0.) */
    private fun formatDecimal(value: Double): String {
        val s = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        val dot = s.indexOf('.')
        return if (dot >= 0) s.substring(dot + 1) else s
    }
}
