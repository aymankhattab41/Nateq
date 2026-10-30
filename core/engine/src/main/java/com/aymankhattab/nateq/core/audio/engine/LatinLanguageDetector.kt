package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.common.VisibleLanguages

/**
 * كاشف N-gram خفيف للغة النص المنقوش بالحروف اللاتينية القصيرة.
 * يميّز الإنجليزية والفرنسية والألمانية والإسبانية
 * بتحفظ شديد وفق شروط دقيقة (الأمر 3).
 */
object LatinLanguageDetector {

    /** تفعيل تشخيص اللغات في Logcat (الأمر 3). */
    @Volatile
    var diagnosticsEnabled: Boolean = false

    val SUPPORTED_LANGUAGES = listOf("fr", "de", "es", "en")

    /** أقل عدد كلمات يُبرّر الكشف — 3 كلمات فأكثر (الأمر 3). */
    internal const val MIN_WORDS = 3

    /** قائمة الكلمات المشتركة التي تمنع تصنيف المقطع (الأمر 3). */
    private val COMMON_WORDS = setOf(
        "hotel", "chat", "pizza", "restaurant", "menu", "taxi",
        "video", "radio", "internet", "email", "ok"
    )

    private val DECISIVE_LETTERS = mapOf(
        "de" to "äöüß",
        "es" to "ñ¿¡",
        "fr" to "œæç"
    )

    private const val FUNCTION_WORD_WEIGHT = 0.60f
    private const val DIACRITIC_BONUS = 1.20f
    private const val MARGIN = 0.30f
    private const val MIN_CONFIDENCE = 1.10f

    private const val FR_DIACRITICS = "àâæçéèêëîïôœùûü"
    private const val DE_DIACRITICS = "äöüß"
    private const val ES_DIACRITICS = "ñáéíóú"

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

    private const val EN_SAMPLE = (
        "The quick brown fox jumps over the lazy dog while we read " +
        "this short screen aloud. Please open the main menu and " +
        "select the option that says read text. You are now in the " +
        "settings page to choose a voice for this application."
    )

    private const val FR_SAMPLE = (
        "Bonjour et bienvenue sur cet appareil. Pouvez-vous lire ce " +
        "texte à voix haute s'il vous plaît ? Je voudrais régler le " +
        "volume et la vitesse de la prononciation. Merci beaucoup " +
        "pour votre aide précieuse."
    )

    private const val DE_SAMPLE = (
        "Guten Tag und vielen Dank für Ihre Unterstützung. Ich " +
        "möchte gern die aktuelle Uhrzeit auf diesem Gerät wissen. " +
        "Bitte lesen Sie diesen Text langsam und deutlich vor. Wir " +
        "haben heute noch viele Aufgaben zu erledigen."
    )

    private const val ES_SAMPLE = (
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
     * يكشف لغة النص اللاتيني بتحفظ:
     * 1. 3 كلمات فأكثر.
     * 2. خلو النص من أي كلمة مشتركة (COMMON_WORDS).
     * 3. تفوّق المرشح بهامش واضح على الإنجليزية.
     * وإلا يرجع null ليسقط على الإنجليزية أو اللغة الثانية.
     */
    fun detect(text: String): String? {
        decisiveLanguage(text)?.let {
            if (diagnosticsEnabled) {
                android.util.Log.d(
                    "NateqLang",
                    "نص: '$text' -> لغة: $it، السبب: حروف حاسمة"
                )
            }
            return it
        }

        val words = wordsIn(text)
        if (words.size < 2) return null

        val bigrams = lettersOf(text).windowed(2).toSet()
        if (bigrams.isEmpty()) return null

        val enScore = score("en", bigrams, words)
        val ignoredWords = words.filter { it in COMMON_WORDS }
        if (diagnosticsEnabled && ignoredWords.isNotEmpty()) {
            android.util.Log.d(
                "NateqLang",
                "تجاهل الكشف بسبب كلمة مشتركة: $ignoredWords في: '$text'"
            )
        }
        val hasCommonWords = ignoredWords.isNotEmpty()

        if (words.size >= MIN_WORDS && !hasCommonWords) {
            val nonEnglish = listOf("fr", "de", "es")
                .filter { VisibleLanguages.isVisible(it) }
            val bestOther = nonEnglish.maxByOrNull {
                score(it, bigrams, words)
            }
            if (bestOther != null) {
                val otherScore = score(bestOther, bigrams, words)
                val othersSorted = nonEnglish.map {
                    score(it, bigrams, words)
                }.sortedDescending()
                val secondOtherScore = othersSorted.getOrNull(1) ?: 0f

                if (otherScore >= MIN_CONFIDENCE &&
                    otherScore - secondOtherScore >= MARGIN &&
                    otherScore - enScore >= MARGIN
                ) {
                    if (diagnosticsEnabled) {
                        android.util.Log.d(
                            "NateqLang",
                            "نص: '$text' -> لغة: $bestOther، " +
                                "السبب: كاشف لاتيني " +
                                "(فارق: ${otherScore - enScore})"
                        )
                    }
                    return bestOther
                }
            }
        }

        if (enScore >= MIN_CONFIDENCE && VisibleLanguages.isVisible("en")) {
            return "en"
        }
        return null
    }

    private fun decisiveLanguage(text: String): String? {
        for ((language, letters) in DECISIVE_LETTERS) {
            if (!VisibleLanguages.isVisible(language)) continue
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
            val profileMass = profile.bigrams.values.sum().coerceAtLeast(1)
            mass / (bigrams.size * profileMass)
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

    private fun lettersOf(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            if (ch.isLetter()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }
}
