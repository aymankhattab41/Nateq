package com.aymankhattab.nateq.core.audio.engine.quirks

/**
 * تنقية خاصة بالنصوص للمحركات التي تعجز عن معالجة الإيموجي أو محارف التنسيق
 * أو العلامات الداخلية للتطبيق (مثل Acapela) (الجولة 55 - الأمر 7).
 */
object EngineTextSanitizer {

    data class SanitizationResult(
        val sanitizedText: String,
        val removedCharsCount: Int
    )

    /**
     * تنقية النص بحذف الإيموجي، المحارف غير المرئية، علامات PUA الداخلية،
     * وتوحيد المسافات المتعددة.
     */
    fun sanitizeWithCount(
        text: String,
        enginePackage: String? = null
    ): SanitizationResult {
        if (text.isEmpty()) {
            return SanitizationResult(text, 0)
        }

        val sb = StringBuilder(text.length)
        var removed = 0
        var i = 0

        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)

            val isEmojiOrPictorial = (
                cp in 0x2600..0x27BF ||
                cp in 0x2B00..0x2BFF ||
                cp in 0x1F000..0x1FBFF ||
                cp in 0x1F300..0x1FAFF ||
                cp in 0xFE00..0xFE0F ||
                cp == 0x200D
            )

            val isInvisibleFormat = (
                cp in 0x200B..0x200F ||
                cp in 0x202A..0x202E ||
                cp in 0x2060..0x2064 ||
                cp == 0xFEFF ||
                cp == 0x061C
            )

            val isPrivateUseArea = (cp in 0xE000..0xF8FF)

            if (isEmojiOrPictorial || isInvisibleFormat || isPrivateUseArea) {
                removed += charCount
                // إضافة مسافة عند حذف رمز رمزي أو إيموجي منعاً للالتصاق
                if (isEmojiOrPictorial &&
                    sb.isNotEmpty() &&
                    !sb.last().isWhitespace()
                ) {
                    sb.append(' ')
                }
            } else {
                sb.appendCodePoint(cp)
            }
            i += charCount
        }

        val normalized = sb.toString()
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .trim()

        return SanitizationResult(
            sanitizedText = normalized,
            removedCharsCount = removed
        )
    }

    /**
     * تنقية النص وإرجاع النص المُنقّى مباشرة.
     */
    fun sanitize(text: String, enginePackage: String? = null): String =
        sanitizeWithCount(text, enginePackage).sanitizedText
}
