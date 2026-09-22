package com.aymankhattab.nateq.engine

/**
 * محوّل فرانكو-آراب (بند الأوامر د.3.6): كلماتٌ عربية تُكتب بحروفٍ لاتينية
 * وأرقام بديلة («3ashan» = عشان، «7abibi» = حبيبي، «5ales» = خالص…).
 * بدون تحويل كان المحركُ يقرؤها أحرفاً/أرقاماً إنجليزية («three a s h a n»).
 *
 * التحويل متحفِّظ بلا أثر على الإنجليزية السليمة:
 *  - كلمة واردة في معجم المصطلحات الشائعة تُستبدل كاملة.
 *  - وإلا تُحوَّل إبداعياً فقط إذا كانت كلُّ محارفها محارفَ فرانكو مؤكدة
 *    (حروف لاتينية + رقم فرانكو 2/3/4/5/6/7/9) وطولها ≤ 12 — أي عبارة
 *    إنجليزية سليمة (لا أرقام فرانكو داخلها) تبقى كما هي تماماً.
 *  - ما لم يكن واثقاً يُرجع النص الأصلي (السلوك القديم).
 */
object FrancoArabic {

    /** معجم المصطلحات الشائعة المؤكدة — نطقٌ عربي سليم مئة في المئة. */
    private val commonWords = mapOf(
        "3ashan" to "عشان",
        "3alashan" to "علشان",
        "7abibi" to "حبيبي",
        "7abiby" to "حبيبي",
        "5ales" to "خالص",
        "khalas" to "خلاص",
        "inshalla" to "إن شاء الله",
        "inshallah" to "إن شاء الله",
        "mashaallah" to "ما شاء الله",
        "alhamdulillah" to "الحمد لله",
        "ezzay" to "إزاي",
        "feen" to "فين",
        "3ala" to "على",
        "3alayk" to "عليك",
        "3endo" to "عنده",
        "3and" to "عند",
        "m3aya" to "معايا",
        "7aga" to "حاجة",
        "balash" to "بلاش",
        "kolo" to "كله",
        "ana" to "أنا",
        "enta" to "إنت",
        "ento" to "إنتو",
        "okay" to "أوكيه",
        "sorry" to "آسف",
        "thankyou" to "شكرا"
    )

    /** رقم فرانكو الشائع ↔ حرفه العربي المقابل. */
    private val digitToArabic = mapOf(
        '2' to 'أ', '3' to 'ع', '4' to 'ش',
        '5' to 'خ', '6' to 'ط', '7' to 'ح', '9' to 'ص'
    )

    /** الحروف اللاتينية المعرَّفة في التحويل الإبداعي (مع ثنائيات صوتية). */
    private val latinToArabic = mapOf(
        "th" to "ث", "kh" to "خ", "sh" to "ش", "dh" to "ذ",
        "gh" to "غ", "ch" to "تش",
        "b" to "ب", "t" to "ت", "j" to "ج", "h" to "ه",
        "d" to "د", "r" to "ر", "z" to "ز", "s" to "س",
        "e" to "ي", "g" to "غ", "k" to "ك", "l" to "ل",
        "m" to "م", "n" to "ن", "w" to "و", "y" to "ي",
        "a" to "ا", "i" to "ي", "o" to "و", "u" to "و",
        "f" to "ف", "c" to "س"
    )

    /** هل الرقمُ رقمُ فرانكو فعلي (لمسات 1/0/8 شاذة لا تُحسب)؟ */
    private fun isFrancoDigit(c: Char): Boolean = c in digitToArabic

    /** أقصى طول كلمة يُحوَّل إبداعياً حتى لا يمتد على جُملة. */
    private const val MAX_CREATIVE_LENGTH = 12

    /** نمط عزل رموز الفرانكو مجمّع مسبقاً لمنع إعادة تصريفه. */
    private val TOKEN_REGEX = Regex("[A-Za-z0-9]+")

    /** يبدّل كلمات الفرانكو في [text] مع بقاء كل ما عداه كما هو. */
    fun convert(text: String): String {
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (match in TOKEN_REGEX.findAll(text)) {
            sb.append(text, cursor, match.range.first)
            val word = match.value
            sb.append(convertWord(word) ?: word)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private val TECHNICAL_TERMS = setOf(
        "A4", "A3", "A5", "H2O", "CO2", "S3", "MP3", "MP4",
        "4K", "3D", "2D", "F1", "M4A", "B5", "C4", "HTML5",
        "CSS3", "IPV4", "IPV6"
    )

    /** يحوّل كلمةً واحدة؛ null = تُبقى كما هي (غير واثق من كونها فرانكو). */
    private fun convertWord(word: String): String? {
        if (word.length > MAX_CREATIVE_LENGTH) return null
        if (word.uppercase() in TECHNICAL_TERMS) return null
        val lower = word.lowercase()
        commonWords[lower]?.let { return it }
        // الإبداع المتحفظ: لا يُلمس إلا إن كانت شاركتٌ فيها الأرقام الفعلاً
        // مع حروفٍ لاتينية وكلُّ محارفها داخل مساحتي الخرائط.
        if (word.none { it.isLetter() }) return null
        if (word.none { isFrancoDigit(it) }) return null
        return creativeFrom(lower)
    }

    /** ترجمة إبداعية حرفاً حرفاً (مع الثنائيات أولاً)؛ null عند محرفٍ غريب. */
    private fun creativeFrom(word: String): String? {
        val sb = StringBuilder(word.length + 2)
        var i = 0
        while (i < word.length) {
            if (i + 1 < word.length) {
                val digraph = latinToArabic[word.substring(i, i + 2)]
                if (digraph != null) {
                    sb.append(digraph)
                    i += 2
                    continue
                }
            }
            val mapped = latinToArabic[word[i].toString()]
                ?: digitToArabic[word[i]]?.toString()
                ?: return null
            sb.append(mapped)
            i += 1
        }
        return sb.toString()
    }
}