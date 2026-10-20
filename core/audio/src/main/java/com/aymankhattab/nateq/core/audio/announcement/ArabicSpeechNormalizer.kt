package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.engine.stripTashkeelFor
import com.aymankhattab.nateq.util.LanguageCode

/**
 * ضبطٌ لفظي محافظ يُطبَّق على كل نطقٍ في التطبيق (النصوص العامة والأحداث):
 * بعض المحركات العربية (ظاهرة في Vocalizer وأصناف أجهزة معينة) تفكّكُ
 * «واحد» حرفاً حرفاً («و ا ح د») وتُسقط نطقَ التنوين في «مساءً/صباحاً»
 * (كـ«مساء» أو «مساءا») عندما تصل الكلمة بلا ضبط — فأدرجنا هنا كلماتٍ
 * مشكولةً كاملةً تُلزم المحرك بقراءةٍ واحدةٍ سليمة بلا تغيير في النص أو
 * معنى اللفظ. وكلماتُ التنوين تُكتب بنونٍ ساكنة صريحة (مَسَاءَنْ) لا
 * بعلامة التنوين، لأن النون حرفٌ تقرؤه كل المحركات بينما التنوين
 * علامةٌ تُهمَل عند التجريد فيُفقد اللفظ (انظر [MASA_AN]).
 * يُستدعى بعد تطبيع NFC على النص المنطوق.
 */
object ArabicSpeechNormalizer {

    // **نطق التنوين بنونٍ صريحة (لا بعلامة التنوين):** التنوين «ً» علامةُ
    // تشكيلٍ فقط بلا حرف، فمحركٌ يجرّد الحركات (أو لا يفهمها) يهملها
    // فيقرأ «صباحا» ألفاً ممدودةً: «صباحا» بدل «صباحن»/«سُبْحانًا».
    // ونونٍ ساكنة (نْ) حرفٌ مستقلٌّ تقرؤه كل المحركات، فتنطق الكلمة
    // «صباحن» قراءةً سليمةً بلا اعتمادٍ على فهم التشكيل — وهو ما يفشل
    // فعلاً فتصبح النتيجة «غير منونة» مسموعةً.
    const val MASA_AN =
        "\u0645\u064e\u0633\u064e\u0627\u0621\u064e\u0646\u0652"
    const val SABA_AN =
        "\u0635\u064e\u0628\u064e\u0627\u062d\u064e\u0646\u0652"
    const val DHUHR_AN =
        "\u0638\u064e\u0647\u0652\u0631\u064e\u0646\u0652"
    const val ASR_AN =
        "\u0639\u064e\u0635\u0652\u0631\u064e\u0646\u0652"
    const val LAYL_AN =
        "\u0644\u064e\u064a\u0652\u0644\u064e\u0646\u0652"
    const val FAJR_AN =
        "\u0641\u064e\u062c\u0652\u0631\u064e\u0646\u0652"
    const val NAHAR_AN =
        "\u0646\u064e\u0647\u064e\u0627\u0631\u064e\u0646\u0652"

    private val protectedTanweenPatterns = listOf(
        Regex(
            """(?<![\p{L}\p{M}])م[\u064E-\u0652]?س[\u064E-\u0652]?""" +
                """ا[\u064E-\u0652]?ء[ااًًا]?${EngineMarkers.TANWEEN_MARKER}"""
        ) to MASA_AN,
        Regex(
            """(?<![\p{L}\p{M}])ص[\u064E-\u0652]?ب[\u064E-\u0652]?""" +
                """ا[\u064E-\u0652]?ح[ااًًا]?${EngineMarkers.TANWEEN_MARKER}"""
        ) to SABA_AN,
        Regex(
            """(?<![\p{L}\p{M}])ظ[\u064E-\u0652]?ه[\u064E-\u0652]?""" +
                """ر[ااًًا]?${EngineMarkers.TANWEEN_MARKER}"""
        ) to DHUHR_AN,
        Regex(
            """(?<![\p{L}\p{M}])ع[\u064E-\u0652]?ص[\u064E-\u0652]?""" +
                """ر[ااًًا]?${EngineMarkers.TANWEEN_MARKER}"""
        ) to ASR_AN,
        Regex(
            """(?<![\p{L}\p{M}])ل[\u064E-\u0652]?ي[\u064E-\u0652]?""" +
                """ل[ااًًا]?${EngineMarkers.TANWEEN_MARKER}"""
        ) to LAYL_AN,
        Regex(
            """(?<![\p{L}\p{M}])ف[\u064E-\u0652]?ج[\u064E-\u0652]?""" +
                """ر[ااًًا]?${EngineMarkers.TANWEEN_MARKER}"""
        ) to FAJR_AN,
        Regex(
            """(?<![\p{L}\p{M}])ن[\u064E-\u0652]?ه[\u064E-\u0652]?""" +
                """ا[\u064E-\u0652]?ر[ااًًا]?${EngineMarkers.TANWEEN_MARKER}"""
        ) to NAHAR_AN
    )

