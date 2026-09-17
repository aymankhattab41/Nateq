package com.aymankhattab.nateq.core.audio.engine

/**
 * كاشف N-gram خفيف للغة النص المنقوش بالحروف اللاتينية القصيرة
 * (بند ب.txt 3.6-2): يميّز الإنجليزية والفرنسية والألمانية والإسبانية
 * من «بصمة» ثنائيات الرموز والكلمات الوظيفية والتشكيل دون أي اعتماد
 * على Android — منطق نقي قابل للاختبار مباشرة.
 *
 * أسلوب الكشف:
 *  1. يُستخلص مدى الحروف من النص فيُحسب [score]: تغطيةُ ثنائيات النص
 *     ضمن «ملامح» اللغة (نصّ تمثيلي مدمج) + وزن كلماتها الوظيفية +
 *     مكافأة أحرف التشكيل المميّزة (ç/œ ⟶ فرنسية، ß/ä/ö/ü ⟶ ألمانية،
 *     ñ ⟶ إسبانية…) — فرنسيةٌ بلا تشكيل («Bonjour comment allez-vous»)
 *     تبقى فارِقةً عبر بصمة ثنائياتها ومفرداتها القصيرة.
 *  2. يُختار اللون الأعلى درجةً إن تفوّق بوضوح على ثانيها (هامش +
 *     حدّ ثقة)، وإلا يرجع [detect] null فيتولى [LanguageSegmenter]
 *     سقوطَهُ الآمن (لغة الطلب إن كانت لاتينية وإلا الإنجليزية) —
 *     نطقٌ صحيح أغلى من حكمٍ لغةٍ طموح.
 *
 * ## متى يُستدعى؟
 * على المقطع اللاتيني المتراكم حين يبلغ 3 كلمات فأكثر؛ المقتطفُ بدونها
 * تُترك حروفه لسقوطٍ محافظ (حرفان مثل «Merci beaucoup» لا يُبرران كشفاً).
 */
object LatinLanguageDetector {

    /** اللغات المدعومة بالكشف — المجموعة الأوروبية القارية التي حُدِّدت
     *  ملامحها الفعلية (لا كشف لتركية/برتغالية/إيطالية بعد). */
    val SUPPORTED_LANGUAGES = listOf("fr", "de", "es", "en")

    /** أقل عدد كلماتٍ يُبرّر الكشف — مقتطفٌ بمصطلحين قد يُتلمس لكن
     *  اللّبس فيه فوق متحمل الحكم القاطع. */
    internal const val MIN_WORDS = 3

    /** أحرف قاطعة حصرية لا توجد في الإنجليزية وتنتمي لكلغةٍ واحدةٍ ضمن
     *  اللغات المدعومة — تُسمح بالكشف المبكر (دون بلوغ [MIN_WORDS])
     *  لأنها تحسم اللسان بلا لبس: نصٌ قصيرٌ يحملها «نطقٌ صحيح أغلى من
     *  حكمٍ طموح» — لا تُحسب بالتدرج بل تُسمّى لغتها فوراً. */
    private val DECISIVE_LETTERS = mapOf(
        "de" to "äöüß",
        "es" to "ñ¿¡",
        "fr" to "œæç"
    )

    /** وزن كل كلمة وظيفية تخص اللغة (the/le/der/el…) — إشارة تركيبية
     *  بعيدة عن بضع ثنائيات قصيرة. */
    private const val FUNCTION_WORD_WEIGHT = 0.60f

    /** مكافأة كل حرف تشكيل مميّز للغة (ç، ß، ñ، ä…) — دليل قاطع يزن
     *  كعدة ثنائيات إذ قد يخلو النص اللاتيني القصير من الكلمات الوظيفية
     *  الطويلة. */
    private const val DIACRITIC_BONUS = 1.20f

    /** هامش الفوز بين أعلى لغة وتاليتها — دونَه يُترك الحكم معلقاً. */
    private const val MARGIN = 0.30f

    /** حدّ أدنى لثقة الفائز حتى لا «يربح» لغةٌ بلا أثرٍ يذكر. */
    private const val MIN_CONFIDENCE = 1.00f

    /** أحرف التشكيل الفرنسية المميّزة (قد تتقاسم ü مع الألمانية). */
    private val FR_DIACRITICS = "àâæçéèêëîïôœùûü"
    private val DE_DIACRITICS = "äöüß"
    private val ES_DIACRITICS = "ñáéíóú"

