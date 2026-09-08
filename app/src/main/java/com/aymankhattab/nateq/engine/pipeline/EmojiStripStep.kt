package com.aymankhattab.nateq.engine.pipeline

import java.text.Normalizer

/**
 * خطوة إزالة الإيموجي عند تعطيل «نطق الإيموجي» (تُستبدل بمسافة للحفاظ على
 * الفصل بين الكلمات). عندما يكون نطق الإيموجي مفعّلاً تكون الإيموجي قد عُرضت
 * أسماؤها خارج الخط (توسيع مسبق في المنسّق)، فتعمل الخطوة بحياد (no-op).
 */
internal class EmojiStripStep(private val isEmojiEnabled: () -> Boolean) : TextProcessingStep {

    override fun apply(input: String): String =
        if (isEmojiEnabled()) input else stripEmojis(input)

    /**
     * إزالة الإيموجي وتركيباتها المعقدة (ZWJ, skin tone modifiers, variation
     * selectors, flags) بطريقة آمنة حتى لا تشوّش النطق.
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
}