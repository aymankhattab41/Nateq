package com.aymankhattab.nateq.engine

/**
 * تفسير محلي لـ SSML المحدود (بند الأوامر د.3.7): خدمة TTS النظامية تستقبل
 * نصاً ([android.speech.tts.SynthesisRequest] نصياً بلا وسوم) فلا يمكن تمرير
 * SSML غير معالَج للمحرك — تُترجَم الوسومُ المدعومة إلى مفرداتٍ ينطقها
 * المحرك الطبيعي:
 *
 *  - `<break time="250ms"/>` → وقفة تقريبية عبر علامة ترقيم بينية (فاصلة
 *    للمدد المتوسطة ونقطة للطويلة) يخلقها المحركُ عفوياً عند نطق الترقيم.
 *  - `<say-as interpret-as="characters">كلمة</say-as>` → تُفصَل حروفُها
 *    بمسافات فينطقها المحرك حرفاً حرفاً لا مختصراً.
 *  - أي وسم آخر (prosody/emphasis/…) يبقى محتواه الداخلي وتُشطر أطرافه.
 *  - كيانات XML الصغيرة تُفك (قبل دخول نموذج النطق).
 *
 * الدالة هويةٌ تامة على النصوص الخالية من الوسوم (لا أثر أدائياً).
 */
object SsmlStep {

    private val sayAsCharacters = Regex(
        """(?is)<say-as\b[^>]*?interpret-as\s*=\s*""" +
            """["']characters["'][^>]*>(.*?)</say-as\s*>"""
    )
    private val breakTag = Regex(
        """(?is)<break\b([^>]*)/?>"""
    )
    private val genericTag = Regex(
        """(?i)</?[a-zA-Z][a-zA-Z0-9-]*(\s+[^>]*)?>"""
    )
    private val breakTime = Regex(
        """time\s*=\s*["']?\s*(\d+)\s*(ms|s)?["']?"""
    )

    /** يفسّر وسوم SSML المحدودة في [text] ويعيد نصاً ترجمته قابلة للنطق. */
    fun apply(text: String): String {
        // الخروج المبكر لا يشمل الكيانات المرمَّزة (بلا <) فتُفك هي أيضاً.
        if (text.indexOf('<') < 0 && text.indexOf('&') < 0) return text
        var out = text
        // 1) say-as characters أولاً (يتضمن محتوى داخلاً قد يحوي مسافات).
        out = out.replace(sayAsCharacters) { match ->
            val inner = match.groupValues[1]
            val codePoints = mutableListOf<String>()
            var offset = 0
            while (offset < inner.length) {
                val codePoint = inner.codePointAt(offset)
                codePoints.add(String(Character.toChars(codePoint)))
                offset += Character.charCount(codePoint)
            }
            codePoints.joinToString(" ")
        }
        // 2) break: وقفة تقريبية حسب المدة.
        out = out.replace(breakTag) { match ->
            val attrs = match.groupValues[1]
            val ms = breakTime.find(attrs)?.let { group ->
                val value = group.groupValues[1].toLongOrNull()
                val unit = group.groupValues[2]
                when {
                    value == null -> 0L
                    unit == "s" -> value * 1_000
                    else -> value
                }
            } ?: 0L
            when {
                ms <= 0 -> " "
                ms >= 750 -> "."
                else -> ","
            }
        }
        // 3) أي وسوم متبقية: تشطر أطرافها ويبقى محتواها.
        out = out.replace(genericTag, "")
        // 4) فك رموز الكيانات الصغيرة (المرمَّزة والمسماة الأربع).
        out = out.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        return out
    }
}