package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة الوحدات: 5km → «خمسة كيلومترات»، 25°C → «خمس وعشرون درجة مئوية» */
internal object UnitStep : TextProcessingStep {

    private val UNIT_NAMES = listOf(
        // سرعة
        UnitInfo(
            "كم/س", "كيلومتر في الساعة", "كيلومترات في الساعة",
            "كيلومتران في الساعة", false
        ),
        UnitInfo(
            "م/ث", "متر في الثانية", "أمتار في الثانية",
            "متران في الثانية", false
        ),
        UnitInfo(
            "MB/s", "ميجابايت في الثانية", "ميجابايتات في الثانية",
            "ميجابايتان في الثانية", false
        ),
        UnitInfo(
            "KB/s", "كيلوبايت في الثانية", "كيلوبايتات في الثانية",
            "كيلوبايتان في الثانية", false
        ),
        UnitInfo(
            "GB/s", "جيجابايت في الثانية", "جيجابايتات في الثانية",
            "جيجابايتان في الثانية", false
        ),

        // بيانات
        UnitInfo("KB", "كيلوبايت", "كيلوبايتات", "كيلوبايتان", false),
        UnitInfo("MB", "ميجابايت", "ميجابايتات", "ميجابايتان", false),
        UnitInfo("GB", "جيجابايت", "جيجابايتات", "جيجابايتان", false),
        UnitInfo("TB", "تيرابايت", "تيرابايتات", "تيرابايتان", false),
        UnitInfo("بايت", "بايت", "بايتات", "بايتان", false),
        UnitInfo("كبت", "كيلوبت", "كيلوبتات", "كيلوبتان", false),
        UnitInfo("مبت", "ميجابت", "ميجابتات", "ميجابتان", false),
        UnitInfo("جببت", "جيجابت", "جيجابتات", "جيجابتان", false),

        // طول
        UnitInfo("km", "كيلومتر", "كيلومترات", "كيلومتران", false),
        UnitInfo("م", "متر", "أمتار", "متران", false),
        UnitInfo("سم", "سنتيمتر", "سنتيمترات", "سنتيمتران", false),
        UnitInfo("مم", "مليمتر", "مليمترات", "مليمتران", false),
        UnitInfo("inch", "بوصة", "بوصات", "بوصتان", true),
        UnitInfo("ft", "قدم", "أقدام", "قدمان", true),
        UnitInfo("yd", "ياردة", "ياردات", "ياردتان", true),
        UnitInfo("mi", "ميل", "أميال", "ميلان", false),

        // وزن
        UnitInfo("kg", "كيلوغرام", "كيلوغرامات", "كيلوغرامان", false),
        UnitInfo("غ", "غرام", "غرامات", "غرامان", false),
        UnitInfo("ملغ", "مليغرام", "مليغرامات", "مليغرامان", false),
        UnitInfo("lb", "رطل", "أرطال", "رطلان", false),
        UnitInfo("oz", "أونصة", "أونصات", "أونصتان", true),

        // حجم
        UnitInfo("لتر", "لتر", "لترات", "لتران", false),
        UnitInfo("مل", "مليلتر", "مليلترات", "مليلتران", false),
        UnitInfo("غالون", "غالون", "غالونات", "غالونان", false),

        // حرارة
        UnitInfo("°C", "درجة مئوية", "درجات مئوية", "درجتان مئويتان", true),
        UnitInfo(
            "°F", "درجة فهرنهايت", "درجات فهرنهايت", "درجتان فهرنهايت", true
        ),
        UnitInfo("K", "كلفن", "كلفنات", "كلفنان", false),

        // وقت
        UnitInfo("ث", "ثانية", "ثوان", "ثانيتان", true),
        UnitInfo("د", "دقيقة", "دقائق", "دقيقتان", true),
        UnitInfo("س", "ساعة", "ساعات", "ساعتان", true),
        UnitInfo("ي", "يوم", "أيام", "يومان", false),
        UnitInfo("أسبوع", "أسبوع", "أسابيع", "أسبوعان", false),
        UnitInfo("شهر", "شهر", "أشهر", "شهران", false),
        UnitInfo("سنة", "سنة", "سنوات", "سنتان", true)
    )

    // وحدات تخزين البيانات وسرعاتها
    private val DATA_STORAGE_UNITS = setOf(
        "KB", "MB", "GB", "TB", "بايت", "كبت", "مبت", "جببت",
        "KB/s", "MB/s", "GB/s"
    )

