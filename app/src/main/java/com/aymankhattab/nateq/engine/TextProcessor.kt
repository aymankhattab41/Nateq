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

// أنماط الأرقام
        private val PATTERN_NUMBER = Pattern.compile("""(\d{1,3}(?:[.,]\d{3})*(?:[.,]\d+)?)""")

        // أنماط أرقام الهواتف: بداية اختيارية + ثم 7-15 رقم مع فواصل (مسافة/شرطة/نقطة)
        // تُحسب الأرقام الفعلية في المعالجة؛ النمط يلتقط المتواليات الطويلة فقط.
        private val PATTERN_PHONE = Pattern.compile("""(?<!\d)\+?\d[\d\s()\-.]{6,}\d(?!\d)""")

        // أنماط الروابط: http(s)://... أو www.example.com — يُنطق اسم النطاق بدل
        // قراءتها حرفاً حرفاً (كانت تُقرأ «أتش تي تي بي نقطة...» المزعجة).
        private val PATTERN_URL = Pattern.compile(
            """(?i)\b((?:https?://|www\.)[^\s<>"']+)"""
        )

        // الأرقام الرومانية (ساعات كبند/فصول/قوائم): تُنطق ككلمات أو أرقام عادية.
        // يعترف فقط بالملييئة وإن كانت كبيرة (IvXLCDM) بحدود كلمات حقيقية.
        private val PATTERN_ROMAN = Pattern.compile("""(?<![\p{Alpha}])[IVXLCDM]{1,8}(?![\p{Alpha}])""")

        // أنماط كود العملة
        private val PATTERN_CURRENCY_CODE = Pattern.compile(
            """\b(USD|EUR|GBP|SAR|AED|KWD|QAR|OMR|BHD|EGP|TND|DZD|MAD|JPY|CNY|INR|KRW|RUB)\s+(\d+(?:[.,]\d+)?)\b"""
        )

        // أنماط تنظيف المسافات
        private val PATTERN_MULTI_SPACE  = Pattern.compile("""\s+""")
        private val PATTERN_SPACE_BEFORE = Pattern.compile("""\s+([،؛.!?])""")

        // فاصلة/نقطة الآلاف: الفاصل المتلوّ بثلاث خانات بالضبط ثم نهاية أو حرف
        // غير رقمي يُعتبر فاصلة آلاف (تُحذف). يعمل على النمطين الأمريكي
        // 1,234.56 والأوروبي 1.234,56 دون انهيار.
        private val PATTERN_THOUSANDS = Pattern.compile("""[.,](?=\d{3}(?:\D|$))""")

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
            Pattern.compile("""\b(\d+(?:[.,]\d+)?)\s*${Pattern.quote(info.symbol)}\b""") to info
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

        // 0.05 تجريد التشكيل العربي (حركات/تنوين/شدّة/سكون/كشيدة) قبل كل المطابقات
        //    حتى يطابق القاموس والأنماط الكلماتَ المشكولة وتتوقف الأخطاء النطقية
        result = stripTashkeel(result)

        // 0.1 معالجة الإيموجي: إزالتها وتنظيف التركيبات المعقدة حتى لا تشوّش النطق
        result = processEmojis(result)

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

        // 6. معالجة الأرقام العادية
        result = processNumbers(result)

        // 7. معالجة الرموز الشائعة
        result = processSymbols(result)

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
            val spoken = numberToWords(value.toLong())
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(spoken))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
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
        val cleaned = PATTERN_THOUSANDS.matcher(raw).replaceAll("")
        return cleaned.replace(',', '.').toDoubleOrNull() ?: 0.0
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
        val forMasculine = arrayOf("", "", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة", "عشرة")
        val forFeminine  = arrayOf("", "", "ثلاث", "أربع", "خمس", "ست", "سبع", "ثمان", "تسع", "عشر")
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
        // الفاصلة/النقطة التي بعدها 3 خانات فاصلة آلاف تُحذف؛ وما تبقى من
        // فواصل/نقاط (الكسور) يُوحَّد إلى نقطة — يغطي النمط الأمريكي والأوروبي.
        val cleaned = PATTERN_THOUSANDS.matcher(numberStr).replaceAll("")
        val number = cleaned.replace(',', '.').toDoubleOrNull() ?: return numberStr
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
            // U+0670–U+0673) بالإضافة للتطويل/الكشيدة (U+0640).
            if (cp in 0x0610..0x061A || cp == 0x0640 || cp in 0x064B..0x065F || cp in 0x0670..0x0673) continue
            sb.append(ch)
        }
        return sb.toString()
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
}