    /** كلمةٌ خام → بديلها المضبوط (ترتيبٌ فوقَ النص الكامل، أطولُها أولاً). */
    private val replacements = listOf(
        // مَسَاءاً مشكولة بألف التنوين
        "\u0645\u064e\u0633\u064e\u0627\u0621\u0627\u064b" to MASA_AN,
        // مَسَاءً مشكولة مسبقاً بتنوين الهمزة
        "\u0645\u064e\u0633\u064e\u0627\u0621\u064b" to MASA_AN,
        // مساءاً (غير مشكولة بألف وتنوين)
        "\u0645\u0633\u0627\u0621\u0627\u064b" to MASA_AN,
        // صباحاً ← صَبَاحَنْ
        "\u0635\u0628\u0627\u062d\u0627\u064b" to SABA_AN,
        // صباحًا (التنوين على الباء — صيغة الكتابة الحديثة)
        "\u0635\u0628\u0627\u062d\u064b\u0627" to SABA_AN,
        // ظهراً ← ظَهْرَنْ
        "\u0638\u0647\u0631\u0627\u064b" to DHUHR_AN,
        // ظهرًا
        "\u0638\u0647\u0631\u064b\u0627" to DHUHR_AN,
        // عصراً ← عَصْرَنْ
        "\u0639\u0635\u0631\u0627\u064b" to ASR_AN,
        // عصرًا
        "\u0639\u0635\u0631\u064b\u0627" to ASR_AN,
        // ليلاً ← لَيْلَنْ
        "\u0644\u064a\u0644\u0627\u064b" to LAYL_AN,
        // ليلًا
        "\u0644\u064a\u0644\u064b\u0627" to LAYL_AN,
        // فجراً ← فَجْرَنْ
        "\u0641\u062c\u0631\u0627\u064b" to FAJR_AN,
        // فجرًا
        "\u0641\u062c\u0631\u064b\u0627" to FAJR_AN,
        // نهاراً ← نَهَارَنْ
        "\u0646\u0647\u0627\u0631\u0627\u064b" to NAHAR_AN,
        // نهارًا
        "\u0646\u0647\u0627\u0631\u064b\u0627" to NAHAR_AN,
        // واحد ← وَاحِد (فتحة الواو وكسرة الحاء).
        "\u0648\u0627\u062d\u062f" to "\u0648\u064e\u0627\u062d\u0650\u062f",
        // مساءً (بتنوين الهمزة)
        "\u0645\u0633\u0627\u0621\u064b" to MASA_AN
    )

    // صيغٌ بالألف العارية بعد تجريد التشكيل: «صباحا» و«مساءا» و«ظهرا»
    // — يلزمها ضبطٌ مثل نظائرها المشكولة، ويُشترط ألّا يتبعها حرفٌ
    // (وإلا مسّ «صباحات» و«مساءات» في كلمةٍ أخرى). يُشترط كذلك ألّا
    // يسبقها تنوينٌ (وإلا تركنا «صباحاً» لمدخله الأطول أعلاه).
    private val bareAlifPatterns = listOf(
        Regex("""\u0635\u0628\u0627\u062d\u0627(?![\p{L}\p{M}\p{N}])""")
            to SABA_AN,
        Regex("""\u0645\u0633\u0627\u0621\u0627(?![\p{L}\p{M}\p{N}])""")
            to MASA_AN,
        Regex("""\u0638\u0647\u0631\u0627(?![\p{L}\p{M}\p{N}])""")
            to DHUHR_AN,
        Regex("""\u0639\u0635\u0631\u0627(?![\p{L}\p{M}\p{N}])""")
            to ASR_AN,
        Regex("""\u0644\u064a\u0644\u0627(?![\p{L}\p{M}\p{N}])""")
            to LAYL_AN,
        Regex("""\u0641\u062c\u0631\u0627(?![\p{L}\p{M}\p{N}])""")
            to FAJR_AN,
        Regex("""\u0646\u0647\u0627\u0631\u0627(?![\p{L}\p{M}\p{N}])""")
            to NAHAR_AN
    )

