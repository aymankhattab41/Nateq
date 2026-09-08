package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة أرقام الهواتف: تُنطق رقماً رقماً بدل إغلاقها كعدد كامل
 *  («خمسمائة وواحد مليون…»). يعترف بأرقام من 7 إلى 15 خانة مع فواصل اختيارية
 *  (مسافة/شرطة/نقطة/أقواس) وبداية + اختيارية. الخط يعمل للعربية فقط، فلغة
 *  النطق ثابتة (عربية) داخل هذه الخطوة. */
internal object PhoneNumberStep : TextProcessingStep {

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

    override fun apply(input: String): String = processPhoneNumbers(input, isArabicContext = true)

    private fun processPhoneNumbers(text: String, isArabicContext: Boolean): String {
        val matcher = PATTERN_PHONE.matcher(text)
        val buffer = StringBuffer()
        while (matcher.find()) {
            val raw = matcher.group(0)!!
            val digits = raw.filter { it.isDigit() }
            // تحقق إضافي ضد التطابقات الكاذبة: تواريخ (12.12.2024) وعناوين IP
            // (192.168.1.100) ليست هواتف رغم وقوع أرقامها ضمن المدى 7..15.
            if (looksLikeDate(raw) || looksLikeIpAddress(raw)) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(raw))
                continue
            }
            // تقبّل فقط ما يقع في مدى أرقام الهواتف الشائعة؛ ما عداه يُترك كما هو.
            if (digits.length !in 7..15) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(raw))
                continue
            }
            // لا تُعامل المتوالية الرقمية كهاتف إلا بدليل قاطع: مفتاح دولي (+)
            // أو بادئة اتصال محلية معروفة أو فواصل هاتفية قياسية — وإلا فتُترك
            // للمعالجة الرقمية («10000000» مبلغ = عشرة ملايين لا هاتف يُنطق رقماً).
            if (!isLikelyPhone(raw, digits)) {
                matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(raw))
                continue
            }
            val isArabic = isArabicContext
            val spokenDigits = digits.map { it.digitToInt() }
                .joinToString(" ") {
                    // الأرقام تُنطق كأرقام مجردة (مذكرة): «خمسة» لا «خمس».
                    if (isArabic) NumberSpeech.toArabicWords(it, isFeminine = false)
                    else NumberSpeech.toEnglishWords(it)
                }
            matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(spokenDigits))
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
}