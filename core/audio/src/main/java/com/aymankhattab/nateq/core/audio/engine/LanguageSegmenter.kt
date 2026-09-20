package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.util.LanguageCode

/**
 * مقطع لغوي واحد ضمن نصٍ مختلط الكتابات — يُنطق هذا المقطع بلغة [languageTag]
 * ومحركها وصوتها المخصصين، ثم يليه المقطع التالي بذاك الجهاز اللغوي.
 */
data class Segment(
    val text: String,
    val languageTag: String
)

/**
 * يقسم نصاً مختلط الكتابات إلى مقاطع متجاورة حسب الـ Script، ويميز كل
 * سكربت غير عربي بلغته المعروفة عبر [Character.UnicodeScript] القياسي:
 * اللاتينية ⟶ سقوطُ الطلب (إنجليزية افتراضياً)، الروسية/البلغارية ⟶ ru
 * (سيريلية)،
 * العبرية ⟶ he، اليونانية ⟶ el، الصينية ⟶ zh (هان)، اليابانية ⟶ ja
 * (هيراغانا/كاتاكانا)، الكورية ⟶ ko (هانغول)، التايلاندية ⟶ th،
 * الديفاناغارية ⟶ hi — فلا تُمرَّر حروفٌ سيريلية/عبرية/صينية لمحركٍ
 * إنجليزي كما كان (كلُّ سكربتٍ محددٍ كان يقع على «سقوط» واحد).
 * سكربت LATIN لا يُحدِّد لغةً من حروفه القصيرة وحدها، فتُجمَّع جولاته
 * المتجاورة في مقطعٍ واحد ثم يُكشف لسانه عبر [LatinLanguageDetector]
 * (بند ب.txt 3.6-2: كلمتان فأكثر من fr/de/es/en بثقةٍ واضحة)، وإلا
 * تُنسب (سقوطاً) للغة الطلب إن كانت لاتينية غير العربية، وإلا
 * [secondaryLanguage] التي يختارها المستخدم (بند اللغة الثانية) ثم
 * [EN_FALLBACK] عند غيابها — كشفٌ محافظ يتراجع لسقوطٍ معلوم عند اللبس.
 * المقاطع العربية تُنطق بالعربية. المحايدات — مسافات/ترقيم/رموز —
 * تلتحق بالمقطع المجاور ولا تُكسر عن سياقها (يلتحق المحايد بالمقطع المفتوح
 * السابق، والمحايد القيادي بالمقطع اللاحق)، فالتجميع عبر كل المقاطع يعيد
 * النص الأصلي حرفياً بلا فقدان. الأرقام تُنسب فوراً وحصرياً إلى لغة
 * نطق الأرقام المحددة في الإعدادات (ar أو en) بلا استثناء لأي سياق.
 *
 * لا يعتمد المقسم على أي كائن Android — منطق نقي قابل للاختبار مباشرة.
 */
