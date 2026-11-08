package com.aymankhattab.nateq.core.audio.enginehost

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * باني SSML لمايكروسوفت المدمج — نقيٌّ وقابلٌ للاختبار.
 *
 * **سبب وجود `<prosody rate>`:** كان معدّل النطق (`rate`) يُهمَل في
 * المضيف بعد إزالة المعامل من باني SSML، فينطق المحرك دائماً بالسرعة
 * الافتراضية مهما ضبط المستخدم. وثائق مايكروسوفت تؤكد دعم
 * `<prosody rate>` في المحركات المدمجة (Embedded NTTS)، فتُصاغ النسبة
 * هنا مئويةً نسبية عن السرعة الافتراضية.
 */
internal object MicrosoftSsml {

    /** هامشٌ أدنى لتجاهل الفروق العائمة الضئيلة (0.999f ≈ 1.0f). */
    private const val RATE_EPSILON = 0.005f

    /**
     * يبني SSML كاملاً للنصّ [text] بالصوت [modelName] واللغة [langTag].
     * [rate] = 1.0 تعني السرعة الافتراضية فلا يُضاف عنصر `prosody`
     * إطلاقاً (طلبٌ أصغر وأقرب للسلوك الأصلي).
     */
    fun build(
        text: String,
        modelName: String,
        langTag: String,
        rate: Float
    ): String {
        val escapedText = escapeXml(text)
        val body = if (abs(rate - 1f) < RATE_EPSILON) {
            escapedText
        } else {
            val percent = ((rate - 1f) * 100f).roundToInt()
            // السالب يحمل إشارته من الرقم، والموجب يحتاج "+" صريحاً
            // كما تشترطه صيغة النسبة المئوية في SSML.
            val sign = if (percent > 0) "+" else ""
            "<prosody rate=\"$sign$percent%\">$escapedText</prosody>"
        }
        val header = "<speak version=\"1.0\" " +
            "xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xml:lang=\"${escapeXml(langTag)}\">"
        val voiceName = escapeXml(modelName)
        return "$header<voice name=\"$voiceName\">$body</voice></speak>"
    }

    /** تهريب محارف XML الخمسة الواجبة داخل النصّ والقيم. */
    fun escapeXml(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
