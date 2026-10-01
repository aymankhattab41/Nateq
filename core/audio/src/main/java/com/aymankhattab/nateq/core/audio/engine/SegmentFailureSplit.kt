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

/** حدودٌ لغوية آمنة — القطع بعدها لا يقطع كلمةً في منتصفها. */
internal val SEGMENT_BOUNDARIES = charArrayOf(
    '.', '!', '?', '،', '؛', ':', '؟', '۔', '।', '\n'
)

/**
 * يقسّم نصاً يتجاوز [SEGMENT_SPLIT_FALLBACK_CHARS] عند أول حدٍّ آمن بعد
 * منتصفه، فتبقى القطعُ على حدود الجُمل. نصٌّ بلا حدٍّ آمنٍ يُقسَّم عند
 * المنتصف. والنصُّ دون العتبة **لا يُقسَّم إطلاقاً** (قطعةٌ واحدة).
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

/** أول حدٍّ آمن في [half, limit] — أو المنتصف إن لم يوجد حدّ. */
private fun boundaryCutIndex(text: String): Int {
    val limit = minOf(
        SEGMENT_SPLIT_FALLBACK_CHARS, text.length
    )
    for (i in (text.length / 2) until limit) {
        if (text[i] in SEGMENT_BOUNDARIES) {
            return i + 1
        }
    }
    return limit
}