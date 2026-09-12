package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة الوحدات: 5km → «خمسة كيلومترات»، 25°C → «خمس وعشرون درجة مئوية» */
internal object UnitStep : TextProcessingStep {

    private val UNIT_NAMES = listOf(
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

        // سرعة
        UnitInfo(
            "كم/س", "كيلومتر في الساعة", "كيلومترات في الساعة",
            "كيلومتران في الساعة", false
        ),
        UnitInfo(
            "م/ث", "متر في الثانية", "أمتار في الثانية",
            "متران في الثانية", false
        ),

        // بيانات
        UnitInfo("KB", "كيلوبايت", "كيلوبايتات", "كيلوبايتان", false),
        UnitInfo("MB", "ميجابايت", "ميجابايتات", "ميجابايتان", false),
        UnitInfo("GB", "جيجابايت", "جيجابايتات", "جيجابايتان", false),
        UnitInfo("TB", "تيرابايت", "تيرابايتات", "تيرابايتان", false),
        UnitInfo("كبت", "كيلوبت", "كيلوبتات", "كيلوبتان", false),
        UnitInfo("مبت", "ميجابت", "ميجابتات", "ميجابتان", false),
        UnitInfo("جببت", "جيجابت", "جيجابتات", "جيجابتان", false),

        // وقت
        UnitInfo("ث", "ثانية", "ثوان", "ثانيتان", true),
        UnitInfo("د", "دقيقة", "دقائق", "دقيقتان", true),
        UnitInfo("س", "ساعة", "ساعات", "ساعتان", true),
        UnitInfo("ي", "يوم", "أيام", "يومان", false),
        UnitInfo("أسبوع", "أسبوع", "أسابيع", "أسبوعان", false),
        UnitInfo("شهر", "شهر", "أشهر", "شهران", false),
        UnitInfo("سنة", "سنة", "سنوات", "سنتان", true)
    )

    private val UNIT_PATTERNS = UNIT_NAMES.map { info ->
        // حدود الكلمات مُعرَّفة يدوياً بلا وسم (?U): هو وسم Java لا تدعمه ICU4C
        // (محرك java.util.regex في أندرويد) فيُسقط تحليل النمط خطأً في ART.
        // البداية \b الصفة ASCII كافية (الأرقام غربية = حروف كلمات)، وتُعرّف
        // الحدُّ الختاميُّ بإلغاء حرف الكلمة يونيكود (?![\p{L}\p{N}_]) ليعترف
        // بحدود الكلمات العربية كما كان يفعل (?U)\b بالضبط (5 م ثم حرف = لا
        // تطابق؛ ثم مسافة/ترقيم/نهاية = تطابق).
        // **بند 3.8 (الوحدة «م» حصراً):** «م» المفردة بعد رقمٍ تُفسَّر متراً
        // إلا في سياقات الزمن والتقويم حيث هي اختصار «مساءً»/«ميلادي» —
        // نظرة خلفية سالبة تستبعد السابقة «ساعة»/«الساعة» (توقيت: «الساعة
        // 5 م») و«عام م»/«سنة م» (ميلادي: «عام 2024 م») فتُترك لخطوات الزمن
        // والتاريخ؛ Lookbehind ثابتة الطول (متطلب Java) لكل لفظٍ على حدة.
        val pattern = if (info.symbol == "م") {
            Pattern.compile(
                """(?<!ساعة\s)(?<!عام\s)(?<!سنة\s)\b""" +
                    """(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """${Pattern.quote(info.symbol)}(?![\p{L}\p{N}_])"""
            )
        } else {
            Pattern.compile(
                """\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*""" +
                    """${Pattern.quote(info.symbol)}(?![\p{L}\p{N}_])"""
            )
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
                val numberText = if (info.isFeminine && n in 11..9999) {
                    NumberSpeech.toArabicWords(n, isFeminine = true)
                } else {
                    NumberWordsConverter.numberToWords(n.toDouble())
                }
                "$numberText ${info.singular}"
            }
        }
    }
}