package com.aymankhattab.nateq.core.audio.engine

/**
 * تقسيم النصوص الطويلة إلى مقاطع نطق قصيرة — أداة مشتركة بين مسار
 * القارئ (تخليق الصوت) ومسار الإعلانات.
 *
 * لماذا التقسيم؟ محركات الـ TTS تتعثّر بالطلب الواحد الطويل: تُبتر النص
 * عند حدّ داخلي، أو تعلّق صامتاً فيستدعي مُهلة التخليق فيُقطع النطق في
 * منتصف القراءة. المقاطع القصيرة تُخلَّق وتُبثّ واحدةً تلو الأخرى، فيُقرأ
 * النص كاملاً بلا بتر.
 *
 * لماذا هذا الحد (200 حرف)؟ 200 حرف نحو 13 ثانية من الكلام الطبيعي:
 * مدة تكفي لتغطية تكلفة استدعاء المحرك (الربط والتهيئة) بين المقاطع،
 * وتبقى أصغر من كل حدود المحركات المعروفة (أضيقها نحو 4000 حرف).
 *
 * الضمانات:
 * 1) أطول مقطعٍ لا يتجاوز [maxChars] مهما كان النص: قطعٌ عند آخر
 *    كلمة، أو قطعٌ قسري لنصٍّ بلا مسافات.
 * 2) لا يُفقد حرف: يُحفظ النص كاملاً عدا الفراغات الزائدة على الحدود.
 * 3) القطع المفضَّل عند علامات الجملة ثم عند المسافات، فيقع الحدُّ عند
 *    موضعٍ طبيعيٍّ ما أمكن.
 */
internal object SpeechChunker {

    /** أقصى طول للمقطع الواحد (حرف) — مقاسٌ واحد لكل المسارات. */
    const val MAX_CHARS = 200

    /** أدنى طول لمقطعٍ يُفصل عنده — يمنع شظايا كلمات قصيرة. */
    private const val MIN_CHUNK_CHARS = 40

    /** علامات الجملة المفضَّلة للفصل. */
    private val SENTENCE_DELIMITERS = charArrayOf(
        '.', ',', '!', '?', '؟', '،', '؛', '…', ':', '\n'
    )

    /**
     * يقسّم [text] إلى مقاطع لا يتجاوز طول الواحد منها [maxChars] حرفاً.
     * الفضولُ لعلامة جملة ثم للمسافة، والقطعُ القسري آخرُ ملاذ.
     * نصٌ أقصر من الحدّ يُعاد كما هو في مقطعٍ واحد.
     */
    fun split(text: String, maxChars: Int = MAX_CHARS): List<String> {
        if (maxChars <= 0) return listOf(text)
        if (text.length <= maxChars) return listOf(text)
        val result = ArrayList<String>()
        var rest = text
        while (rest.length > maxChars) {
            val cut = cutIndex(rest, maxChars)
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotBlank()) result.add(piece)
            rest = rest.substring(cut).trimStart()
        }
        val tail = rest.trim()
        if (tail.isNotBlank()) result.add(tail)
        return if (result.isEmpty()) listOf(text) else result
    }

    /** موضع القطع داخل [rest]: بعد آخر علامة جملة ضمن النافذة، وإلا عند
     *  آخر مسافة، وإلا قطعٌ قسري عند [maxChars]. */
    private fun cutIndex(rest: String, maxChars: Int): Int {
        val windowEnd = minOf(maxChars + 1, rest.length)
        for (i in windowEnd - 1 downTo 0) {
            if (i < MIN_CHUNK_CHARS) break
            if (rest[i] in SENTENCE_DELIMITERS) return i + 1
        }
        for (i in windowEnd - 1 downTo 0) {
            if (i < MIN_CHUNK_CHARS) break
            if (rest[i].isWhitespace()) return i
        }
        return maxChars
    }
}