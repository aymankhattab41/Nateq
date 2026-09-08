package com.aymankhattab.nateq.engine

import android.content.Context
import com.aymankhattab.nateq.settings.SettingsRepository
import java.math.BigDecimal
import java.text.Normalizer
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/** بيانات وحدة قياس: المفرد والجمع والمثنى وجنس الوحدة لدعم التوافق النحوي مع العدد */
private data class UnitInfo(
    val symbol: String,
    val singular: String,
    val plural: String,
    val dual: String,
    val isFeminine: Boolean
)

/**
 * معالج النصوص الذكي - يحول النصوص الخام إلى نصوص قابلة للنطق طبيعياً
 * يدعم: الأرقام، التواريخ، الأوقات، العملات، الوحدات، الاختصارات
 */
class TextProcessor(
    private val context: Context,
    /** المرجع المحقون عبر Hilt إن وُجد (يمرره NateqTtsService)، وإلا يُبنى
     *  محلياً — قراءة لحظية لتفضيل التاريخ الهجري لا أكثر. */
    private val injectedSettings: SettingsRepository? = null
) {

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

// أنماط الأرقام: «\b» المحيط يضمن التقاط المتوالة الرقمية كاملة (المبالغ
        // الطويلة بلا فواصل مثل 10000000 تُنطق «عشرة ملايين») ويمنع شطرها
        // إلى مجموعات ثلاثية، ويُجبر التوسّع ليتجاوز الكسور ذات الخانات الثلاث
        // (3.14159 تُسلم للعشرية كاملة بدل اقتطاع «3.141»).
        private val PATTERN_NUMBER = Pattern.compile("""\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\b""")

        // أنماط أرقام الهواتف: بداية اختيارية + ثم 7-15 رقم مع فواصل (مسافة/شرطة/نقطة)
        // تُحسب الأرقام الفعلية في المعالجة؛ النمط يلتقط المتواليات الطويلة فقط.
        private val PATTERN_PHONE = Pattern.compile("""(?<!\d)\+?\d[\d\s()\-.]{6,}\d(?!\d)""")

        // بادئات اتصال محلية معروفة تُرجّح أن المتوالية الرقمية هاتف وليست مبلغاً:
        // مصر (010/011/012/015…) والبادئات 05–09 الشائعة في السعودية والخليج
        // وشمال أفريقيا وأوروبا.
        private val LOCAL_PHONE_PREFIXES = listOf(
            "010", "011", "012", "015", "016", "017", "018", "019",
            "05", "06", "07", "08", "09"
        )

        // مؤشرات صريحة قبل رقم روماني («الفصل III»، «الجزء II») تُرجّح أنه رقم
        // تسلسلي وليس كلمة إنجليزية مكتوبة بحروف رومانية.
        private val ROMAN_INDICATORS = listOf(
            "الفصل", "الجزء", "الباب", "القسم", "المقدمة", "الملحق", "الفقرة",
            "المادة", "السورة", "المجلد",
            "chapter", "part", "section", "volume", "book", "unit", "lesson", "act"
        )

        // كلمات إنجليزية شائعة مكوّنة من حروف رومانية ظاهرياً (I، DID، MIX، MID،
        // CD…) تُستبعد دائماً من تحويل الأرقام الرومانية.
        private val ENGLISH_ROMAN_WORDS = setOf("I", "ID", "DID", "MIX", "MID", "CD")

        // أنماط الروابط: http(s)://... أو www.example.com — يُنطق اسم النطاق بدل
        // قراءتها حرفاً حرفاً (كانت تُقرأ «أتش تي تي بي نقطة...» المزعجة).
        private val PATTERN_URL = Pattern.compile(
            """(?i)\b((?:https?://|www\.)[^\s<>"']+)"""
        )

        // الأرقام الرومانية (ساعات كبند/فصول/قوائم): تُنطق ككلمات أو أرقام عادية.
        // يعترف فقط بالملييئة وإن كانت كبيرة (IVXLCDM) بحدود كلمات حقيقية؛
        // والسياق (مؤشر صريح أو نطاق 1–12) يُقرَّر في المعالجة.
        private val PATTERN_ROMAN = Pattern.compile("""(?<![\p{Alpha}])[IVXLCDM]{1,8}(?![\p{Alpha}])""")

        // أنماط كود العملة
private val PATTERN_CURRENCY_CODE = Pattern.compile(
            """\b(USD|EUR|GBP|SAR|AED|KWD|QAR|OMR|BHD|EGP|TND|DZD|MAD|JPY|CNY|INR|KRW|RUB)\s+(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\b"""
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
            Pattern.compile("""${Pattern.quote(symbol)}\s*(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\b""") to name
        }
        private val CURRENCY_PATTERNS_AFTER = CURRENCY_SYMBOLS.map { (symbol, name) ->
            Pattern.compile("""\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*${Pattern.quote(symbol)}""") to name
        }

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
            UnitInfo("°F", "درجة فهرنهايت", "درجات فهرنهايت", "درجتان فهرنهايت", true),
            UnitInfo("K", "كلفن", "كلفنات", "كلفنان", false),

            // سرعة
            UnitInfo("كم/س", "كيلومتر في الساعة", "كيلومترات في الساعة", "كيلومتران في الساعة", false),
            UnitInfo("م/ث", "متر في الثانية", "أمتار في الثانية", "متران في الثانية", false),

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
            // (?U) تُفعل أصناف الأحرف اليونيكودية فتعترف \b بالحروف العربية —
            // لولاها لم تُطابق الوحدات العربية («5 م»، «10 سم»، «80 كم/س»)
            // إطلاقاً لأن Java لا تتعامل مع العربية كحروف كلمات.
            Pattern.compile("""(?U)\b(\d+(?:[.,]\d{3})*(?:[.,]\d+)?)\s*${Pattern.quote(info.symbol)}\b""") to info
        }

        // رموز تُستبدل دائماً (معناها ثابت لا يتبدل بسياق):
        private val SYMBOL_NAMES_GENERAL = mapOf(
            "%" to "بالمئة",
            "٪" to "بالمئة",
            "°C" to "درجة مئوية",
            "°F" to "درجة فهرنهايت",
            "°" to "درجة",
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
            "&" to "و",
            "|" to "أو",
            "~" to "تقريباً",
            "_" to "شرطة سفلية",
            "\\" to "شرطة مائلة عكسية"
        )

        // رموز حسابية تُستبدل فقط بين رقمين (فلا تتحول "ملاحظة - هام" إلى
        // "ملاحظة ناقص هام"، ولا تعارَض كلمة عادية معها). الكسر 1/2 يُنطق
        // «واحد على اثنين» كما في العربية السياقية.
        private val SYMBOL_ARITHMETIC = mapOf(
            "+" to " زائد ",
            "-" to " ناقص ",
            "×" to " في ",
            "÷" to " على ",
            "/" to " على ",
            "*" to " في ",
            "=" to " يساوي "
        )

        // @: لا تُستبدل داخل بريد إلكتروني (حرف/رقم على طرفيها)، بل فقط
        // حين تكون معزولة (مثل "نلتقي @ 5").
        private val PATTERN_AT = Pattern.compile("(?<!\\p{L})(?<![0-9])@(?![0-9])(?!\\p{L})")

        // كلمات الأعداد (آحاد/مراهقين/عشرات/مئات) لـ numberToWords — مرجعية
        // مشتركة بين المسارين الطويل (Long) والعشري (String).
        private val UNITS_WORDS = arrayOf("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة")
        private val TEENS_WORDS = arrayOf("عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر")
        private val TENS_WORDS = arrayOf("", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون")
        private val HUNDREDS_WORDS = arrayOf("", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة")

        /** أنماط منتهية تجمع الرموز العامة (الأطول أولاً لضمان °C قبل °) ثم
         *  الحسابية المقيدة بين الرقمين ثم @ المعزولة. */
        private val SYMBOL_PATTERNS: List<Pair<Pattern, String>> = buildList {
            addAll(
                SYMBOL_NAMES_GENERAL.entries.sortedByDescending { it.key.length }
                    .map { Pattern.compile(Pattern.quote(it.key)) to it.value }
            )
            addAll(
                SYMBOL_ARITHMETIC.map { (op, word) ->
                    // لاحظ: \Q..\E لإبعاد الرموز الخاصة (بما فيها * و /) عن المعنى النمطي.
                    Pattern.compile("(?<=\\d)\\s*\\Q$op\\E\\s*(?=\\d)") to word
                }
            )
            add(PATTERN_AT to " عند ")
        }
    }

private val pronunciationDict = PronunciationDictionary(context)

    /** هل نطق أسماء الإيموجي مفعّل؟ بلا حقنة Settings (الاختبارات) يُفترض مفعّل. */
    private val emojiEnabled: Boolean
        get() = injectedSettings?.isEmojiPronunciationEnabled() ?: true

    /**
     * معالجة نص كامل وتحويله لصيغة نطق طبيعية.
     * @param languageTag كود اللغة (مثلاً "ar"، "en"، "ar-EG")
     *                    — المعالجة مخصصة للغة العربية فقط؛ اللغات الأخرى تُعاد كما هي.
     */
    fun process(text: String, languageTag: String = "ar"): String {
        if (text.isBlank()) return text

        // نطق أسماء الإيموجي (بدل حذفها) قبل مسار العربية ليغطي الإنجليزية
        // واللغات الأخرى أيضاً — الناتج لا يُمرَّر لأي تحويل لاحق خارج العربية.
        val expanded = if (emojiEnabled) expandEmojis(text, languageTag) else null

        // المعالجة مخصصة للعربية فقط؛ الإنجليزية واللغات الأخرى تُعاد كما هي
        // بعد توسيع الإيموجي فقط (لا يجوز تحويل أرقام إنجليزية إلى كلمات عربية)
        if (languageTag.startsWith("ar").not()) {
            return if (expanded != null) cleanupSpaces(expanded) else text
        }

        // text قد يحوي إيموجي عُرضت أسماؤها (expanded) أو تُحذف لاحقاً (expandEmojis == null)
        var result = expanded ?: text

        // 0. تطبيع الأرقام الشرقية (٠١٢٣٤٥٦٧٨٩) إلى غربية (0123456789)
        //    لأن أنماط \d في Java لا تطابق الأرقام الشرقية
        result = normalizeIndicDigits(result)

        // 0.05 تجريد التشكيل العربي (حركات/تنوين/شدّة/سكون/كشيدة) قبل كل المطابقات
        //    حتى يطابق القاموس والأنماط الكلماتَ المشكولة وتتوقف الأخطاء النطقية
        result = stripTashkeel(result)

        // عند تعطيل نطق الإيموجي: السلوك السابق — إزالتها وتنظيف التركيبات
        if (expanded == null) result = stripEmojis(result)

// 1. تطبيق القاموس الشخصي أولاً (أعلى أولوية)
        result = pronunciationDict.apply(result)

        // 1.5 المسار السريع (Fast-path): إن لم يحتوِ النص على أي محفِّز لأرقام
        //     الرموز/الصيغ (أرقام، فواصل، رموز عملة، حروف رومانية...) — أي نص
        //     عربي صافٍ بلا أرقام — نتخطى كل مراحل regex الثقيلة (التواريخ/
        //     الأوقات/العملات/الروابط/الرومانية/الهواتف/الأرقام/الرموز) ونذهب
        //     مباشرةً لتنظيف المسافات. يوفّر تريليونات المطابقات على كل إعلان.
        if (!requiresRegexPipeline(result)) {
            return cleanupSpaces(result)
        }

        // 2. معالجة التواريخ
        result = processDates(result)

        // 3. معالجة الأوقات
        result = processTimes(result)

        // 4. معالجة العملات
result = processCurrencies(result)

        // 5. معالجة الوحدات
        result = processUnits(result)

        // 5.3 معالجة الروابط: استخراج اسم النطاق ونطقه (مع التعامل مع subdomain)
        result = processUrls(result)

        // 5.4 معالجة الأرقام الرومانية (الساعات/الفصول) بأنطقها كأرقام
        result = processRomanNumerals(result)

        // 5.5 معالجة أرقام الهواتف (تُنطق رقماً رقماً قبل الأرقام العادية)
        // نمرّر العربية من languageTag لا من فحص النص: رقم هاتف وحيد (بلا حروف
        // عربية) كان يُنطق إنجليزياً خطأً في السياق العربي (رقم مصري يبدأ 01…).
        result = processPhoneNumbers(result, languageTag.startsWith("ar"))

// 6. معالجة الرموز الشائعة قبل الأرقام: العمليات الحسابية («5 + 3») تتطلب
        //    بقاء الأرقام خام (أرقام غربية) حتى تنطبق أنماطها (?<=\d)…(?=\d)؛
        //    لو تحولت الأرقام كلمات أولاً (خمسة + ثلاثة) لتعطل النطق تماماً.
        result = processSymbols(result)

        // 7. معالجة الأرقام العادية
        result = processNumbers(result)

// 8. تنظيف المسافات الزائدة
        result = cleanupSpaces(result)

        return result
    }

    /**
     * فحص سريع لكل الحروف: هل يحتوي النص أي محفِّز يستدعي مراحل regex الثقيلة؟
     * المحفِّزات هي: أي حرف/رقم لاتيني (أرقام/فواصل/رموز/حروف رومانية وعملات
     * ورسميات مثل USD/SAR)، رموز العملة (€¥₹…)، أي رمز حسابي/عام، وعلامات عربية
     * خاصة (٪، ﷼) — فإذا خلا النص منها (عربي خالص بلا أرقام) نتخطى كل المراحل
     * ونكتفي بالتنظيف، فيتسارع معالجة السنة/الرسائل/الإشعارات العادية بشكل كبير.
     */
    private fun requiresRegexPipeline(text: String): Boolean {
        for (i in text.indices) {
            val code = text[i].code
            when {
                // أي حرف/رقم/علامة لاتينية (استثناء الفراغ) — يشمل الأرقام
                // والفواصل ورموز العمليات و @ و # وحروف العملات والرومانية
                // والرسمية، وعلامات مثل ° × ÷ (كلها دون U+0600).
                code < 0x600 && code != 0x20 -> return true
                // ٪ (عربي للمئة) — تُستبدل في SYMBOL_NAMES_GENERAL
                code == 0x66A -> return true
                // رموز عملة خارج ASCII (€¥₹…)
                code in 0x20A0..0x20CF -> return true
                // رموز النظام الرياضية/المنطقية (≥≤≠≈∞√…)
                code in 0x2200..0x22FF -> return true
                // باي اليوناني (يُستبدل في SYMBOL_NAMES_GENERAL)
                code == 0x03C0 -> return true
                // ﷼ (ريال سعودي)
                code == 0xFDFC -> return true
            }
        }
        return false
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

    /** معالجة أرقام الهواتف: تُنطق رقماً رقماً بدل إغلاقها كعدد كامل
     * («خمسمائة وواحد مليون…»). يعترف بأرقام من 7 إلى 15 خانة مع فواصل اختيارية
     * (مسافة/شرطة/نقطة/أقواس) وبداية + اختيارية. */
    private fun processPhoneNumbers(text: String, isArabicContext: Boolean): String {
        val matcher = PATTERN_PHONE.matcher(text)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val raw = matcher.group(0)!!
            val digits = raw.filter { it.isDigit() }
            // تحقق إضافي ضد التطابقات الكاذبة: تواريخ (12.12.2024) وعناوين IP
            // (192.168.1.100) ليست هواتف رغم وقوع أرقامها ضمن المدى 7..15.
            if (looksLikeDate(raw) || looksLikeIpAddress(raw)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(raw))
                continue
            }
            // تقبّل فقط ما يقع في مدى أرقام الهواتف الشائعة؛ ما عداه يُترك كما هو.
            if (digits.length !in 7..15) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(raw))
                continue
            }
            // لا تُعامل المتوالية الرقمية كهاتف إلا بدليل قاطع: مفتاح دولي (+)
            // أو بادئة اتصال محلية معروفة أو فواصل هاتفية قياسية — وإلا فتُترك
            // للمعالجة الرقمية («10000000» مبلغ = عشرة ملايين لا هاتف يُنطق رقماً).
            if (!isLikelyPhone(raw, digits)) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(raw))
                continue
            }
            // لغة النطق تأتي من سياق المعالجة (languageTag) لا من أحرف النص:
            // النص الإنجليزي عاد مبكراً في process()، والرقم المجرد يُنطق
            // عربياً في السياق العربي.
            val isArabic = isArabicContext
            val spokenDigits = digits.map { it.digitToInt() }
                .joinToString(" ") {
                    // الأرقام تُنطق كأرقام مجردة (مذكرة): «خمسة» لا «خمس».
                    if (isArabic) NumberSpeech.toArabicWords(it, isFeminine = false)
                    else NumberSpeech.toEnglishWords(it)
                }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(spokenDigits))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** هل التطابق يشبه تاريخاً (3 مجموعات رقمية مفصولة بنقطة/شرطة، آخرها 2-4 أرقام)؟ */
    private fun looksLikeDate(raw: String): Boolean {
        val parts = raw.split(Regex("""[-/.]""")).filter { it.isNotBlank() }
        if (parts.size != 3) return false
        val lens = parts.map { it.length }
        // يوم/شهر (1-2) وسنة (2-4) — الأجزاء الثلاثة كلها أرقام خالصة
        if (parts.any { !it.all(Char::isDigit) }) return false
        return lens[0] in 1..2 && lens[1] in 1..2 && lens[2] in 2..4
    }

    /** هل التطابق عنوان IP (4 مجموعات من 1-3 أرقام مفصولة بنقاط، ودون علامة +)؟ */
    private fun looksLikeIpAddress(raw: String): Boolean {
        if (raw.startsWith("+")) return false
        val parts = raw.split('.')
        if (parts.size != 4) return false
        return parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
    }

    /** ترجيح كون المتوالية رقم هاتف فعلياً (لا مبلغاً أو عدداً مجرداً). */
    private fun isLikelyPhone(raw: String, digits: String): Boolean {
        // مفتاح اتصال دولي صريح (+20 …)
        if (raw.startsWith("+")) return true
        // بادئة اتصال محلية معروفة (010 مصر، 05 السعودية…)
        if (LOCAL_PHONE_PREFIXES.any { digits.startsWith(it) }) return true
        // مجموعات آلاف أوروبية/فرنسية (1 000 000، 12.345.678) ليست هواتف
        if (looksLikeThousandsGrouping(raw)) return false
        // فواصل هاتفية قياسية (مسافة/شرطة/أقواس/نقطة)
        return raw.any { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
    }

    /** هل التطابق مجرد تجميع آلاف بفواصل (تنسيق أوروبي) وليس هاتفاً؟ */
    private fun looksLikeThousandsGrouping(raw: String): Boolean {
        val groups = raw.split(Regex("""[\s().\-]+""")).filter { it.isNotBlank() }
        if (groups.size < 2) return false
        if (groups.first().length !in 1..3) return false
        return groups.drop(1).all { it.length == 3 && it.all(Char::isDigit) }
    }

    /**
     * معالجة الروابط: تحويل «https://example.com/path?q=1» إلى نطق دال على
     * اسم النطاق («موقع example.com») بدل قراءة الشعار والمحارف حرفاً حرفاً.
     * يُحفظ اسم النطاق ليُنطق كما هو (مقروء، فذلك أفضل لمواقع مكتوبة بحروف
     * لاتينية) وتُحذف بقية أجزاء الرابط بصمت.
     */
    private fun processUrls(text: String): String {
        val matcher = PATTERN_URL.matcher(text)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val raw = matcher.group(1)!!
            // نستخرج اسم النطاق: www.example.com أو example.com أو example.com:8080/path
            var host = raw
                .removePrefix("https://").removePrefix("http://")
                .removePrefix("www.")
            // قطع كل ما بعد أول / أو ? أو # (المسار/الاستعلام/الربط)
            val slash = host.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (slash >= 0) host = host.substring(0, slash)
            // إزالة المنفذ إن وجد (example.com:8080) وعلامات الترقيم الختامية
            host = host.substringBefore(":").trimEnd('.', ',', '،', ')', ';', '!', '؟')
            if (host.isBlank()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(raw))
                continue
            }
            // نطق «موقع» + اسم النطاق مقروءاً (أنسب للمواقع المكتوبة بحروف لاتينية
            // من القراءة حرفاً حرفاً). تُحذف اللواحق الشائعة (com/net/org) للاختصار.
            val name = when {
                host.endsWith(".com") || host.endsWith(".net") || host.endsWith(".org") ->
                    host.substringBeforeLast('.')
                else -> host
            }
            val spoken = "موقع $name"
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(spoken))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * معالجة الأرقام الرومانية: «III» كرقم ساعة → «ثلاثة»، «XIV» → «أربعة عشر».
     * يُتحقق من الصحة النحوية (نقصان/زيادة) قبل التحويل؛ إن كانت متوالية
     * رومانية غير صحيحة (مثل تاريخ «MMXXIV») تُترك كما هي للتواريخ.
     */
    private fun processRomanNumerals(text: String): String {
        val matcher = PATTERN_ROMAN.matcher(text)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val rom = matcher.group(0)!!
            val value = romanToInt(rom) ?: run {
                // غير صالح/غير معترف → لا نلمسه (ربما تاريخ أو اختصار)
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(rom))
                continue
            }
            // كلمات إنجليزية شائعة من حروف رومانية (DID/MIX/MID/CD/I) تُستبعد
            // دائماً مهما كان السياق.
            if (rom in ENGLISH_ROMAN_WORDS) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(rom))
                continue
            }
            // دون مؤشر صريح لا نُحوّل إلا الأرقام التسلسلية 1–12 (ساعات/قوائم)؛
            // ما عداها تُرك — لا نجعل «الفصل MCMXCV» تفقّد سياقها ولا نجعل كلمة
            // إنجليزية عابرة رقمَ ساعة.
            if (!hasRomanIndicatorBefore(text, matcher.start()) && value !in 1..12) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(rom))
                continue
            }
            val spoken = numberToWords(value.toLong())
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(spoken))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** هل يسبق التطابق مؤشر صريح («الفصل»، «الجزء»، «chapter»…)؟ */
    private fun hasRomanIndicatorBefore(text: String, start: Int): Boolean {
        var before = text.substring(0, start).trimEnd()
        while (before.isNotEmpty() && !before.last().isLetterOrDigit()) before = before.dropLast(1)
        if (before.isEmpty()) return false
        val lower = before.lowercase()
        return ROMAN_INDICATORS.any { ind ->
            val indLower = ind.lowercase()
            if (!lower.endsWith(indLower)) return@any false
            val prefixLen = lower.length - indLower.length
            prefixLen == 0 || !lower[prefixLen - 1].isLetterOrDigit()
        }
    }

    /** تحويل رقم روماني إلى Int، أو null عند تركيبة غير صالحة. */
    private fun romanToInt(s: String): Int? {
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (c in s.reversed()) {
            val v = map[c] ?: return null
            if (v < prev) total -= v else total += v
            prev = v
        }
        // تحقق من الصحة: لا تكرار لأكثر من 3 لـ I/X/C، ولا 4 لـ V/L/D.
        val repeats = listOf('I', 'X', 'C', 'M').any { ch -> s.filter { it == ch }.length > 3 }
            || listOf('V', 'L', 'D').any { ch -> s.filter { it == ch }.length > 1 }
        // قيمة معقولة كرقم ترتيبي (تجنب تحويل CC/DD/MM التواريخ إلى أرقام)
        if (repeats || total > 3999) return null
        return total
    }

    /** تحليل مبلغ رقمي مع تمييز صحيح بين فاصلة الآلاف وفاصلة الكسور:
     * فاصلة تليها ثلاث خانات بالضبط تُعتبر فاصلة آلاف (تُحذف)،
     * وأي فاصلة أخرى تُعتبر فاصلة كسور (تُستبدل بنقطة).
     * @return القيمة العددية أو صفراً عند تعذر الفهم (لا نهيار للنطق).
     */
    private fun parseAmount(raw: String): Double {
        val cleaned = sanitizeNumerals(raw)
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    /** معالجة العملات: $100 → مائة دولار، 50€ → خمسون يورو */
    private fun processCurrencies(text: String): String {
        var result = text

        // رموز قبل المبلغ: $100
        for ((pattern, name) in CURRENCY_PATTERNS_BEFORE) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val amount = parseAmount(matcher.group(1)!!)
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
                val amount = parseAmount(matcher.group(1)!!)
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
            val amount = parseAmount(codeMatcher.group(2)!!)
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
        for ((pattern, info) in UNIT_PATTERNS) {
            val matcher = pattern.matcher(result)
            val buffer = StringBuffer()
            while (matcher.find()) {
                val number = parseAmount(matcher.group(1)!!)
                val replacement = numberWithUnit(number, info)
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement))
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
            return "${numberToWords(value)} ${info.singular}"
        }
        val n = value.toInt()
        return when (n) {
            0 -> "${numberToWords(0.0)} ${info.singular}"
            1 -> "${info.singular} ${if (info.isFeminine) "واحدة" else "واحد"}"
            2 -> info.dual
            in 3..10 -> "${unitNumberWord(n, info.isFeminine)} ${info.plural}"
            else -> "${numberToWords(n.toDouble())} ${info.singular}"
        }
    }

    /**
     * لفظ العدد (3–10) مع مراعاة قاعدة العدد في العربية:
     * العدد يأخذ صيغة مؤنثة مع المعدود المذكر (خمسة كيلومترات)
     * وصيغة مذكرة مع المعدود المؤنث (خمس دقائق).
     */
    private fun unitNumberWord(digit: Int, isFeminine: Boolean): String {
        // الفهرس يعادل الرقم بالضبط (لا انزياح): كان المصفوفة تحذف منزلة
        // (خمسة → «ستة أمتار») لغياب العنصر الأول.
        val forMasculine = arrayOf("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة", "عشرة")
        val forFeminine  = arrayOf("", "واحدة", "اثنتان", "ثلاث", "أربع", "خمس", "ست", "سبع", "ثمان", "تسع", "عشر")
        val table = if (isFeminine) forFeminine else forMasculine
        return if (digit in 3..10) table[digit] else ""
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
        // الفصل بين فواصل الآلاف والفاصلة العشرية يتم عبر sanitizeNumerals
        // الذي لا يُهلك الأعداد العشرية ثلاثية الخانات (3.141 تبقى عشرية).
        val cleaned = sanitizeNumerals(numberStr)
        val number = cleaned.toDoubleOrNull() ?: return numberStr
        return numberToWords(number)
    }

    /**
     * تنظيف تمثيل رقمي خام بفصل دقيق بين فاصل الآلاف وفاصلة الكسور:
     *  - فاصل آلاف يُحذف (1,234 → 1234، 1.234.567 → 1234567)
     *  - فاصلة كسور تُوحَّد إلى نقطة (1,5 → 1.5، 1.234,56 → 1234.56)
     * قاعدة التمييز: آخر فاصل يُعتبر كسوراً إلا إذا كان طرفه ثلاثي الخانات
     * ضمن متوالية آلاف منسجمة (فحص تنوّع الرموز). الفاصلة المنفردة بطرف ثلاثي
     * (3.141) تبقى عشرية فلا تُفسد الأعداد العشرية كأعداد صحيحة.
     */
    private fun sanitizeNumerals(raw: String): String {
        val seps = mutableListOf<Int>()
        for (i in raw.indices) {
            val ch = raw[i]
            val digitAround = i > 0 && raw[i - 1].isDigit() && i + 1 < raw.length && raw[i + 1].isDigit()
            if ((ch == ',' || ch == '.') && digitAround) seps.add(i)
        }
        if (seps.isEmpty()) return raw

        val lastIdx = seps.last()
        val lastCh = raw[lastIdx]
        val tailLen = raw.length - lastIdx - 1

        val lastIsDecimal = when {
            tailLen != 3 -> true
            seps.size == 1 -> lastCh == '.'   // فاصلة وحيدة + طرف ثلاثي: (,) آلاف أمريكية، (.) عشرية
            else -> seps.any { raw[it] != lastCh }  // تنوّع الرموز: الأخيرة كسور (1,234.567)، وإلا فكلها آلاف
        }

        val sb = StringBuilder(raw.length)
        for (i in raw.indices) {
            when {
                i == lastIdx && lastIsDecimal -> sb.append('.')
                i in seps -> Unit
                else -> sb.append(raw[i])
            }
        }
        return sb.toString()
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
        if (day !in 1..31) return "التاريخ غير صالح"
        // مسار الهجري: إن فشل التحويل (نادر) نتراجع للصيغة الميلادية الصحيحة
        // ولا نُمرر قيماً ميلادية عبر أسماء الشهور الهجرية (كان ينتج نطقاً مختلطاً
        // مثل «خمسة عشر محرم 2024»).
        if (runCatching {
                (injectedSettings ?: SettingsRepository(context)).isHijriDateEnabled()
            }.getOrDefault(false)
        ) {
            val hijri = runCatching { toHijri(day, month, year) }.getOrNull()
            if (hijri != null && hijri.second in 1..12 && hijri.first in 1..30) {
                return "${numberToWords(hijri.first.toLong())} ${HIJRI_MONTHS[hijri.second]} " +
                    "${numberToWords(hijri.third.toLong())}"
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

        fun minutesPart(count: Int): String = when (count) {
            1 -> "دقيقة واحدة"
            2 -> "دقيقتان"
            in 3..10 -> "${NumberSpeech.toArabicWords(count)} دقائق"
            else -> "${NumberSpeech.toArabicWords(count)} دقيقة"
        }

        // صيغة دقائق سياق «إلا» (منصوبة): «إلا خمس دقائق»، «إلا دقيقة واحدة»، «إلا دقيقتين»
        fun minutesOmissionPart(count: Int): String = when (count) {
            1 -> "دقيقة واحدة"
            2 -> "دقيقتين"
            in 3..10 -> "${NumberSpeech.toArabicWords(count)} دقائق"
            else -> "${NumberSpeech.toArabicWords(count)} دقيقة"
        }

        return when (minute) {
            0 -> "$hourText $period"
            15 -> "$hourText والربع $period"
            30 -> "$hourText والنصف $period"
            45 -> {
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextHourText = numberToWords(nextHour.toLong())
                "$nextHourText إلا ربع $period"
            }
            in 1..29 -> "$hourText و ${minutesPart(minute)} $period"
            in 31..44 -> "$hourText و ${minutesPart(minute)} $period"
            in 46..59 -> {
                val remaining = 60 - minute
                val nextHour = if (hour12 == 12) 1 else hour12 + 1
                val nextHourText = numberToWords(nextHour.toLong())
                "$nextHourText إلا ${minutesOmissionPart(remaining)} $period"
            }
            else -> "$hourText $period"
        }
    }

    /** تطبيع الأرقام الشرقية والفارسية والهندية إلى غربية — يفوّض إلى util المشترك */
    private fun normalizeIndicDigits(text: String): String {
        return com.aymankhattab.nateq.util.LocaleUtils.normalizeIndicDigits(text)
    }

    /**
     * تجريد التشكيل العربي من النص (حركات، تنوين، شدّة، سكون، تطويل/كشيدة،
     * والعلامات الإملائية الرافدة) دون لمس الحروف أو فواصل الكلمات.
     * ضروري قبل مطابقة الأنماط والقواميس: علامات Unicode الفاصلة عن الحرف
     * (Mn) تجعل [Character.isLetter] تعود false وتفكك تعبيرات regex العربية،
     * كما أن الكلمة المشكولة لا تُطابق مدخلات القاموس المكتوبة بلا تشكيل.
     * تُطبَّق على مدخل المعالجة فقط، وبالتالي لا تُحذف من نصٍّ ليس عربياً.
     */
    private fun stripTashkeel(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val cp = ch.code
            // نطاقات التشكيل العربي الكاملة (U+0610–U+061A، U+064B–U+065F،
            // U+0670–U+0673) بالإضافة للتطويل/الكشيدة (U+0640). يُضاف نطاق
            // «العربية الممتدة - A» (U+08A0–U+08FF) لا نجرّد منه إلا الحركات
            // الخالصة (U+08D3–U+08E1 و U+08E3–U+08FF) لأن النطاق كاملاً يحوي
            // حروفاً هجائية للأوردو والبشتو واللغات الأفريقية ورسم المصحف (كالباء
            // ذات النقطة السفلية) — مسحها كان يشوّه الكلمات ويحذف حروفاً أصلية.
            if (cp in 0x0610..0x061A || cp == 0x0640 || cp in 0x064B..0x065F ||
                cp in 0x0670..0x0673 || cp in 0x08D3..0x08E1 || cp in 0x08E3..0x08FF
            ) continue
            sb.append(ch)
        }
        return sb.toString()
    }

/**
     * توسيع الإيموجي ورموز المشاعر إلى أسمائها القابلة للنطق (عربي/إنجليزي
     * حسب languageTag). يشمل: كودات Unicode الموثّقة في EmojiNames، الإيموجي
     * النصيّ (☺), رموز المشاعر النصية (":)", ":) ", "<3"…)، والأعلام (رمزا
     * منطقة متجاوران). الإيموجي غير الموثّق يُنطق بالكلمة العامة الثابتة.
     * تُسقط تعديلات ألوان البشرة ومؤشرات الأشكال و ZWJ بصمت، ويُدمج
     * الإيموجي المركّب (عائلة/مهنة) باسم أول مكوّن. عند تعطيل المفتاح لا
     * تُستدعى هذه الدالة (تُستخدم stripEmojis بدلها).
     */
    private fun expandEmojis(text: String, languageTag: String): String {
        val arabic = languageTag.startsWith("ar")
        val fallback = if (arabic) EmojiNames.AR_FALLBACK else EmojiNames.EN_FALLBACK
        val base = EmojiNames.applyAsciiEmoticons(text, arabic)
        val sb = StringBuilder(base.length)
        var i = 0
        val len = base.length
        while (i < len) {
            val cp = base.codePointAt(i)
            val chars = Character.charCount(cp)
            when {
                EmojiNames.isEmojiModifier(cp) -> {
                    // تعديلات منفصلة (ZWJ/ألوان بشرة/مؤشر أشكال) تُسقط بصمت
                    i += chars
                }
                EmojiNames.isRegionalIndicator(cp) -> {
                    val nextIdx = i + chars
                    if (nextIdx < len) {
                        val next = base.codePointAt(nextIdx)
                        if (EmojiNames.isRegionalIndicator(next)) {
                            val code = EmojiNames.buildCountryCode(cp, next)
                            sb.append(' ').append(EmojiNames.flagReadingName(code, arabic))
                            i = nextIdx + Character.charCount(next)
                            continue
                        }
                    }
                    // علم غير مكتمل (رمز واحد بلا قرين): نطق عام
                    sb.append(' ').append(fallback)
                    i += chars
                }
                EmojiNames.isEmojiBlockCp(cp) -> {
                    val name = if (arabic) EmojiNames.arName(cp) else EmojiNames.enName(cp)
                    sb.append(' ').append(name ?: fallback)
                    i += chars
                    // تجاوز بقية المجموعة: ألوان بشرة، مؤشرات أشكال، وعناصر
                    // ما بعد ZWJ (عائلة/مهنة) حتى لا تُنطق مقاطع متناثرة
                    var zwjSeen = false
                    while (i < len) {
                        val c2 = base.codePointAt(i)
                        val c2chars = Character.charCount(c2)
                        when {
                            c2 in 0x1F3FB..0x1F3FF || c2 in 0xFE0E..0xFE0F -> i += c2chars
                            c2 == 0x200D -> {
                                zwjSeen = true; i += c2chars
                            }
                            EmojiNames.isEmojiBlockCp(c2) && zwjSeen -> {
                                i += c2chars; zwjSeen = false
                            }
                            else -> break
                        }
                    }
                }
                else -> {
                    sb.append(base, i, i + chars)
                    i += chars
                }
            }
        }
        return Normalizer.normalize(sb.toString().trim(), Normalizer.Form.NFC)
    }

    /**
     * معالجة الإيموجي عند تعطيل «نطق الإيموجي»: إزالة الإيموجي وتركيباتها
     * المعقدة (ZWJ, skin tone modifiers, variation selectors, flags) بطريقة
     * آمنة حتى لا تشوّش النطق. تُستبدل بمسافة للحفاظ على الفصل بين الكلمات.
     */
    private fun stripEmojis(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val len = text.length
        while (i < len) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)

// نطاقات الإيموجي الأساسي (ما عدا العربية والعامة)
            val isEmoji = isEmojiCodePoint(cp)

            // تعديلات variation selectors و ZWJ (ألوان البشرة تَشمَلها
            // نطاقات الإيموجي 1F300-1FAFF فتُستبدل بمسافة تلقائياً).
            val isModifier = cp in 0xFE00..0xFE0F || cp == 0x200D

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
            cp in 0x1F000..0x1F1FF ||  // الكتل المكملة: ماهجونغ/دومينو/لعب/أعلام
            cp == 0xFE0F // مؤشر شكل الإيموجي (variation selector)
    }

