package com.aymankhattab.nateq.core.audio.enginehost

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * بصمة آمنة للنص المنطوق في السجلات والتقارير (البند ١٥).
 *
 * النص الذي يقرأه قارئ الشاشة قد يحتوي بيانات خاصة؛ لذلك لا يُكتب
 * نصّه الكامل في أي سجل أو تقرير تشخيص، بل وصفٌ لا يعيد بناءه:
 * طول الحروف وطول البايتات وبصمة CRC32 مختصرة. البصمة تسمح بربط
 * طلبٍ بسجله من غير كشف محتواه.
 */
internal object SpeechTextRedactor {

    /** وصفٌ مختصر بلا أي جزء من النص الأصلي. */
    fun describe(text: String): String {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val crc = CRC32()
        crc.update(bytes)
        val hash = java.lang.String.format(
            java.util.Locale.US, "%08x", crc.value
        )
        return "len=${text.length} bytes=${bytes.size} sha=$hash"
    }

    /**
     * يستبدل كل نصٍّ بين علامتي `text='...'` بوصف آمن. يُستخدم لتنظيف
     * مسارات الأخطاء الجاهزة قبل إدراجها في تقرير التشخيص.
     */
    fun redactQuotedText(line: String): String =
        QUOTED_TEXT.replace(line) { match ->
            "text=<${describe(match.groupValues[1])}>"
        }

    private val QUOTED_TEXT = Regex("""text='([^']*)'""")
}