    private val UNIT_PATTERNS = UNIT_NAMES.map { info ->
        val pattern = when (info.symbol) {
            "م" -> Pattern.compile(
                """(?<!ساعة\s)(?<!الساعة\s)(?<!عام\s)(?<!سنة\s)\b""" +
                    """(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """${Pattern.quote(info.symbol)}""" +
                    """(?!\s*/\s*ث)(?!\.?\s*ب(?:ايت|ت)?)""" +
                    """(?![\p{L}\p{M}\p{N}_])"""
            )
            "MB" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:(?i:MB)|م\.?\s*ب(?:ايت|\.?)|ميجا\s*بايت)""" +
                    """(?![\p{L}\p{M}\p{N}_])"""
            )
            "KB" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:(?i:KB)|ك\.?\s*ب(?:ايت|\.?)|كيلو\s*بايت)""" +
                    """(?![\p{L}\p{M}\p{N}_])"""
            )
            "GB" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:(?i:GB)|ج\.?\s*ب(?:ايت|\.?)|جيجا\s*بايت)""" +
                    """(?![\p{L}\p{M}\p{N}_])"""
            )
            "TB" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:(?i:TB)|ت\.?\s*ب(?:ايت|\.?)|تيرا\s*بايت)""" +
                    """(?![\p{L}\p{M}\p{N}_])"""
            )
            "MB/s" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:(?i:MB/s)|(?:م\.?\s*ب(?:ايت|\.?)|ميجا\s*بايت)""" +
                    """/(?:ث|ثانية))(?![\p{L}\p{M}\p{N}_])"""
            )
            "KB/s" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:(?i:KB/s)|(?:ك\.?\s*ب(?:ايت|\.?)|كيلو\s*بايت)""" +
                    """/(?:ث|ثانية))(?![\p{L}\p{M}\p{N}_])"""
            )
            "GB/s" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:(?i:GB/s)|(?:ج\.?\s*ب(?:ايت|\.?)|جيجا\s*بايت)""" +
                    """/(?:ث|ثانية))(?![\p{L}\p{M}\p{N}_])"""
            )
            "مبت" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:مبت|م\.?\s*بت|ميجا\s*بت)(?![\p{L}\p{M}\p{N}_])"""
            )
            "كبت" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:كبت|ك\.?\s*بت|كيلو\s*بت)(?![\p{L}\p{M}\p{N}_])"""
            )
            "جببت" -> Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """(?:جببت|ج\.?\s*بت|جيجا\s*بت)(?![\p{L}\p{M}\p{N}_])"""
            )
            else -> {
                val isDataUnit = info.symbol in DATA_STORAGE_UNITS
                val flags = if (isDataUnit) Pattern.CASE_INSENSITIVE else 0
                val symbolPart = if (isDataUnit) {
                    "(?i:${Pattern.quote(info.symbol)})"
                } else {
                    Pattern.quote(info.symbol)
                }
                Pattern.compile(
                    """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                        """$symbolPart(?![\p{L}\p{M}\p{N}_])""",
                    flags
                )
            }
        }
        pattern to info
    }

    // بوابة عدم التطابق: دمج OR صريح لأنماط الوحدات كلها — إن لم يطابق شيئاً
    // أُعيد النص كما هو بلا 38 ممراً وتخصيص سلسلة؛ بدائل الوحدات عربية بلا
    // أرقام فلا يخلق استبدالٌ تطابقاً جديداً، فالسلوك مطابق تماماً للناتج.
    private val UNIT_ANY_PATTERN = Pattern.compile(
        UNIT_PATTERNS.joinToString("|") { "(" + it.first.pattern() + ")" }
    )

    override fun apply(input: String): String {
        if (!UNIT_ANY_PATTERN.matcher(input).find()) return input
        var result = input
        for ((pattern, info) in UNIT_PATTERNS) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val number = AmountParser.parseAmount(matcher.group(1)!!)
                val replacement = numberWithUnit(number, info)
                matcher.appendReplacement(
                    buffer,
                    java.util.regex.Matcher.quoteReplacement(replacement)
                )
            }
            matcher.appendTail(buffer)
            result = buffer.toString()
        }
        return result
    }

    /**
     * نطق عدد مع وحدة قياس مع التوافق النحوي (الجنس والعدد):
     *  1 ← «كيلومتر واحد» / «دقيقة واحدة»،
     *  2 ← المثنى («كيلومتران» / «دقيقتان»)،
     *  3–10 ← الجمع مع اتفاق جنس العدد («خمسة كيلومترات» / «خمس دقائق»)،
     *  ما فوق ← العدد ثم الوحدة المفردة («خمسة وعشرون كيلومتر»).
     */
    private fun numberWithUnit(value: Double, info: UnitInfo): String {
        if (value % 1.0 != 0.0 || value < 0.0) {
            return "${NumberWordsConverter.numberToWords(value)} " +
            info.singular
        }
        val n = value.toInt()
        return when (n) {
            0 -> "${NumberWordsConverter.numberToWords(0.0)} ${info.singular}"
            1 -> "${info.singular} ${if (info.isFeminine) "واحدة" else "واحد"}"
            2 -> info.dual
            in 3..10 ->
                "${NumberWordsConverter.unitNumberWord(n, info.isFeminine)} " +
                    info.plural
            else -> {
                // المعدود المركّب (11–99 فما بين المئات) يلزم آحاده بالمؤنث مع
                // المعدود المؤنث: «خمس وعشرون سنة» لا «خمسة وعشرون سنة».
                // بند 3.3: رفع الحد 9999→99,999,999 (NumberSpeech يدعم).
                val numberText = if (info.isFeminine && n in 11..99_999_999) {
                    NumberSpeech.toArabicWords(n, isFeminine = true)
                } else {
                    NumberWordsConverter.numberToWords(n.toDouble())
                }
                "$numberText ${info.singular}"
            }
        }
    }
}