package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.regex.Pattern

/** معالجة أرقام الهواتف: تُنطق رقماً رقماً بدل إغلاقها كعدد كامل
 *  («خمسمائة وواحد مليون…»). يعترف بأرقام من 7 إلى 15 خانة مع فواصل اختيارية
 *  (مسافة/شرطة/نقطة/أقواس) وبداية + اختيارية. الخط يعمل للعربية فقط، فلغة
 *  النطق ثابتة (عربية) داخل هذه الخطوة. */
internal class PhoneNumberStep(
    private val modeProvider: () -> Int = { 1 },
    private val languageProvider: () -> String? = { null }
) : TextProcessingStep {

    companion object : TextProcessingStep {
        private val defaultInstance = PhoneNumberStep()
        override fun apply(input: String): String =
            defaultInstance.apply(input)
        override fun applyEnglish(input: String): String =
            defaultInstance.applyEnglish(input)
    }

    // أنماط أرقام الهواتف: بداية اختيارية + ثم 7-15 رقماً مع فواصل
    // (مسافة/شرطة/نقطة). تُحسب الأرقام الفعلية في المعالجة؛ النمط
    // يلتقط المتواليات الطويلة فقط. «~» الملاصقة اختيارية: علامةُ
    // واتساب قبل رقم غير المسجّل (بند المعالجة) — تُنطق «may be»
    // كما هي قبل الهاتف المؤكَّد (لا «تقريباً» ولا حذفاً صامتاً)،
    // وغيره يبقى لخطوة الرموز (نطق «تقريباً» لسياقات التقريب).
    // \p{Cf} يمتص العلامات غير المرئية (LRM/RLM…) التي تضعها واتساب
    // حول «~» وداخل فواصل الرقم، فلا تُفسد المطابقة ولا تُنطق.
    private val PATTERN_PHONE = Pattern.compile(
        """(?<!\d)(?:~[\s\p{Cf}]*)?(?:\+\s*)?""" +
            """\d[\d\s\p{Cf}()\-.]{6,}\d(?!\d)"""
    )

    // بادئات اتصال محلية ودولية (00، 01 إلى 09): كل متوالية هاتفية 7-15
    // رقماً تبدأ بـ 0 هي هاتف محلي/دولي قطعاً (المبالغ والأعداد لا تبدأ بـ 0).
    private val LOCAL_PHONE_PREFIXES = listOf(
        "00", "01", "02", "03", "04", "05", "06", "07", "08", "09"
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
        processPhoneNumbers(
            input,
            isArabicContext = isArabic(defaultForPipeline = true)
        )

    /** النسخة الإنجليزية: تُنطق خانات الهاتف كلماتٍ إنجليزية
     *  («Call zero one zero…»). */
    override fun applyEnglish(input: String): String =
        processPhoneNumbers(
            input,
            isArabicContext = isArabic(defaultForPipeline = false)
        )

    private fun isArabic(defaultForPipeline: Boolean): Boolean {
        val configured = languageProvider()
        return if (configured != null) {
            configured != "en"
        } else {
            defaultForPipeline
        }
    }

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
            // (192.168.1.100) وأعداد عشرية (30496.00) ليست هواتف.
            if (looksLikeDate(raw) || looksLikeIpAddress(raw) ||
                looksLikeDecimal(raw)
            ) {
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
            // ماركة واتساب «~» الملاصقة (إن وُجدت): تُنطق «may be» كما
            // هي بلا ترجمة ولا حذف — الاسم المعنون بـ«~» غير المسجَّل
            // يُقرأ على حقيقته («may be») ثم يُنطق الرقم كما هو.
            val marker = if (raw.startsWith("~")) "may be " else ""
            val body = phoneBody(raw)
            val isArabic = isArabicContext
            val hasPlus = body.startsWith("+")
            val plusPrefix = if (hasPlus) "+" else ""
            val mode = modeProvider().coerceIn(1, 8)
            val spoken = NumberSpeech.formatByMode(
                mode = mode,
                numberStr = "$plusPrefix$digits",
                isEnglish = !isArabic
            )
            val quoted = java.util.regex.Matcher
                .quoteReplacement(marker + spoken)
            matcher.appendReplacement(buffer, quoted)
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /** جسدُ التطابق بلا البادئات الخارجية: ماركة واتساب «~» والمسافات
     *  وعلامات التنسيق غير المرئية (LRM/RLM — \p{Cf}) المحيطة به. تُجرد
     *  للفحص اللاحق (مفتاح دولي بعدها يظل مفتاحاً دولياً) مع بقاء «~»
     *  نفسها مكتشفةً عند بداية التطابق لنطقِها «may be». التحويل للأمام
     *  بلا Regex: يتوقف عند أول رقم أو «+». */
    private fun phoneBody(raw: String): String {
        val lead = { s: String -> s.dropWhile { c ->
            c.isWhitespace() ||
                Character.getType(c) == Character.FORMAT.toInt()
        } }
        var body = lead(raw)
        if (body.startsWith("~")) {
            body = lead(body.substringAfter('~'))
        }
        return body
    }

    /** هل التطابق تاريخ (3 مجموعات رقمية بفاصل، آخرها سنة 2-4 أرقام)؟ */
    private fun looksLikeDate(raw: String): Boolean {
        if (raw.trimStart().startsWith("+")) return false
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
        if (raw.trimStart().startsWith("+")) return false
        val parts = raw.split('.')
        if (parts.size != 4) return false
        return parts.all {
            it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit)
        }
    }

    /** هل التطابق عدداً أو مبلغاً عشرياً (30496.00 أو 30,496.00)؟ */
    private fun looksLikeDecimal(raw: String): Boolean {
        if (raw.trimStart().startsWith("+")) return false
        // نقطة واحدة فقط تفصل بين أرقام: كسر عشري قطعاً
        val firstDot = raw.indexOf('.')
        if (firstDot >= 0 && raw.lastIndexOf('.') == firstDot) {
            val before = raw.substring(0, firstDot)
            val after = raw.substring(firstDot + 1)
            if (before.any(Char::isDigit) && after.any(Char::isDigit)) {
                return true
            }
        }
        // نهاية بكسر عشري بعد فاصلة (مثل 30,496.00 أو 1,234.5)
        val lastSep = raw.lastIndexOfAny(charArrayOf('.', ','))
        if (lastSep >= 0) {
            val tail = raw.substring(lastSep + 1)
            if (tail.length in 1..2 && tail.all(Char::isDigit)) return true
        }
        return false
    }

    /** ترجيح كون المتوالية رقم هاتف فعلياً (لا مبلغاً أو عدداً مجرداً). */
    private fun isLikelyPhone(raw: String, digits: String): Boolean {
        // ماركة واتساب «~» الملاصقة لا تُغيّر الترجيح — تُحذف من الجسد
        // قبل الفحص (مفتاح دولي بعدها يظل مفتاحاً دولياً).
        val body = phoneBody(raw)
        // مفتاح اتصال دولي صريح (+20 …)
        if (body.startsWith("+")) return true
        // بادئة اتصال محلية/دولية معروفة تبدأ بـ 0 (00، 01 إلى 09)
        if (LOCAL_PHONE_PREFIXES.any { digits.startsWith(it) }) return true
        // مجموعات آلاف أوروبية/فرنسية (1 000 000، 12.345.678) ليست هواتف
        if (looksLikeThousandsGrouping(body)) return false
        // عمليات حسابية («1000 - 2000»، «5 * 7») ليست هواتف رغم الفواصل
        if (looksLikeArithmetic(body)) return false
        // أعداد عشرية («30496.00»، «12345.67») ليست هواتف إطلاقاً
        if (looksLikeDecimal(body)) return false
        // فواصل هاتفية قياسية (مسافة/شرطة/أقواس/نقطة)
        return body.any {
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