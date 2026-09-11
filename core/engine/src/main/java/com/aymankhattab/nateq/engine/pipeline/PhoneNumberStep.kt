package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة أرقام الهواتف: تُنطق رقماً رقماً بدل إغلاقها كعدد كامل
 *  («خمسمائة وواحد مليون…»). يعترف بأرقام من 7 إلى 15 خانة مع فواصل اختيارية
 *  (مسافة/شرطة/نقطة/أقواس) وبداية + اختيارية. الخط يعمل للعربية فقط، فلغة
 *  النطق ثابتة (عربية) داخل هذه الخطوة. */
internal object PhoneNumberStep : TextProcessingStep {

    // أنماط أرقام الهواتف: بداية اختيارية + ثم 7-15 رقماً مع فواصل
    // (مسافة/شرطة/نقطة). تُحسب الأرقام الفعلية في المعالجة؛ النمط
    // يلتقط المتواليات الطويلة فقط.
    private val PATTERN_PHONE = Pattern.compile(
        """(?<!\d)\+?\d[\d\s()\-.]{6,}\d(?!\d)"""
    )

    // بادئات اتصال محلية معروفة تُرجّح أن المتوالية الرقمية هاتف وليست مبلغاً:
    // مصر (010/011/012/015…) والبادئات 05–09 الشائعة في السعودية والخليج
    // وشمال أفريقيا وأوروبا.
    private val LOCAL_PHONE_PREFIXES = listOf(
        "010", "011", "012", "015", "016", "017", "018", "019",
        "05", "06", "07", "08", "09"
    )

    // عملية حسابية بفاصل بين رقميين فأكثر («1000 - 2000») ليست
    // هاتفاً رغم فصلها بمسافة وشرطة؛ تُترك لخطوتي الرموز والأرقام لاحقاً.
    private val PATTERN_ARITHMETIC =
        Pattern.compile("""\d+(?:\s*[+\-*/]\s*\d+)+""")

    // فواصل التاريخ في looksLikeDate (شرطة/نقطة/شرطة مائلة) — أنماط
    // مسبقة التجميع بدل إنشاء Regex في كل استدعاء (بند تحسين Regex).
    private val PATTERN_DATE_SEPARATOR = Pattern.compile("""[-/.]""")

    // فواصل مجموعات الآلاف في looksLikeThousandsGrouping (مسافة/أقواس/
    // نقطة/شرطة) — نفس الملاحظة.
    private val PATTERN_GROUP_SEPARATOR = Pattern.compile("""[\s().\-]+""")

    override fun apply(input: String): String =
        processPhoneNumbers(input, isArabicContext = true)