/** تحويل رقم لكلمات عربية (يدعم حتى التريليونات، والكسور العشرية) */
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

    /** مقياس مجموعة الأرقام (آلاف/ملايين/مليارات/تريليونات/كوادريليون/كوينتيليون). */
    private fun scaleForGroup(groupIndex: Int, group: Int, groupText: String): String {
        if (groupIndex == 0) return ""
        return when (groupIndex) {
            1 -> when (group) { 1 -> "ألف"; 2 -> "ألفان"; in 3..10 -> "$groupText آلاف"; else -> "$groupText ألفاً" }
            2 -> when (group) { 1 -> "مليون"; 2 -> "مليونان"; in 3..10 -> "$groupText ملايين"; else -> "$groupText مليوناً" }
            3 -> when (group) { 1 -> "مليار"; 2 -> "ملياران"; in 3..10 -> "$groupText مليارات"; else -> "$groupText ملياراً" }
            4 -> when (group) { 1 -> "تريليون"; 2 -> "تريليونان"; in 3..10 -> "$groupText تريليونات"; else -> "$groupText تريليوناً" }
            5 -> when (group) { 1 -> "كوادريليون"; 2 -> "كوادريليونان"; in 3..10 -> "$groupText كوادريليونات"; else -> "$groupText كوادريليوناً" }
            6 -> when (group) { 1 -> "كوينتيليون"; 2 -> "كوينتيليونان"; in 3..10 -> "$groupText كوينتيليونات"; else -> "$groupText كوينتيليوناً" }
            else -> ""
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
}
