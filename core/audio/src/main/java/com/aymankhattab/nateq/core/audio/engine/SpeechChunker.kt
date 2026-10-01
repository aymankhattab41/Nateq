package com.aymankhattab.nateq.core.audio.engine

/**
 * تقسيم النصوص الطويلة إلى وحدات نطق قصيرة — شبكةُ أمانٍ في
 * **مسار الإعلانات فقط** ([AnnouncementSpeaker]).
 *
 * **ليس حلّاً لانقطاع النص الطويل، وقد أُزيل من مسار القارئ لأجل
 * ذلك:** حين اشتُقَّ عام v106 كان يُعالج العَرَض (توقّف النطق) لا
 * سببه (مهلةُ الطلب أقصرُ من ميزانية التخليق)، فكان يُسقط كلمةً
 * بصمت عند فشل أيّ مقطع، ويُقطع الكلام عند حدود القطع، ويفصل
 * نبرةَ الجملة الواحدة. السببُ الجذريُّ مُصلَح في [SynthesisBudget]
 * (ميزانيةٌ واحدة تضمن أن كلَّ مهلةٍ خارجية تغطي ميزانيتها
 * الداخلية) و[SystemVoiceProvider] (إتمامٌ بالطول المُعلَن في رأس
 * WAV)، فيُسلَّم نصُّ القارئ كلُّه طلباً واحداً متّصلاً.
 *
 * وبقي هنا في الإعلانات لسببٍ مختلف: كلُّ وحدةٍ تُنطق مستقلّةً في
 * طابور المتحدّث، ففشلُ وحدةٍ لا يُسقط ما بعدها، وهذا ما لا يصحّ
 * في حلقة التخليق داخل القارئ.
 *
 * الضمانات:
 * 1) أطول وحدةٍ لا يتجاوز طولها [maxChars] مهما كان النص: قطعٌ عند
 *    آخر كلمة، أو قطعٌ قسري لنصٍّ بلا مسافات.
 * 2) لا يُفقد حرف: يُحفظ النص كاملاً عدا الفراغات الزائدة على الحدود.
 * 3) القطع المفضَّل عند علامات الجملة ثم عند المسافات، فيقع الحدُّ عند
 *    موضعٍ طبيعيٍّ ما أمكن.
 */
internal object SpeechChunker {

    /** أقصى طول للوحدة الواحدة (حرف) — مقاسٌ واحد للإعلانات. */
    const val MAX_CHARS = 200

    /** أدنى طول لوحدةٍ تُفصل عنده — يمنع شظايا كلمات قصيرة. */
    private const val MIN_UNIT_CHARS = 40

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
            if (i < MIN_UNIT_CHARS) break
            if (rest[i] in SENTENCE_DELIMITERS) return i + 1
        }
        for (i in windowEnd - 1 downTo 0) {
            if (i < MIN_UNIT_CHARS) break
            if (rest[i].isWhitespace()) return i
        }
        return maxChars
    }
}