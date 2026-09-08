package com.aymankhattab.nateq.engine

/**
 * مقطع لغوي واحد ضمن نصٍ مختلط الكتابات — يُنطق هذا المقطع بلغة [languageTag]
 * ومحركها وصوتها المخصصين، ثم يليه المقطع التالي بذاك الجهاز اللغوي.
 */
data class Segment(
    val text: String,
    val languageTag: String
)

/**
 * يقسم نصاً مختلط الكتابات (عربي/إنجليزي/غيرها) إلى مقاطع متجاورة حسب الـ Script:
 * المقاطع العربية تُنطق بالعربية، وسائر حروف الكتابة تُنسب (سقوطاً) إما للغة
 * الطلب نفسها إذا لم تكن عربية وإما للإنجليزية افتراضياً. المحايدات — مسافات/
 * أرقام/ترقيم/رموز — تلتحق بالمقطع المجاور ولا تُكسر عن سياقها، فالتجميع عبر
 * كل المقاطع يعيد النص الأصلي حرفياً بلا فقدان.
 *
 * لا يعتمد المقسم على أي كائن Android — منطق نقي قابل للاختبار مباشرة.
 */
class LanguageSegmenter {

    companion object {
        /** لغة السقوط لسائر الكتابات ضمن الطلب العربي. */
        const val EN_FALLBACK = "en"

        private val ARABIC_RANGES = arrayOf(
            0x0600..0x06FF, // العربية الأساسية (شاملة التشكيل والأرقام العربية-الهندية)
            0x0750..0x077F, // التذييل العربي
            0x08A0..0x08FF, // العربية الموسّعة-أ
            0xFB50..0xFDFF, // صيغ عرض عربية-أ
            0xFE70..0xFEFF  // صيغ عرض عربية-ب
        )

        /** فئات Unicodes المحايدة (فواصل/رموز/فواصل مسطرة) — تُحوَّل من Byte
         *  Java إلى Int للتوافق مع [Character.getType] برمجياً. */
        private val NEUTRAL_CATEGORIES = intArrayOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt()
        )
    }

    private enum class Kind { ARABIC, OTHER, NEUTRAL }

    private data class Run(val kind: Kind, val start: Int, val endExclusive: Int)

    /**
     * @param requestLanguage لغة الطلب القادمة من النظام: العربية «وغير المعروفة»
     *  سقوطُها لسائر الكتابات هي [EN_FALLBACK]؛ أي طلبٍ آخر (fr/de/…) تُنسب له
     *  الكتابات غير العربية مباشرة ليُنطق النص الأجنبي بصوت لغته.
     * @return مقاطع النص المتجاورة بلغاتها؛ النص الخالي يُرجع مقطعاً واحداً
     *  بلغة السقوط حتى لا يُعالَج النص الفارغ بشكلٍ خاص في المسارات العليا.
     */
    fun segment(text: String, requestLanguage: String): List<Segment> {
        val fallback = nonArabicFallback(requestLanguage)
        if (text.isEmpty()) return listOf(Segment("", fallback))
        return merge(text, fallback)
    }

    /** يبني المقاطع من الجولات عبر «مقطعٍ مفتوح» يمتد على إحداثيات النص الأصلي:
 *  المحايد بعدُ يلتحق بالمقطع المفتوح (رأسيٌّ يدخل في فتحته الأولى)، والمحايد
 *  بين ركضتين من لغةٍ واحدة يضمّهما معاً دون تفتيت، والمحايد الختامي يشمله
 *  نطاقُ المقطع الأخير حتى نهاية النص. */
    private fun merge(text: String, fallback: String): List<Segment> {
        val runs = buildRuns(text)
        if (runs.isEmpty()) return listOf(Segment(text, fallback))

        val segments = ArrayList<Segment>()
        var openStart = -1
        var openLanguage: String? = null
        var leadingStart = -1
        for (run in runs) {
            when (run.kind) {
                Kind.NEUTRAL -> {
                    if (openLanguage == null && leadingStart == -1) leadingStart = run.start
                    // وإلا فهو بيني\ختامي: نطاق المقطع المفتوح يشمل إحداثياته.
                }
                else -> {
                    val language = if (run.kind == Kind.ARABIC) "ar" else fallback
                    if (openLanguage == null) {
                        openStart = if (leadingStart != -1) leadingStart else run.start
                        leadingStart = -1
                        openLanguage = language
                    } else if (openLanguage != language) {
                        segments.add(Segment(text.substring(openStart, run.start), openLanguage))
                        openStart = run.start
                        openLanguage = language
                    }
                    // نفس اللغة: يمدّ النهاية إلى نهاية الجولة (المحايد بينهما داخلٌ).
                }
            }
        }
        if (openLanguage != null) {
            segments.add(Segment(text.substring(openStart, text.length), openLanguage))
        }
        if (segments.isEmpty()) return listOf(Segment(text, fallback))
        return segments
    }

    private fun buildRuns(text: String): List<Run> {
        val runs = ArrayList<Run>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val kind = kindOf(codePoint)
            val width = Character.charCount(codePoint)
            val start = index
            index += width
            val last = runs.lastOrNull()
            if (last != null && last.kind == kind) {
                runs[runs.size - 1] = last.copy(endExclusive = index)
            } else {
                runs.add(Run(kind, start, index))
            }
        }
        return runs
    }

    private fun kindOf(codePoint: Int): Kind {
        // المحايدات أولاً: المسافات والأرقام (بكل أنظمة العدّ) والفواصل لا
        // تنتمي لسكريبتٍ معين أياً كانت خانة المقاطع المجاورة.
        if (Character.isWhitespace(codePoint) || Character.isDigit(codePoint)) return Kind.NEUTRAL
        if (NEUTRAL_CATEGORIES.contains(Character.getType(codePoint))) return Kind.NEUTRAL
        if (isArabic(codePoint)) return Kind.ARABIC
        if (Character.isLetter(codePoint)) return Kind.OTHER
        return Kind.NEUTRAL
    }

    private fun isArabic(codePoint: Int): Boolean {
        for (range in ARABIC_RANGES) {
            if (codePoint in range) return true
        }
        return false
    }

    /** لغة سقوط الكتابات غير العربية: طلب عربي/فارغ ← [EN_FALLBACK]؛ وإلا فبِلغة
     *  الطلب نفسها حتى يُنطق النص الأجنبي بصوت لغته عند طلبٍ غير عربي. */
    private fun nonArabicFallback(requestLanguage: String): String {
        val language = requestLanguage.takeWhile { it.isLetter() }
        return if (language.isBlank() || language.startsWith("ar", ignoreCase = true)) {
            EN_FALLBACK
        } else {
            language
        }
    }
}