    private val EN_FUNCTION_WORDS = setOf(
        "the", "and", "that", "this", "with", "for", "you",
        "have", "not", "are", "was", "from", "will", "your"
    )
    private val FR_FUNCTION_WORDS = setOf(
        "le", "la", "les", "des", "du", "un", "une", "et",
        "est", "que", "qui", "dans", "pour", "vous", "je",
        "cette", "avec", "sur"
    )
    private val DE_FUNCTION_WORDS = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ein",
        "eine", "ich", "zu", "mit", "wir", "für", "den",
        "auf", "auch", "die"
    )
    private val ES_FUNCTION_WORDS = setOf(
        "el", "la", "los", "las", "del", "una", "que", "por",
        "con", "para", "nos", "sus", "más", "hay", "cómo"
    )

    /** ملامح اللغة: خريطة ثنائي ⟶ تكراره في نصٍّ تمثيلي للغة. يعتمد الحكم
     *  على «الغطاء» النسبي لمجموعة ثنائيات النص ضمن الملامح (0..1). */
    private class Profile(language: String, sample: String) {
        val languageTag = language
        val bigrams: Map<String, Int> = run {
            val letters = sample.lowercase()
                .filter { it.isLetter() }
            val map = HashMap<String, Int>()
            for (i in 0 until letters.length - 1) {
                val key = letters.substring(i, i + 2)
                map[key] = (map[key] ?: 0) + 1
            }
            map
        }
    }

    /** نصوصٌ تمثيلية مختصرة لكل لغة تُبنى منها بصمات الثنائيات — كلمات
     *  وجملٌ شائعة في واجهات التطبيق والإعلانات القصيرة التي ينطقها قارئ
     *  الشاشة (ولا تُضمّن أي بيانات حقيقية للمستخدم). */
    private val EN_SAMPLE = (
        "The quick brown fox jumps over the lazy dog while we read " +
        "this short screen aloud. Please open the main menu and " +
        "select the option that says read text. You are now in the " +
        "settings page to choose a voice for this application."
        )

    private val FR_SAMPLE = (
        "Bonjour et bienvenue sur cet appareil. Pouvez-vous lire ce " +
        "texte à voix haute s'il vous plaît ? Je voudrais régler le " +
        "volume et la vitesse de la prononciation. Merci beaucoup " +
        "pour votre aide précieuse."
        )

    private val DE_SAMPLE = (
        "Guten Tag und vielen Dank für Ihre Unterstützung. Ich " +
        "möchte gern die aktuelle Uhrzeit auf diesem Gerät wissen. " +
        "Bitte lesen Sie diesen Text langsam und deutlich vor. Wir " +
        "haben heute noch viele Aufgaben zu erledigen."
        )

    private val ES_SAMPLE = (
        "Buenos días y muchas gracias por su ayuda. Quisiera saber " +
        "la hora actual en este dispositivo de manera rápida. Por " +
        "favor lea este texto y también la siguiente palabra. " +
        "Nosotros tenemos muchas tareas para hoy."
        )

    private val profiles: Map<String, Profile> = mapOf(
        "en" to Profile("en", EN_SAMPLE),
        "fr" to Profile("fr", FR_SAMPLE),
        "de" to Profile("de", DE_SAMPLE),
        "es" to Profile("es", ES_SAMPLE)
    )

    /**
     * يكشف لغة النص اللاتيني الممتد؛ يرجع null عند اللبس (كلمات أقل
     * من [MIN_WORDS] أو فوزٌ غير حاسم) فيتولى المتصل سقوطَهُ الآمن.
     */
    fun detect(text: String): String? {
        val words = wordsIn(text)
        // نصٌّ قصير (< [MIN_WORDS]) بحرفٍ قاطع حصري يُكشف فوراً —
        // «Café»/«München»/«¿Qué?» مصطلحٌ واحد يحسم لسانه بلا تدرج.
        if (words.size < MIN_WORDS) {
            decisiveLanguage(text)?.let { return it }
        }
        if (words.size < MIN_WORDS) return null
        val bigrams = lettersOf(text).windowed(2).toSet()
        if (bigrams.isEmpty()) return null
        val ranked = SUPPORTED_LANGUAGES
            .map { lang -> lang to score(lang, bigrams, words) }
            .sortedByDescending { it.second }
        val best = ranked[0]
        val second = ranked.getOrNull(1)?.second ?: 0f
        if (best.second >= MIN_CONFIDENCE &&
            best.second - second >= MARGIN
        ) {
            return best.first
        }
        return null
    }

    /** يبحث في النص عن أول حرفٍ قاطع حصري؛ يرجع لغته أو null. حرفٌ مثل
     *  ß/ñ/œ لا تَلبَّس بين اللغات الأربع المدعومة فيكفي للكشف المبكر. */
    private fun decisiveLanguage(text: String): String? {
        for ((language, letters) in DECISIVE_LETTERS) {
            if (text.any { it.lowercaseChar() in letters }) return language
        }
        return null
    }

    private fun score(
        language: String,
        bigrams: Set<String>,
        words: List<String>
    ): Float {
        val profile = profiles.getValue(language)
        var mass = 0.0
        for (bigram in bigrams) {
            mass += profile.bigrams[bigram] ?: 0
        }
        val coverage = if (bigrams.isEmpty()) {
            0.0
        } else {
            mass / bigrams.size
        }
        var total = coverage.toFloat()
        for (word in words) {
            if (functionWordsOf(language).contains(word)) {
                total += FUNCTION_WORD_WEIGHT
            }
            for (ch in word) {
                if (ch in diacriticsOf(language)) {
                    total += DIACRITIC_BONUS
                }
            }
        }
        return total
    }

    private fun functionWordsOf(language: String): Set<String> =
        when (language) {
            "en" -> EN_FUNCTION_WORDS
            "fr" -> FR_FUNCTION_WORDS
            "de" -> DE_FUNCTION_WORDS
            "es" -> ES_FUNCTION_WORDS
            else -> emptySet()
        }

    private fun diacriticsOf(language: String): String =
        when (language) {
            "fr" -> FR_DIACRITICS
            "de" -> DE_DIACRITICS
            "es" -> ES_DIACRITICS
            else -> ""
        }

    /** كلمات النص بمجرد الحروف (لا أرقام/فواصل) — أساس عداد الحد الأدنى
     *  وفحص المفردات الوظيفية. */
    private fun wordsIn(text: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (ch.isLetter()) {
                current.append(ch.lowercaseChar())
            } else if (current.isNotEmpty()) {
                result.add(current.toString())
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    /** مسار الحروف منضّمٌ بلا فواصل — أساس الثنائيات (تقفز عبر الكلمات
     *  كما تحلل ملامحُ العيّنة نفسها). */
    private fun lettersOf(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            if (ch.isLetter()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }
}