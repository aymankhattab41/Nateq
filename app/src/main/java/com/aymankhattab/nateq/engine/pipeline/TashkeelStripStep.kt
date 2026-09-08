package com.aymankhattab.nateq.engine.pipeline

/**
 * تجريد التشكيل العربي من النص (حركات، تنوين، شدّة، سكون، تطويل/كشيدة،
 * والعلامات الإملائية الرافدة) دون لمس الحروف أو فواصل الكلمات.
 * ضروري قبل مطابقة الأنماط والقواميس: علامات Unicode الفاصلة عن الحرف
 * (Mn) تجعل [Character.isLetter] تعود false وتفكك تعبيرات regex العربية،
 * كما أن الكلمة المشكولة لا تُطابق مدخلات القاموس المكتوبة بلا تشكيل.
 * تُطبَّق على مدخل المعالجة فقط، وبالتالي لا تُحذف من نصٍّ ليس عربياً.
 */
internal object TashkeelStripStep : TextProcessingStep {

    override fun apply(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
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
}