    private fun processPhoneNumbers(
        text: String, isArabicContext: Boolean
    ): String {
        // فصل الحالات البسيطة بمسح خطي واحد O(N): أي تطابق قابل للاستبدال
        // يحمل 7-15 رقماً (فلاتر الأطوال أدناه)، فغيابُ 7 أرقام يعني أن
        // الـ regex لا يمكن أن يُسفر عن أي استبدال — تُتخطّى المطابقة كلياً
        // للنصوص بلا أرقام أو بأرقام قصيرة (الحالة الأكثر شيوعاً).
        if (!canContainPhoneSequence(text)) return text
        val matcher = PATTERN_PHONE.matcher(text)
        if (!matcher.find()) return text
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            val raw = matcher.group(0)!!
            val digits = raw.filter { it.isDigit() }
            // تحقق إضافي ضد التطابقات الكاذبة: تواريخ (12.12.2024) وعناوين IP
            // (192.168.1.100) ليست هواتف رغم وقوع أرقامها ضمن المدى 7..15.
            if (looksLikeDate(raw) || looksLikeIpAddress(raw)) {
                val quoted = java.util.regex.Matcher.quoteReplacement(raw)
                matcher.appendReplacement(buffer, quoted)
                continue
            }
            // تقبّل فقط ما يقع في مدى أرقام الهواتف؛ ما عداه يُترك كما هو.
            if (digits.length !in 7..15) {
                val quoted = java.util.regex.Matcher.quoteReplacement(raw)
                matcher.appendReplacement(buffer, quoted)
                continue
            }
            // لا تُعامل المتوالية الرقمية كهاتف إلا بدليل قاطع: مفتاح دولي (+)
            // أو بادئة اتصال محلية معروفة أو فواصل هاتفية قياسية — وإلا فتُترك
            // للمعالجة: «10000000» = عشرة ملايين لا هاتف يُنطق رقماً.
            if (!isLikelyPhone(raw, digits)) {
                val quoted = java.util.regex.Matcher.quoteReplacement(raw)
                matcher.appendReplacement(buffer, quoted)
                continue
            }
            val isArabic = isArabicContext
            val spokenDigits = digits.map { it.digitToInt() }
                .joinToString(" ") {
                    // الأرقام تُنطق كأرقام مجردة (مذكرة): «خمسة» لا «خمس».
                    if (isArabic) NumberSpeech
                        .toArabicWords(it, isFeminine = false)
                    else NumberSpeech.toEnglishWords(it)
                }
            val quoted = java.util.regex.Matcher
                .quoteReplacement(spokenDigits)
            matcher.appendReplacement(buffer, quoted)
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** هل التطابق تاريخ (3 مجموعات رقمية بفاصل، آخرها سنة 2-4 أرقام)؟ */
    private fun looksLikeDate(raw: String): Boolean {
        val parts = PATTERN_DATE_SEPARATOR.split(raw)
            .filter { it.isNotBlank() }
        if (parts.size != 3) return false
        val lens = parts.map { it.length }
        // يوم/شهر (1-2) وسنة (2-4) — الأجزاء الثلاثة كلها أرقام خالصة
        if (parts.any { !it.all(Char::isDigit) }) return false
        return lens[0] in 1..2 && lens[1] in 1..2 && lens[2] in 2..4
    }

    /** هل التطابق عنوان IP (4 مجموعات رقمية من 1-3 أرقام بنقاط)؟ */
    private fun looksLikeIpAddress(raw: String): Boolean {
        if (raw.startsWith("+")) return false
        val parts = raw.split('.')
        if (parts.size != 4) return false
        return parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit)
        }
    }

    /** ترجيح كون المتوالية رقم هاتف فعلياً (لا مبلغاً أو عدداً مجرداً). */
    private fun isLikelyPhone(raw: String, digits: String): Boolean {
        // مفتاح اتصال دولي صريح (+20 …)
        if (raw.startsWith("+")) return true
        // بادئة اتصال محلية معروفة (010 مصر، 05 السعودية…)
        if (LOCAL_PHONE_PREFIXES.any { digits.startsWith(it) }) return true
        // مجموعات آلاف أوروبية/فرنسية (1 000 000، 12.345.678) ليست هواتف
        if (looksLikeThousandsGrouping(raw)) return false
        // عمليات حسابية («1000 - 2000»، «5 * 7») ليست هواتف رغم الفواصل
        if (looksLikeArithmetic(raw)) return false
        // فواصل هاتفية قياسية (مسافة/شرطة/أقواس/نقطة)
        return raw.any {
            it == ' ' || it == '-' || it == '(' || it == ')' || it == '.'
        }
    }

    /** هل التطابق مجرد تجميع آلاف بفواصل (تنسيق أوروبي) وليس هاتفاً؟ */
    private fun looksLikeThousandsGrouping(raw: String): Boolean {
        val groups = PATTERN_GROUP_SEPARATOR.split(raw)
            .filter { it.isNotBlank() }
        if (groups.size < 2) return false
        if (groups.first().length !in 1..3) return false
        return groups.drop(1).all { it.length == 3 && it.all(Char::isDigit) }
    }

    /** هل التطابق تعبير حسابي (مجموعات رقمية يفصلها عامل + - * /)؟ */
    private fun looksLikeArithmetic(raw: String): Boolean {
        // المطابقة الكاملة فقط سليمة: التعامل هنا تعبيرٌ حسابي لا هاتف
        return PATTERN_ARITHMETIC.matcher(raw).matches()
    }

    /** مسح خطي رخيص بلا regex: هل قد يحمل النص متوالية هاتف؟ أي تطابق
     *  قابل للاستبدال يحمل 7-15 رقماً (فلاتر الأطوال في
     *  [processPhoneNumbers])، فبلوغ 7 أرقام ASCII هو القاطع الوحيد
     *  المطلوب هنا — والحالة الأكثر شيوعاً (نص بلا أرقام أو بأرقام قصيرة)
     *  تتوقف عند أول 7 أرقام أو نهاية السلسلة. */
    private fun canContainPhoneSequence(text: String): Boolean {
        var digits = 0
        for (c in text) {
            if (c in '0'..'9') {
                digits++
                if (digits >= 7) return true
            }
        }
        return false
    }
}