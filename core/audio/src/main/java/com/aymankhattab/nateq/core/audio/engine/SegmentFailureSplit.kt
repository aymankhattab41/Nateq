package com.aymankhattab.nateq.core.audio.engine

/**
 * تقسيم المقطع عند **الفشل** — لا قبله.
 *
* **لماذا التقسيمُ موجود أصلاً؟** كان مسار القارئ يسلّم النصّ كلَّه
 * طلباً واحداً إلى المحرّك فيقطعه عند حدود المقاطع اللغوية بصمت
 * (كلماتٌ تختفي بلا صوت ولا خطأ)، فكان الحلُّ السابق تقسيماً أعمى
 * مسبقاً إلى 200 حرف — وهو أسوأ: كان يُسقط كلماتٍ كلياً بصمت أيضاً.
 *
 * **الفرقُ الجوهري:** هذا التقسيمُ لا يقع إلا لمقاطع تتجاوز
 * [SEGMENT_SPLIT_FALLBACK_CHARS] — وهي عتبةُ التعثّر القصوى في محرّكات
 * TTS (نصٌّ أطول منها يعجز المحرّك عن تخليقه كاملاً). فالنصُّ السليم
 * يبقى طلباً واحداً متّصلاً بلا تقطيع، والمتعثّرُ وحده يُعاد تخليقه
 * على أجزاءٍ **عند حدوده اللغوية** فلا تُبتَر كلمةٌ في منتصفها، وكلُّ
 * جزءٍ يُنطق قبل الذي يليه.
 */

/** عتبةُ التعثّر: أطولُ من هذا يُعاد تخليقه على أجزاء. */
internal const val SEGMENT_SPLIT_FALLBACK_CHARS = 1_500

/**
 * العتبة الدنيا لتقسيم الجمل الطويلة مسبقاً (280 حرفاً):
 * النصوص الأطول من ذلك تُقسَّم عند حدود الجمل الآمنة لتسريع أول صوت.
 */
internal const val SENTENCE_CHUNK_MAX_CHARS = 280

/** حدودٌ لغوية آمنة — القطع بعدها لا يقطع كلمةً في منتصفها. */
internal val SEGMENT_BOUNDARIES = charArrayOf(
    '.', '!', '?', '،', '؛', ':', '؟', '۔', '।', '\n'
)

/**
 * يقسّم نصاً يتجاوز [SEGMENT_SPLIT_FALLBACK_CHARS] على أقرب حدٍّ آمن
 * **ضمن** العتبة، فتبقى القطعةُ أطولُ ما يمكن بلا ابترار كلمة، ولا
 * تتجاوز ميزانيةَ المحرّك أبداً. ونصٌّ بلا حدٍّ آمنٍ يُقسَّم عند العتبة.
 * والنصُّ دون العتبة **لا يُقسَّم إطلاقاً** (قطعةٌ واحدة).
 */
internal fun splitOnFailureBoundary(text: String): List<String> {
    if (text.length <= SEGMENT_SPLIT_FALLBACK_CHARS) {
        return listOf(text)
    }
    val parts = ArrayList<String>()
    var rest = text
    while (rest.length > SEGMENT_SPLIT_FALLBACK_CHARS) {
        val cut = boundaryCutIndex(rest)
        parts.add(rest.substring(0, cut).trim())
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) {
        parts.add(rest)
    }
    return parts
}

/**
 * موضعُ القطع: **آخر** حدٍّ آمن ضمن العتبة، وإلا العتبةُ نفسُها.
 *
 * **لماذا بحثٌ عكسيّ؟** العتبةُ (1500) حدُّ تعثُّرٍ لا يُتجاوز — جزءٌ
 * أطولُ منها قد يعجز المحرّك. فلا يجوز أن يُبحث عن حدٍّ **بعد**
 * العتبة ليجدَ القطعَ أطولَ منها. والبحثُ من المنتصف **أماماً** يفشل
 * في النصِّ الطويل: متى تجاوز النصُّ 3000 حرف صار نصفُه أكبرَ من
 * العتبة، فنطاقُ البحث `[midpoint, limit]` **فارغ** فيُقطع عند العتبةِ
 * في منتصف كلمةٍ بلا بحثٍ إطلاقاً.
 *
 * فالبحثُ **عكسياً** من العتبة يحلّ الأمرين معاً: أطولُ جزءٍ **ضمن
 * الميزانية** ينتهي على حدٍّ لغويٍّ، وينقطع فقط على الحدّ الثابت إن
 * لم يوجد حدٌّ في النطاق (نصٌّ بلا ترقيم — وهو نادرٌ في النصوص
 * الحقيقية). وما دام [0, limit] يشمل ما بعد المنتصف دائماً فهو لا
 * يفوّت حدّاً في ذيل النصّ أيضاً.
 */
private fun boundaryCutIndex(text: String): Int {
    val limit = minOf(
        SEGMENT_SPLIT_FALLBACK_CHARS, text.length
    )
    var i = limit - 1
    while (i >= 0) {
        if (text[i] in SEGMENT_BOUNDARIES) {
            return i + 1
        }
        i--
    }
    return limit
}

/**
 * يقسّم النص الطويل (فوق [maxChars] حرفاً، افتراضياً 280)
 * عند حدود الجمل الآمنة ([SEGMENT_BOUNDARIES]) إلى أجزاء متوسطة،
 * لتسريع زمن أول صوت (TTFB) للمحركات التي تُخلِّق النص كله قبل الكتابة.
 * لا يقطع الكلمات في منتصفها ويحافظ على النص كاملاً بلا فقدان.
 */
internal fun splitSentenceChunks(
    text: String,
    maxChars: Int = SENTENCE_CHUNK_MAX_CHARS
): List<String> {
    if (text.length <= maxChars) {
        return listOf(text)
    }
    val parts = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val remaining = text.length - start
        if (remaining <= maxChars) {
            val lastPart = text.substring(start).trim()
            if (lastPart.isNotEmpty()) {
                parts.add(lastPart)
            }
            break
        }
        val limit = (start + maxChars).coerceAtMost(text.length)
        var cut = -1
        // نبحث عن أقرب حد لغوي آمن من نهاية النافذة رجوعاً
        for (i in (limit - 1) downTo start) {
            if (text[i] in SEGMENT_BOUNDARIES) {
                cut = i + 1
                break
            }
        }
        // إن لم نجد حداً لغوياً، نقطع عند آخر مسافة لتفادي قطع الكلمات
        if (cut == -1) {
            val lastSpace = text.lastIndexOf(' ', limit - 1)
            if (lastSpace > start) {
                cut = lastSpace + 1
            } else {
                cut = limit
            }
        }
        val chunk = text.substring(start, cut).trim()
        if (chunk.isNotEmpty()) {
            parts.add(chunk)
        }
        start = cut
        while (start < text.length && text[start] == ' ') {
            start++
        }
    }
    return if (parts.isEmpty()) listOf(text) else parts
}