    // نمط يلتقط «مساء» المجردة كظرف زمان في سياق الوقت (دون مس «مساء الخير»)
    // سواء في نهاية الجملة أو في وسطها (متبوعةً بمسافة وكلمات أخرى أو ترقيم).
    private val timeWords = "(?:الآن|\\d+|" +
        "(?:[ولب]?ال)?(?:نصف|ربع|ثلث|دقيقة|دقائق|دقيقتين|ساعة|ساعات|" +
        "عشر|عشرة|واحد|واحدة|اثنان|اثنين|ثلاث|ثلاثة|أربع|أربعة|" +
        "خمس|خمسة|ست|ستة|سبع|سبعة|ثماني|ثمان|ثمانية|تسع|تسعة|" +
        "أول|أولى|ثان|ثاني|ثانية|ثالث|ثالثة|رابع|رابعة|خامس|خامسة|" +
        "سادس|سادسة|سابع|سابعة|ثامن|ثامنة|تاسع|تاسعة|عاشر|عاشرة|" +
        "حادي|حادية))"
    private val timeMasaRegex = Regex(
        """((?:^|\s)$timeWords\s+)مساء(?![\p{L}\p{M}\p{N}])"""
    )

    /** يطبّق القاموس على النص كاملاً (لا يلمس غيرَ العربية). */
    fun normalize(text: String, isTashkeelAware: Boolean = false): String {
        var out = text
        if (!isTashkeelAware) {
            for ((pattern, to) in protectedTanweenPatterns) {
                out = pattern.replace(out, to)
            }
            for ((pattern, to) in bareAlifPatterns) {
                out = pattern.replace(out, to)
            }
            for ((from, to) in replacements) {
                out = out.replace(from, to)
            }
            out = timeMasaRegex.replace(out) { m ->
                m.groupValues[1] + MASA_AN
            }
        }
        if (out.contains(EngineMarkers.TANWEEN_MARKER)) {
            out = out.replace(EngineMarkers.TANWEEN_MARKER, "")
        }
        if (out.contains(EngineMarkers.NUMBER_MARK_START)) {
            out = out.replace(EngineMarkers.NUMBER_MARK_START, "")
        }
        if (out.contains(EngineMarkers.NUMBER_MARK_END)) {
            out = out.replace(EngineMarkers.NUMBER_MARK_END, "")
        }
        return out
    }

    /**
     * تجهيز نص عربي للمحرك مع مراعاة قدرة المحرك على فهم التشكيل (بند 3.4):
     * إن كان المحرك يفهم التشكيل تُحذف علامة الحفظ دون استبدال التنوين بنون.
     */
    fun prepareArabicForEngine(
        text: String,
        languageTag: String,
        enginePackage: String? = null
    ): String {
        if (!LanguageCode.isArabic(languageTag)) {
            return text.replace(EngineMarkers.TANWEEN_MARKER, "")
                .replace(EngineMarkers.NUMBER_MARK_START, "")
                .replace(EngineMarkers.NUMBER_MARK_END, "")
        }
        val isTashkeelAware = !stripTashkeelFor(enginePackage)
        return normalize(text, isTashkeelAware = isTashkeelAware)
    }
}

/**
 * دالة واحدة موحدة لتجهيز النص العربي للمحرك (بند 3.4)،
 * تُستدعى من NateqTtsService والإعلانات والمعاينة.
 */
fun prepareArabicForEngine(
    text: String,
    languageTag: String,
    enginePackage: String? = null
): String = ArabicSpeechNormalizer.prepareArabicForEngine(
    text, languageTag, enginePackage
)