class LanguageSegmenter(
    private val numberLanguageProvider: (() -> String)? = null
) {

    companion object {
        /** لغة السقوط لسائر الكتابات ضمن الطلب العربي (الإنجليزية). */
        val EN_FALLBACK: String get() = LanguageCode.EN.tag

        /** علاّمة داخلية لتجميع جولات السكربت اللاتيني المتجاورة في مقطعٍ
         *  واحد قبل كشف لغته (بند ب.txt 3.6-2) — ليست لغةً نطقية، تُستبدل
         *  بنتيجة الكاشف أو بسقوطه في [buildSegment] عند إغلاق المقطع. */
        private const val LATIN_PLACEHOLDER = "\u0000latin"

        /** الكتل السكربتية المعروفة اللغة حتماً ⟶ ISO-639-1 (توسيع
         *  ك«المدونة»): كل سكربت محددٍ في خريطة لغته قبل سقوط اللاتينية. */
        private val SCRIPT_LANGUAGE_TAGS: Map<Character.UnicodeScript, String> =
            mapOf(
                Character.UnicodeScript.CYRILLIC to "ru",
                Character.UnicodeScript.HEBREW to "he",
                Character.UnicodeScript.GREEK to "el",
                Character.UnicodeScript.HAN to "zh",
                Character.UnicodeScript.HIRAGANA to "ja",
                Character.UnicodeScript.KATAKANA to "ja",
                Character.UnicodeScript.HANGUL to "ko",
                Character.UnicodeScript.THAI to "th",
                Character.UnicodeScript.DEVANAGARI to "hi",
                Character.UnicodeScript.ETHIOPIC to "am"
            )

        private val ARABIC_RANGES = arrayOf(
            0x0600..0x06FF, // العربية الأساسية
            // (شاملة التشكيل والأرقام العربية-الهندية)
            0x0750..0x077F, // التذييل العربي
            0x0870..0x089F, // العربية الموسّعة-ب
            0x08A0..0x08FF, // العربية الموسّعة-أ
            0xFB50..0xFDFF, // صيغ عرض عربية-أ
            0xFE70..0xFEFF, // صيغ عرض عربية-ب
            0x10EC0..0x10EFF, // العربية الموسّعة-ج
            0x1EE00..0x1EEFF // رموز الرياضيات العربية
        )

        /** فئات Unicodes المحايدة (فواصل/رموز/فواصل مسطرة) — قناع بتات Long
         *  يُبنى مرةً واحدة من قائمة الفئات: كل فئة تُفعّل بتها (1L shl type).
         *  أعلى فئة Character قيمةً هي 30 (FINAL_QUOTE_PUNCTUATION) فقناع
         *  الـ Long يتسع لها بلا فيض، والاختبار البتي (mask and بٍت) أسرع
         *  من البحث الخطي في مصفوفة على كل حرفٍ من النص. */
        private val NEUTRAL_CATEGORY_MASK: Long =
            listOf(
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
            ).fold(0L) { mask, category -> mask or (1L shl category) }
    }

    private enum class Kind { SCRIPT, NUMBER, NEUTRAL }

    private class Run(
        val kind: Kind,
        val script: Character.UnicodeScript?,
        val start: Int
    )

    /**
     * @param fallbackLanguage لغة السقوط القادمة من الطلب/الإعلان: العربية
     *  «وغير المعروفة/الفارغة» سقوطُها لحروف الكتابات سائرٍ هي
     *  [EN_FALLBACK]؛ أي طلبٍ آخر (fr/de/…) تُنسب له الحروف غير
     *  العربية مباشرة ليُنطق النص الأجنبي
     *  بصوت لغته. والنصُّ المَحايد وحده (رموز بلا حروف وأرقام) يُنسب كلُّه للغة
     *  السقوط نفسها.
     * @param secondaryLanguage لغة النطق الاحتياطية للمقطع اللاتيني القصير
     *  غير المتحسَّم ضمن الطلب العربي (بند اللغة الثانية): كلمة «Bonjour»
     *  الوحيدة لا يحسمها [LatinLanguageDetector] فتُنطق بهذه اللغة (إن
     *  سُلّمت صحيحة) عوض [EN_FALLBACK]؛ القيمة الفارغة/غير المعروفة تعود
     *  لـ [EN_FALLBACK] سقوطاً أخيراً. لا أثر لها على الطلب غير العربي
     *  (تُنسب الحروف للغة الطلب نفسها حتماً).
     * @param numberLanguage لغة نطق الأرقام الحصرية المستقلة عن السياق.
     * @return مقاطع النص المتجاورة بلغاتها؛ النص الخالي يُرجع مقطعاً واحداً
     *  بلغة السقوط حتى لا يُعالَج النص الفارغ بشكلٍ خاص في المسارات العليا.
     */
    fun segment(
        text: String,
        fallbackLanguage: String = LanguageCode.AR.tag,
        secondaryLanguage: String = EN_FALLBACK,
        numberLanguage: String = numberLanguageProvider?.invoke()
            ?: LanguageCode.AR.tag
    ): List<Segment> {
        return merge(
            text,
            scriptFallback(fallbackLanguage, secondaryLanguage),
            neutralFallback(fallbackLanguage),
            numberLanguage
        )
    }

    /** يبني المقاطع من الجولات عبر «مقطعٍ مفتوح» يمتد على إحداثيات النص:
     *  الأرقام تُنسب فوراً لـ [numberLanguage]، والمحايد يلتحق بالمقطع المفتوح،
     *  والمقاطع المتجاورة بنفس اللغة تُدمج تلقائياً دون تفتيت. */
    private fun merge(
        text: String,
        scriptFallback: String,
        neutralFallback: String,
        numberLanguage: String
    ): List<Segment> {
        val runs = buildRuns(text)
        if (runs.isEmpty()) return listOf(Segment(text, neutralFallback))

        val segments = ArrayList<Segment>()
        var openStart = -1
        var openLanguage: String? = null
        var leadingStart = -1
        for (run in runs) {
            when (run.kind) {
                Kind.NEUTRAL -> {
                    if (openLanguage == null && leadingStart == -1) {
                        leadingStart = run.start
                    }
                    // وإلا فهو بيني\ختامي: نطاق المقطع المفتوح يشمل إحداثياته.
                }
                Kind.SCRIPT, Kind.NUMBER -> {
                    val language = if (run.kind == Kind.NUMBER) {
                        numberLanguage
                    } else {
                        runLanguage(run.script, scriptFallback)
                    }
                    if (openLanguage == null) {
                        openStart =
                            if (leadingStart != -1) leadingStart else run.start
                        leadingStart = -1
                        openLanguage = language
                    } else if (openLanguage != language) {
                        var cutPoint = run.start
                        if (cutPoint > 0 && cutPoint > openStart) {
                            val prevChar = text[cutPoint - 1]
                            if (prevChar == '@' || prevChar == '#') {
                                cutPoint -= 1
                            }
                        }
                        addSegment(
                            segments,
                            buildSegment(
                                text, openStart, cutPoint,
                                openLanguage, scriptFallback
                            )
                        )
                        openStart = cutPoint
                        openLanguage = language
                    }
                    // نفس اللغة: يمدّ النهاية إلى نهاية الجولة
                    // (المحايد بينهما داخلٌ).
                }
            }
        }
        if (openLanguage != null) {
            addSegment(
                segments,
                buildSegment(
                    text, openStart, text.length,
                    openLanguage, scriptFallback
                )
            )
        }
        if (segments.isEmpty()) return listOf(Segment(text, neutralFallback))
        return segments
    }

    /** يضيف مقطعاً جديداً ويدمجه مع السابق إن تطابقت لغتهما المحسومة. */
    private fun addSegment(
        segments: ArrayList<Segment>,
        newSegment: Segment
    ) {
        if (newSegment.text.isEmpty()) return
        val last = segments.lastOrNull()
        if (last != null && last.languageTag == newSegment.languageTag) {
            segments[segments.size - 1] = Segment(
                last.text + newSegment.text,
                last.languageTag
            )
        } else {
            segments.add(newSegment)
        }
    }

    /** يبني مقطعاً نهائياً: المقطعُ اللاتيني المتراكم يُكشف لسانه عبر
     *  [LatinLanguageDetector] (بند ب.txt 3.6-2)، وإن لم يحسم يُترك لسقوطه
     *  المحافظ ([scriptFallback] كما قبل الكشف). سائرُ المقاطع بلغتها
     *  الثابتة. */
    private fun buildSegment(
        text: String,
        start: Int,
        end: Int,
        language: String,
        scriptFallback: String
    ): Segment {
        val tag = if (language == LATIN_PLACEHOLDER) {
            val isHashtagOrHandle = (start > 0 &&
                (text[start - 1] == '@' || text[start - 1] == '#')) ||
                (end > start &&
                (text[start] == '@' || text[start] == '#'))
            if (isHashtagOrHandle) {
                scriptFallback
            } else {
                val candidate = LatinLanguageDetector.detect(
                    text.substring(start, end)
                )
                candidate ?: scriptFallback
            }
        } else {
            language
        }
        return Segment(text.substring(start, end), tag)
    }

    private fun buildRuns(text: String): List<Run> {
        val runs = ArrayList<Run>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val (kind, script) = kindOf(codePoint)
            val width = Character.charCount(codePoint)
            val start = index
            index += width
            val last = runs.lastOrNull()
            if (last == null ||
                last.kind != kind || last.script != script
            ) {
                // ركض جديد (لا نُخزّن نهايته: يستدلّ منها الدمج
                // عبر إحداثيات الركض التالي أو نهاية النص).
                runs.add(Run(kind, script, start))
            }
        }
        return runs
    }

    /** حل المحايدات أولاً ثم العربي والسكربت من [Character.UnicodeScript]
     *  القياسي. الكتابات العامة ([COMMON]/[INHERITED]) أُعِدَّت كحروفٍ
     *  شبيهةٍ بالترقيم
     *  فلا تنتمي لكِتَابٍ ما ننسبه لسكربتٍ آخر، بل تُعدُّ محايدةً (مسافة رفيعة/
     *  فاصلة اتجاه / علامات رقم Bidi) يساندها المقطع المجاور. */
    private fun kindOf(codePoint: Int): Pair<Kind, Character.UnicodeScript?> {
        // الأرقام أولاً (بكل أنظمة العدّ): تُنسب فوراً وحصرياً للغة الأرقام.
        if (Character.isDigit(codePoint)) {
            return Kind.NUMBER to null
        }
        if (Character.isWhitespace(codePoint)) {
            return Kind.NEUTRAL to null
        }
        if ((NEUTRAL_CATEGORY_MASK and (1L shl Character.getType(codePoint)))
                != 0L) {
            return Kind.NEUTRAL to null
        }
        if (isArabic(codePoint)) {
            return Kind.SCRIPT to Character.UnicodeScript.ARABIC
        }
        if (Character.isLetter(codePoint)) {
            val script = Character.UnicodeScript.of(codePoint)
            if (script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.INHERITED
            ) {
                return Kind.NEUTRAL to null
            }
            return Kind.SCRIPT to script
        }
        return Kind.NEUTRAL to null
    }

    private fun isArabic(codePoint: Int): Boolean {
        for (range in ARABIC_RANGES) {
            if (codePoint in range) return true
        }
        return false
    }

    /** لغة السكربت القاطعة من الخريطة (سيريلية ⟶ ru، صينية ⟶ zh…).
     *  اللاتينية تُجمَّع كعلاّمةٍ داخلية يُكشف لسانُها عند إغلاق المقطع؛
     *  وسائرُ السكربتات غير المعيّنة تُنسب إلى [scriptFallback] (لغة الطلب
     *  إن كانت لاتينية وإلا الإنجليزية). */
    private fun runLanguage(
        script: Character.UnicodeScript?,
        scriptFallback: String
    ): String {
        if (script == null) return scriptFallback
        return when (script) {
            Character.UnicodeScript.ARABIC -> LanguageCode.AR.tag
            Character.UnicodeScript.LATIN -> LATIN_PLACEHOLDER
            else -> SCRIPT_LANGUAGE_TAGS[script] ?: scriptFallback
        }
    }

    /** لغة سقوط حروف الكتابات غير العربية: طلب عربي/فارغ ← اللغة الثانية التي
     *  يختارها المستخدم (بند اللغة الثانية) وإن كانت فارغة/غير معروفة ←
     *  [EN_FALLBACK]؛ وإلا بِلغة الطلب نفسها حتى يُنطق النص الأجنبي بصوت
     *  لغته عند طلبٍ غير عربي. */
    private fun scriptFallback(
        requestLanguage: String,
        secondaryLanguage: String
    ): String {
        val language = requestLanguage.takeWhile { it.isLetter() }
        return when {
            language.isBlank() -> EN_FALLBACK
            LanguageCode.isArabic(language) -> secondaryOrEn(secondaryLanguage)
            else -> language
        }
    }

    /** لغة سقوط اللاتينية غير المتحسَّمة ضمن الطلب العربي: [secondaryLanguage]
     *  إن سُلّمت بلغةٍ صالحة وإلا [EN_FALLBACK] (يُبقى سقوطاً
     *  أخيراً وحيداً). */
    private fun secondaryOrEn(secondaryLanguage: String): String {
        val language = secondaryLanguage.takeWhile { it.isLetter() }
        return if (language.isBlank()) EN_FALLBACK else language
    }

    /** لغة ما لا يحوي حروفاً إطلاقاً (أرقام/رموز/مسافات فقط): لغة الطلب نفسها
     *  إن عُرفت — فالأرقام تُنطق بلسان طلبها ولو كان عربياً —
     *  وإلا [EN_FALLBACK]
     *  للطلب الغامض/الفارغ (التاريخي). */
    private fun neutralFallback(requestLanguage: String): String {
        val language = requestLanguage.takeWhile { it.isLetter() }
        return when {
            language.isBlank() -> EN_FALLBACK
            LanguageCode.isArabic(language) -> LanguageCode.AR.tag
            else -> language
        }
    }
}