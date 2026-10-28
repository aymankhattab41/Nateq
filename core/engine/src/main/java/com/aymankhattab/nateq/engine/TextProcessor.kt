package com.aymankhattab.nateq.engine

import android.content.Context
import com.aymankhattab.nateq.core.audio.engine.SegmentWithOffset
import com.aymankhattab.nateq.core.engine.EngineMarkers
import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.core.engine.SynthesisConfig
import com.aymankhattab.nateq.engine.pipeline.AcronymStep
import com.aymankhattab.nateq.engine.pipeline.ArabicSlashStep
import com.aymankhattab.nateq.engine.pipeline.CleanupStep
import com.aymankhattab.nateq.engine.pipeline.CurrencyStep
import com.aymankhattab.nateq.engine.pipeline.DateStep
import com.aymankhattab.nateq.engine.pipeline.DictionaryStep
import com.aymankhattab.nateq.engine.pipeline.EmojiStripStep
import com.aymankhattab.nateq.engine.pipeline.FrancoArabicStep
import com.aymankhattab.nateq.engine.pipeline.IndicDigitsStep
import com.aymankhattab.nateq.engine.pipeline.NumberStep
import com.aymankhattab.nateq.engine.pipeline.NumberWordsConverter
import com.aymankhattab.nateq.engine.pipeline.PhoneNumberStep
import com.aymankhattab.nateq.engine.pipeline.PunctuationStep
import com.aymankhattab.nateq.engine.pipeline.RomanNumeralStep
import com.aymankhattab.nateq.engine.pipeline.SymbolStep
import com.aymankhattab.nateq.engine.pipeline.TanweenProtectStep
import com.aymankhattab.nateq.engine.pipeline.TashkeelStripStep
import com.aymankhattab.nateq.engine.pipeline.TextProcessingStep
import com.aymankhattab.nateq.engine.pipeline.TimeStep
import com.aymankhattab.nateq.engine.pipeline.UnitStep
import com.aymankhattab.nateq.engine.pipeline.UrlStep
import com.aymankhattab.nateq.util.LanguageCode
import java.text.Normalizer
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * محركات الـ TTS المعروفة بفهمها النطقي للتشكيل العربي (الحركات/الشدة/
 * التنوين): تُحتفظ لهذه الفئة بالتشكيل في الناتج النهائي (عبر مسار الاستعادة —
 * [TextProcessor.process]]) فلا تُنطق الحركاتُ حروفاً غريبة ولا يُجرد نصٌّ
 * يجيد المحركُ قراءته. الباقية (غير المعروفة) تُسلك السلوكَ القائم: تجريد.
 */
val ARABIC_TASHKEEL_AWARE_ENGINES: Set<String> = setOf(
    "com.google.android.tts"
)

/**
 * هل نجرد التشكيل العربي من النص قبل تسليمه للمحرك [enginePackage]؟
 * الإفتراض: نعم (تجريد — أغلب المحركات لا تنطق الحركات بوضوح). محركٌ معروف
 * بفهمه التشكيل (انظر [ARABIC_TASHKEEL_AWARE_ENGINES]) وnull (غير معروف)
 * يُعالَجان بحذر: المعروف يُبقي التشكيل، وغير المعروف يُجرد (سلوك سابق).
 * للمحرك المدمج vocalizer_embedded (الجولة 53 - الأمر 5): يعيد true صريحاً
 * لتجريد التشكيل العام وحفظ ما يضيفه مطبّع النون الصريحة.
 */
fun stripTashkeelFor(enginePackage: String?): Boolean = when (enginePackage) {
    "vocalizer_embedded" -> true
    else -> enginePackage !in ARABIC_TASHKEEL_AWARE_ENGINES
}

/**
 * معالج النصوص الذكي — يحول النصوص الخام إلى نصوص قابلة للنطق طبيعياً.
 *
 * منسّق لخط معالجة نمطي [TextProcessingStep]: يمثل الترقيم/الترتيب الأصلي
 * للتحويلات (روابط، تواريخ، أوقات، عملات، وحدات، أرقام رومانية، هواتف،
 * رموز، أرقام) في خطوات منفصلة تسهل اختبارها وترتيبها، مع بقاء الواجهة
 * العامة متطابقة ([process] و [numberToWords]).
 */
class TextProcessor(
    private val context: Context,
    /** المرجع المحقون عبر Hilt إن وُجد (يمرره NateqTtsService)، وإلا يُبنى
     *  محلياً لإعدادات النطق. */
    private val injectedSettings: SynthesisConfig? = null,
    /** القاموس المحقون عبر Hilt إن وُجد (يمرره NateqTtsService). */
    private val injectedDict: PronunciationDictionary? = null
) {

    /**
     * **الافتراضيُّ هو [PronunciationDictionary.shared] لا `new`.**
     *
     * كان الغيابُ يُنتج `PronunciationDictionary(context)` — نسخةً جديدةً
     * **فارغة** لكل معالِج — فتنعدم القاموسُ في مسار النطق بينما يراه
     * المستخدمُ في الإعدادات. والمعالجُ الذي لا يحقن قاموساً (بناءُ
     * AnnouncementSpeaker هو المثال) كان يصيبُ العطبَ كلَّه. فصار
     * الافتراضيُّ نفسَ الكائن، وبقي [PronunciationDictionary] المباشرُ
     * للاختبارات التي تحتاج عزلةً.
     */
    private val pronunciationDict =
        injectedDict ?: PronunciationDictionary.shared(context)

    /**
     * إبطال التخزين المؤقت للقاموس وتصفير خنق فحص القرص
     * ليُعاد التحميل من القرص فوراً عند النطق التالي.
     */
    fun invalidateDictionary() {
        pronunciationDict.invalidate()
    }

    /**
     * مدخل القاموس الوحيد — يُطبَّق مرة واحدة على النص الخام قبل أي معالجة:
     * تطبيع NFC فقط ثم تطبيق القاموس وإرجاع النص (الأمر 1 و2).
     */
    fun applyDictionaryFirst(
        text: String,
        languageTag: String,
        precomputedSegments: List<SegmentWithOffset>? = null
    ): String {
        if (text.isEmpty()) return text
        return try {
            pronunciationDict.reloadIfChanged()
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            pronunciationDict.apply(
                normalized,
                languageTag,
                precomputedSegments
            )
        } catch (t: Throwable) {
            android.util.Log.e(
                "NateqDict",
                "خطأ في تطبيق القاموس على: ${text.take(80)}",
                t
            )
            text
        }
    }

    /**
     * هل نطق أسماء الإيموجي مفعّل؟ بلا حقنة Settings
     * (الاختبارات) يُفترض مفعّل.
     */
    private val emojiEnabled: Boolean
        get() = injectedSettings?.isEmojiPronunciationEnabled() ?: true


    /**
     * حفظ تشكيل الكلمات العربية المُرسلة للمحرك (بند 1.7): التجريد
     * الداخلي للمطابقة يبقى كما هو، لكن الكلمات الأصلية غير المتحوّلة
     * تُعاد بكلماتها المشكولة — فينطق المحركُ العربيُّ الفاهم للتشكيل
     * الحركاتَ بوضوح. بلا حقنة Settings يُفترض معطّل (السلوك القائم).
     */
    private val tashkeelPreserved: Boolean
        get() = injectedSettings?.isTashkeelPreserved() ?: false

    /** خطوة نطق علامات الترقيم — تتبع مستوى «البعض/الكل» قراءةً لحظية. */
    private val punctuationStep = PunctuationStep {
        injectedSettings?.getPunctuationLevel() ?: PunctuationLevels.SOME
    }

    /** خطوة نطق الأرقام — تتبع إعداد لغة ونمط نطق الأرقام لحظياً. */
    private val numberStep = NumberStep(
        languageProvider = { injectedSettings?.getNumberReadingLanguage() },
        modeProvider = { injectedSettings?.getNumberReadingMode() ?: 1 }
    )

    /** خطوة نطق أرقام الهواتف — تتبع إعداد لغة ونمط نطق الأرقام لحظياً. */
    private val phoneNumberStep = PhoneNumberStep(
        languageProvider = { injectedSettings?.getNumberReadingLanguage() },
        modeProvider = { injectedSettings?.getNumberReadingMode() ?: 1 }
    )

    // خطوات التمهيد: تُنفَّذ قبل بوابة المسار السريع
    // (تطبيع/تشكيل/إيموجي/قاموس).
    // «إزالة الإيموجي» شرطية: تعمل فقط عند تعطيل نطقها (إلا تُعيد النص).
    // «تصحيحُ ألف التنوين» (مساءاً ← مَسَاءَنْ) ليس هنا: مِلكُه
    // ArabicSpeechNormalizer في :core:audio، ويُطبَّق على النص المنطوق بعد
    // المعالجة مباشرةً، فهو مِلكٌ واحدٌ لتلك الصيغ لا تكرارٌ في خطٍّ آخر.
    private fun buildPreamble(): List<TextProcessingStep> {
        val steps = mutableListOf<TextProcessingStep>()
        val s = injectedSettings
        steps += IndicDigitsStep
        if (s?.isArabicNormalizationEnabled() != false) {
            steps += FrancoArabicStep()
        }
        steps += TanweenProtectStep
        steps += TashkeelStripStep
        steps += EmojiStripStep { emojiEnabled }
        return steps
    }

    private fun buildBaseSteps(
        markNumbers: Boolean = false
    ): List<TextProcessingStep> {
        val steps = mutableListOf<TextProcessingStep>()
        val s = injectedSettings
        steps += IndicDigitsStep
        steps += UrlStep
        if (s?.isTimeConversionEnabled() != false) {
            steps += DateStep(markNumbers = markNumbers)
        }
        if (s?.isTimeConversionEnabled() != false) {
            steps += TimeStep(
                modeProvider = { s?.getTimeSuffixMMode() ?: "context" },
                markNumbers = markNumbers
            )
        }
        if (s?.isCurrencyConversionEnabled() != false) {
            if (markNumbers) {
                steps += object : TextProcessingStep {
                    override fun apply(input: String): String =
                        CurrencyStep.apply(input, markNumbers = true)
                    override fun applyEnglish(input: String): String =
                        CurrencyStep.applyEnglish(input, markNumbers = true)
                }
            } else {
                steps += CurrencyStep
            }
        }
        if (s?.isUnitConversionEnabled() != false) {
            if (markNumbers) {
                steps += object : TextProcessingStep {
                    override fun apply(input: String): String =
                        UnitStep.apply(input, markNumbers = true)
                }
            } else {
                steps += UnitStep
            }
        }
        steps += AcronymStep
        steps += ArabicSlashStep
        return steps
    }

    private fun buildHeavySteps(
        markNumbers: Boolean = false
    ): List<TextProcessingStep> {
        val steps = mutableListOf<TextProcessingStep>()
        val s = injectedSettings
        steps += buildBaseSteps(markNumbers)
        steps += RomanNumeralStep
        if (s?.isPhoneConversionEnabled() != false) {
            if (markNumbers) {
                steps += PhoneNumberStep(
                    languageProvider = {
                        injectedSettings?.getNumberReadingLanguage()
                    },
                    modeProvider = {
                        injectedSettings?.getNumberReadingMode() ?: 1
                    },
                    markNumbers = true
                )
            } else {
                steps += phoneNumberStep
            }
        }
        if (s?.isSymbolConversionEnabled() != false) steps += SymbolStep
        steps += punctuationStep
        if (markNumbers) {
            steps += NumberStep(
                languageProvider = {
                    injectedSettings?.getNumberReadingLanguage()
                },
                modeProvider = {
                    injectedSettings?.getNumberReadingMode() ?: 1
                },
                markNumbers = true
            )
        } else {
            steps += numberStep
        }
        return steps
    }

    private fun buildEnglishPreamble(): List<TextProcessingStep> = listOf(
        IndicDigitsStep,
        EmojiStripStep { emojiEnabled }
    )

    private fun buildEnglishBaseSteps(
        markNumbers: Boolean = false
    ): List<TextProcessingStep> {
        val steps = mutableListOf<TextProcessingStep>()
        val s = injectedSettings
        steps += IndicDigitsStep
        if (s?.isTimeConversionEnabled() != false) {
            steps += DateStep(markNumbers = markNumbers)
        }
        if (s?.isCurrencyConversionEnabled() != false) {
            if (markNumbers) {
                steps += object : TextProcessingStep {
                    override fun apply(input: String): String =
                        CurrencyStep.apply(input, markNumbers = true)
                    override fun applyEnglish(input: String): String =
                        CurrencyStep.applyEnglish(input, markNumbers = true)
                }
            } else {
                steps += CurrencyStep
            }
        }
        return steps
    }

    private fun buildEnglishHeavySteps(
        markNumbers: Boolean = false
    ): List<TextProcessingStep> {
        val steps = mutableListOf<TextProcessingStep>()
        val s = injectedSettings
        steps += buildEnglishBaseSteps(markNumbers)
        if (s?.isPhoneConversionEnabled() != false) {
            if (markNumbers) {
                steps += PhoneNumberStep(
                    languageProvider = {
                        injectedSettings?.getNumberReadingLanguage()
                    },
                    modeProvider = {
                        injectedSettings?.getNumberReadingMode() ?: 1
                    },
                    markNumbers = true
                )
            } else {
                steps += phoneNumberStep
            }
        }
        if (s?.isSymbolConversionEnabled() != false) steps += SymbolStep
        steps += punctuationStep
        if (markNumbers) {
            steps += NumberStep(
                languageProvider = {
                    injectedSettings?.getNumberReadingLanguage()
                },
                modeProvider = {
                    injectedSettings?.getNumberReadingMode() ?: 1
                },
                markNumbers = true
            )
        } else {
            steps += numberStep
        }
        return steps
    }

    /**
     * معالجة نص كامل وتحويله لصيغة نطق طبيعية.
     * @param languageTag كود اللغة (مثلاً "ar"، "en"، "ar-EG")
     *                    — العربية لها مسارها، والإنجليزية مسارٌ موازٍ
     *                      بمفرداتها، وبقية اللغات تُعاد كما هي.
     * @param enginePackage حزمة المحرك الـ TTS المستخدم (بند التشكيل الشرطي):
     *                      بعض المحركات (مثل Google TTS) تفهم التشكيل العربي
     *                      فتُنطق الحركات بوضوح — يحتفظ بنص المشكولة في
     *                      الإخراج عبر [restoreTashkeel]. القيمة null تعني
     *                      السلوك القائم.
     * @param applyDictionary هل يُطبّق القاموس هنا؟ افتراضياً true لمن
     *                        يستدعيها مباشرة (الاختبارات)؛ المسارات الحية
     *                        تمرر false لأنها طبقت القاموس أولاً (الأمر 1).
     * @param markNumbers هل تُحاط الأرقام بعلامات الحجز \uE030..\uE031؟
     */
    fun process(
        text: String,
        languageTag: String = LanguageCode.AR.tag,
        enginePackage: String? = null,
        applyDictionary: Boolean = true,
        markNumbers: Boolean = false
    ): String {
        if (text.isBlank()) return text

        val noCommas = stripCommasBetweenDigits(text)
        var result = Normalizer.normalize(noCommas, Normalizer.Form.NFC)
        if (applyDictionary) {
            result = applyDictionaryFirst(result, languageTag)
        }

        // تفسير SSML المحدود (بند الأوامر د.3.7): يترجم <break> و<say-as>
        // والكيانات قبل كل الفروع (العربية والإنجليزية واللغات الأخرى) —
        // هويةٌ تامة على النص العادي فلا تكلف أثراً على المسارات السريعة.
        result = SsmlStep.apply(result)


        // نطق أسماء الإيموجي (بدل حذفها) قبل مسار العربية ليغطي الإنجليزية
        // واللغات الأخرى — الناتج لا يُمرَّر لأي تحويل لاحق خارج العربية.
        val expanded =
            if (emojiEnabled) expandEmojis(result, languageTag) else null

        // الإنجليزية: مسار موازٍ بمفرداتها (لا تحويل أرقامٍ إنجليزية إلى
        // كلماتٍ عربية) — الأرقام/التواريخ/العملات/الهواتف/الرموز/الترقيم
        // تُنطق إنجليزية، مع حجب الروابط كي لا تشوّهها خطوةُ الترقيم.
        if (LanguageCode.isEnglish(languageTag)) {
            var out = expanded ?: result
            for (step in buildEnglishPreamble()) out = step.applyEnglish(out)
            val urls = ArrayList<String>()
            out = maskUrls(out, urls)
            if (requiresEnglishPipeline(out) || markNumbers) {
                for (step in buildEnglishHeavySteps(markNumbers)) {
                    out = step.applyEnglish(out)
                }
            }
            out = unmaskUrls(out, urls)
            if (markNumbers) {
                val spelledEnabled = injectedSettings
                    ?.isNumberVoiceSpelledWordsEnabled() ?: true
                if (spelledEnabled) {
                    out = NumberWordDetector.detectAndMark(out, languageTag)
                }
                out = markRemainingDigits(out)
            }
            val cleaned = CleanupStep.apply(out)
            return stripNumberMarkersIfUnmarked(cleaned, markNumbers)
        }

        // اللغات الأخرى (fr/de/es…) تُعاد كما هي بعد توسيع الإيموجي فقط.
        if (!LanguageCode.isArabic(languageTag)) {
            if (expanded != null) {
                val cleaned = CleanupStep.apply(expanded)
                return stripNumberMarkersIfUnmarked(cleaned, markNumbers)
            }
            return stripNumberMarkersIfUnmarked(result, markNumbers)
        }

        // text قد يحوي إيموجي عُرضت أسماؤها (expanded) أو تُحذف لاحقاً
        if (expanded != null) result = expanded
        // بند 1.7: نسخة مشكولة من النص (بعد توسيع الإيموجي وقبل الجرد)
        // تُحفظ لاستعادة تشكيل الكلمات الأصلية غير المتحوّلة حين يفعّل
        // المستخدم الحفظ — وإلا تبقى null فتسلك المعالجةُ السلوكَ الحالي
        // نفسه (تجريد التشكيل وتسليم النص مجرداً للمحرك).
        // **بند التشكيل الشرطي:** المحركات التي تفهم التشكيل العربي
        // (مثل Google TTS) تحتفظ بالتشكيل في الإخراج تلقائياً — نفعّل لها
        // نفس مسار الاستعادة [restoreTashkeel] بلا اشتراط تفضيل المستخدم.
        val voweledSource = if (tashkeelPreserved ||
            !stripTashkeelFor(enginePackage)
        ) {
            result
        } else {
            null
        }
        val urls = ArrayList<String>()
        var out = maskUrls(result, urls)
        for (step in buildPreamble()) out = step.apply(out, languageTag)

        // المسار السريع (Fast-path): إن لم يحتوِ النص على أي محفِّز لأرقام
        // الرموز/الصيغ (أرقام، فواصل، رموز عملة، حروف رومانية...) — أي نص
        // عربي صافٍ بلا أرقام — نتخطى كل مراحل regex الثقيلة (التواريخ/
        // الأوقات/العملات/الروابط/الرومانية/الهواتف/الأرقام/الرموز) ونذهب
        // مباشرةً لتنظيف المسافات. يوفّر تريليونات المطابقات على كل إعلان.
        if (!requiresRegexPipeline(out) && !markNumbers) {
            out = unmaskUrls(out, urls)
            val cleaned = CleanupStep.apply(out)
            val finalized = finalizeArabic(voweledSource, cleaned)
            return stripNumberMarkersIfUnmarked(finalized, markNumbers)
        }

        for (step in buildHeavySteps(markNumbers)) out = step.apply(out)
        out = unmaskUrls(out, urls)
        if (markNumbers) {
            val spelledEnabled = injectedSettings
                ?.isNumberVoiceSpelledWordsEnabled() ?: true
            if (spelledEnabled) {
                out = NumberWordDetector.detectAndMark(out, languageTag)
            }
            out = markRemainingDigits(out)
        }
        val cleaned = CleanupStep.apply(out)
        val finalized = finalizeArabic(voweledSource, cleaned)
        return stripNumberMarkersIfUnmarked(finalized, markNumbers)
    }

    /**
     * تتبع المسار كاملاً مرحلة بمرحلة (الجولة 51 - الأمر 3).
     */
    fun processWithTrace(
        text: String,
        languageTag: String = LanguageCode.AR.tag,
        enginePackage: String? = null
    ): List<Pair<String, String>> {
        val trace = mutableListOf<Pair<String, String>>()
        if (text.isEmpty()) return trace

        val hexRepr = buildHexRepresentation(text)
        trace.add("1. النص الخام (مع المحارف الخاصة)" to hexRepr)

        val afterDict = applyDictionaryFirst(text, languageTag)
        val dictMatches = pronunciationDict.traceMatches(text, languageTag)
        val dictSummary = if (dictMatches.isEmpty()) {
            afterDict
        } else {
            val details = dictMatches.joinToString("\n") { m ->
                val st = if (m.accepted) "مقبول" else "مرفوض"
                "  • [${m.key} -> ${m.value}] (${m.matchMode}): " +
                    "$st (${m.reason})"
            }
            "$afterDict\nمطابقات القاموس:\n$details"
        }
        trace.add("2. بعد تطبيق القاموس (applyDictionaryFirst)" to dictSummary)

        var current = SsmlStep.apply(afterDict)
        trace.add("3. بعد معالجة الدلالات (SSML/Semantics)" to current)

        val expanded =
            if (emojiEnabled) expandEmojis(current, languageTag) else null
        if (expanded != null) {
            current = expanded
            trace.add("4. بعد توسيع الإيموجي" to current)
        }

        val voweledSource = if (tashkeelPreserved ||
            !stripTashkeelFor(enginePackage)
        ) {
            current
        } else {
            null
        }

        val urls = ArrayList<String>()
        current = maskUrls(current, urls)

        for (step in buildPreamble()) {
            val name = step.javaClass.simpleName.ifBlank { "PreambleStep" }
            current = step.apply(current, languageTag)
            trace.add("5. خطوة تمهيدية [$name]" to current)
        }

        for (step in buildHeavySteps(false)) {
            val name = step.javaClass.simpleName.ifBlank { "HeavyStep" }
            current = step.apply(current)
            trace.add("5. خطوة معالجة [$name]" to current)
        }

        current = unmaskUrls(current, urls)
        current = CleanupStep.apply(current)
        val finalized = finalizeArabic(voweledSource, current)
        trace.add("5. بعد تنظيف المسافات والتشكيل" to finalized)

        return trace
    }

    private fun buildHexRepresentation(input: String): String {
        val sb = StringBuilder()
        for (cp in input.codePoints()) {
            if (cp < 0x20 || (cp in 0x7F..0x9F) || cp in 0x200B..0x200F ||
                cp == 0x061C || cp == 0xFEFF || cp == 0x00AD ||
                cp in 0x202A..0x202E || cp in 0x2066..0x2069
            ) {
                sb.append(String.format("[U+%04X]", cp))
            } else {
                sb.append(String(Character.toChars(cp)))
            }
        }
        return sb.toString()
    }

    /**
     * تُكمل الناتج العربي النهائي (بعد معالجته وتنظيفه) بأحد مسارين:
     * عند تعطيل حفظ التشكيل تُسلَّم مُنظّفة كما هي، وعند تفعيله تُمرَّر
     * عبر [restoreTashkeel] ليعود لكل كلمةٍ لم تحوّلها خطوةٌ تشكيلُها
     * الأصلي — بلا أي أثر على منطق التحويل نفسه (بند 1.7).
     */
    private fun finalizeArabic(
        voweledSource: String?,
        cleaned: String
    ): String =
        if (voweledSource != null) restoreTashkeel(voweledSource, cleaned)
        else cleaned

/**
     * يعيد تطعيم تشكيل الكلمات الأصلية غير المتحوّلة إلى الناتج النهائي
     * (بند 1.7): يقارن كل كلمةٍ في النتيجة بنظيرها في المصدر (المطابقة
     * على الصيغة المجردة — كلمةٌ بقيت كما هي تُستبدل بصيغتها المشكولة،
     * وكلمةٌ محوّلة (رقم→لفظ، تاريخ، عملة...) تعجز عن ملاقاةِ من
     * نظيرها فتبقى بصيغة التحويل). بلا أي أثر على منطق التحويل نفسه.
     */
    private fun restoreTashkeel(
        voweledSource: String,
        processed: String
    ): String {
        if (processed.isEmpty()) return processed
        // معجم المصدر: كل كلمة مشكولة مقابل مجردها — تتم المحاكاة بعد
        // تقسيمٍ متوازٍ، والمشكولةُ الأصلي تُؤخذ من المصدر مباشرة.
        val normalizedSource = voweledSource
            .replace(PATTERN_WHITESPACE, " ").trim()
        val stripped = TashkeelStripStep.apply(normalizedSource)
        val sourceStripped = stripped.split(' ')
        val sourceVoweled = normalizedSource.split(' ')
        if (sourceStripped.size != sourceVoweled.size) return processed
        if (sourceStripped.isEmpty()) return processed
        // مطابقة كلمة-بكلمة بترتيب «أقرب متاح غير مستهلك»: الكلمات
        // المتحوّلة لا تُستهلك نظيرها، فيبقى متاحاً للكلمة الصحيحة
        // التالية — مطابقة لطيفة لا تصطدم بالمتكررات.
        val consumed = BooleanArray(sourceStripped.size)
        val rebuilt = StringBuilder(processed.length)
        var added = false
        for (word in processed.split(' ')) {
            if (word.isEmpty()) continue
            if (added) rebuilt.append(' ')
            var matched = false
            for (i in sourceStripped.indices) {
                if (consumed[i] || sourceStripped[i] != word) continue
                rebuilt.append(sourceVoweled[i])
                consumed[i] = true
                matched = true
                break
            }
            if (!matched) rebuilt.append(word)
            added = true
        }
        return rebuilt.toString()
    }

    /**
     * معالجة دلالية مبكرة للنص الكامل — تُستدعى قبل تقسيم اللغة
     * في NateqTtsService حتى لا يقسم مقسّمُ اللغاتِ «1500 USD» إلى
     * [مبلغ عربي, رمز إنجليزي] فيفقد
     * [CurrencyStep] مبلغَه المكسورُ أو يُنطق «USD» حروفاً بمحركٍ إنجليزي.
     * لا تشمل الخطوات الثقيلة الباقية (رومانية/هواتف/رموز/أرقام) لأن
     * كلَ نصٍ مقطعي يعاد تمريره عبر [process] لاحقاً بكامل الخطوات، فلا
     * يُكرر تحويل الأرقام هنا ولا يُستخدم الناتج إلا لتهيئة التقسيم.
     * @param languageTag كود اللغة (مثل "ar"، "en") — المعالجة مخصصة للعربية
     *                    فقط كـ[process]؛ غير العربية تُعاد كما هي.
     * @param markNumbers هل تُحاط الأرقام والألفاظ العددية بعلامات الحجز؟
     */
    fun processSemantics(
        text: String,
        languageTag: String = LanguageCode.AR.tag,
        markNumbers: Boolean = false
    ): String {
        if (text.isBlank()) return text
        // إزالة الفواصل بين الأرقام عربي أو إنجليزي
        val cleaned = stripCommasBetweenDigits(text)
        // توحيد NFC عند المدخل مطابقاً لـ[process] — يبقى الإخراج مطابقاً
        // لنمط التقسيم المعدَّ مسبقاً على صيغة موحَّدة.
        val normalized = Normalizer.normalize(cleaned, Normalizer.Form.NFC)
        val spelledWordsEnabled =
            injectedSettings?.isNumberVoiceSpelledWordsEnabled() ?: true

        if (LanguageCode.isEnglish(languageTag)) {
            val urls = ArrayList<String>()
            var english = maskUrls(normalized, urls)
            if (requiresEnglishPipeline(english) || markNumbers) {
                for (step in buildEnglishBaseSteps(markNumbers)) {
                    english = step.applyEnglish(english)
                }
            }
            english = unmaskUrls(english, urls)
            if (markNumbers) {
                if (spelledWordsEnabled) {
                    english = NumberWordDetector.detectAndMark(
                        english, languageTag
                    )
                }
                english = markRemainingDigits(english)
            }
            return CleanupStep.apply(english)
        }
        if (!LanguageCode.isArabic(languageTag)) return normalized
        val urls = ArrayList<String>()
        var result = maskUrls(normalized, urls)
        if (requiresRegexPipeline(result) || markNumbers) {
            for (step in buildBaseSteps(markNumbers)) {
                result = step.apply(result)
            }
        }
        result = unmaskUrls(result, urls)
        if (markNumbers) {
            if (spelledWordsEnabled) {
                result = NumberWordDetector.detectAndMark(
                    result, languageTag
                )
            }
            result = markRemainingDigits(result)
        }
        return CleanupStep.apply(result)
    }

    /**
     * فحص سريع لكل الحروف: هل يحتوي النص أي محفِّز يستدعي مراحل regex الثقيلة؟
     * المحفِّزات هي: أي حرف/رقم لاتيني (أرقام/فواصل/رموز/حروف رومانية وعملات
     * ورسميات مثل USD/SAR)، رموز العملة (€¥₹…)، أي رمز حسابي/عام، وعلامات
     * عربية خاصة (٪، ﷼) — فإذا خلا النص منها (عربي خالص بلا أرقام) نتخطى
     * كل المراحل ونكتفي بالتنظيف، فيتسارع معالجة السنة/الرسائل/
     * الإشعارات العادية بشكل كبير.
     */
    private fun requiresRegexPipeline(text: String): Boolean {
        for (i in text.indices) {
            val code = text[i].code
            when {
                // أي حرف/رقم/علامة لاتينية (استثناء الفراغات كافةً —
                // مسافة/سطر/تبويب) — يشمل الأرقام والفواصل ورموز العمليات
                // و @ و # وحروف العملات والرومانية والرسمية، وعلامات مثل
                // ° × ÷ (كلها دون U+0600).
                code < 0x600 && !text[i].isWhitespace() -> return true
                // ٪ (عربي للمئة) — تُستبدل في SYMBOL_NAMES_GENERAL
                code == 0x66A -> return true
                // الفاصلة المنقوطة العربية (؛) — تُنطق اسمها بمستوى «الكل»
                code == 0x61B -> return true
                // الشرطتان الطويلتان (– و—) وعلامة النقاط (…) — تُنطق
                // أسماؤها بمستوى «الكل» (كلها فوق نطاق ASCII ولا يلتقطها
                // الشرط اللاتيني في HEAVY أصلاً)
                code in 0x2013..0x2014 || code == 0x2026 -> return true
                // رموز عملة خارج ASCII (€¥₹…)
                code in 0x20A0..0x20CF -> return true
                // رموز النظام الرياضية/المنطقية (≥≤≠≈∞√…)
                code in 0x2200..0x22FF -> return true
                // باي اليوناني (يُستبدل في SYMBOL_NAMES_GENERAL)
                code == 0x03C0 -> return true
                // ﷼ (ريال سعودي)
                code == 0xFDFC -> return true
            }
        }
        return false
    }

    /**
     * بوابة الإنجليزي السريعة: خطواتُ المسار الإنجليزي كلها لا تعمل إلا
     * بحضور رقمٍ أو رمز — فإذا كان النص حروفاً/فراغات/فاصلةً عليا فقط
     * (وهو الشائع في مقاطع الإنجليزية) تخطّينا الممرات الثقيلة.
     */
    private fun requiresEnglishPipeline(text: String): Boolean {
        for (c in text) {
            if (c.isLetter() || c == ' ' || c == '\'' || c == '’') {
                continue
            }
            return true
        }
        return false
    }

    /**
     * حجب الروابط مؤقتاً أثناء خطوات الإنجليزية: خطوة الترقيم تعتبر «/»
     * المعزولة كلمةً فتشوّه «https://…». تُستبدل الروابط بمحارف حجزٍ في
     * منطقة الاستخدام الخاص (U+E000+) — بلا أرقام فلا تلمسها خطوةُ الأرقام،
     * وبلا حروفٍ لا يمسّها نطقُ الترقيم — ثم تُستعاد كما كانت.
     */
    private fun maskUrls(text: String, store: MutableList<String>): String {
        val matcher = PATTERN_URL_EN.matcher(text)
        if (!matcher.find()) return text
        matcher.reset()
        val buffer = StringBuffer()
        while (matcher.find()) {
            store += matcher.group()
            val token = (URL_MASK_BASE + store.size - 1).toString()
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(token))
        }
        matcher.appendTail(buffer)
        return buffer.toString()
    }

    /**
     * استرجاع الروابط التي حجبتها [maskUrls] إلى مواضع محارف الحجز.
     *
     * وعند تفعيل مفتاح اختبار الروابط (ع٤١-ب7) تُستبدل كلُّ محجوزةٍ
     * بصيغتها المنطوقة المختصرة ([UrlSpeech.shorten]) — **وهذا بعد
     * القاموس** (يجري في [buildPreamble])؛ فلا يُمسّ ناتجُ القاموس
     * لأنه ليس محجوزاً بعلامة، وإنما تُبدَّل مواضعُ الروابط المحجوزة
     * وحدها (حمايةُ العلامات المحجوزة). المفتاحُ مطفأٌ افتراضياً فلا
     * يتغيّر السلوك القائم.
     */
    private fun unmaskUrls(text: String, store: List<String>): String {
        if (store.isEmpty()) return text
        val shorten = injectedSettings?.isUrlShorteningEnabled() ?: false
        val sb = StringBuilder(text.length + store.sumOf { it.length })
        for (ch in text) {
            val idx = ch - URL_MASK_BASE
            if (idx in store.indices) {
                val original = store[idx]
                val replacement = if (shorten) {
                    UrlSpeech.shorten(original)
                } else {
                    original
                }
                sb.append(replacement)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * توسيع الإيموجي ورموز المشاعر إلى أسمائها القابلة للنطق (عربي/إنجليزي
     * حسب languageTag). يشمل: كودات Unicode الموثّقة في EmojiNames، الإيموجي
     * النصيّ (☺), رموز المشاعر النصية (":)", ":) ", "<3"…)، والأعلام (رمزا
     * منطقة متجاوران). الإيموجي غير الموثّق يُنطق بالكلمة العامة الثابتة.
     * تُسقط تعديلات ألوان البشرة ومؤشرات الأشكال بصمت؛ و ZWJ يُحفظ
     * خارج تسلسلات الإيموجي (الكونجونكتات الهندية) ويُدمج داخلها، ويُدمج
     * الإيموجي المركّب (عائلة/مهنة) باسم أول مكوّن. عند تعطيل المفتاح لا
     * تُستدعى هذه الدالة (تُستخدم خطوة الإزالة بدلها).
     */
    private fun expandEmojis(text: String, languageTag: String): String {
        val arabic = LanguageCode.isArabic(languageTag)
        val fallback = if (arabic) EmojiNames.AR_FALLBACK
            else EmojiNames.EN_FALLBACK
        val base = EmojiNames.applyAsciiEmoticons(text, arabic)
        val sb = StringBuilder(base.length)
        var i = 0
        val len = base.length
        // تجميع الإيموجي المتكرر المتطابق المتلاصق (مثل 😂😂😂) في اسمٍ واحدٍ
        // مسبوقٍ بالعدد («ثلاثة وجه يضحك بدموع») بدل تكرار الاسم نفسه مرات —
        // فلا يُنطق 5-6 مرات متتالية مزعجة للمستمع. يُدمج المتجاور المتطابق
        // فقط، وأي حرفِ نصٍّ يفصلها.
        var groupIdent: String? = null
        var groupName = ""
        var groupCount = 0

        fun flushGroup() {
            if (groupIdent != null) {
                appendEmojiName(
                    sb,
                    if (groupCount > 1) {
                        emojiCountWord(groupCount, arabic) + " " + groupName
                    } else {
                        groupName
                    }
                )
                groupIdent = null
                groupName = ""
                groupCount = 0
            }
        }

        /** يضمّ إيموجيًّا (باسمه وهويته الكاملة) لمجموعة التكرار،
         *  أو يفتتح مجموعةً جديدة. */
        fun absorb(name: String, ident: String) {
            if (groupIdent == ident) {
                groupCount++
            } else {
                flushGroup()
                groupIdent = ident
                groupName = name
                groupCount = 1
            }
        }

        while (i < len) {
            val cp = base.codePointAt(i)
            val chars = Character.charCount(cp)
            val startOfUnit = i
            when {
                EmojiNames.isEmojiModifier(cp) -> {
                    // تعديلات منفصلة (ألوان بشرة/مؤشرات أشكال/Keycap/وسوم
                    // أعلام) تُسقط بصمت؛ ZWJ خارج التسلسل يُحفظ (نصوص هندية)
                    i += chars
                }
                EmojiNames.isRegionalIndicator(cp) -> {
                    val nextIdx = i + chars
                    if (nextIdx < len) {
                        val next = base.codePointAt(nextIdx)
                        if (EmojiNames.isRegionalIndicator(next)) {
                            val ident = base.substring(
                                startOfUnit,
                                nextIdx + Character.charCount(next)
                            )
                            absorb(
                                EmojiNames.flagReadingName(
                                    EmojiNames.buildCountryCode(cp, next),
                                    arabic
                                ),
                                ident
                            )
                            i = nextIdx + Character.charCount(next)
                            continue
                        }
                    }
                    // علم غير مكتمل (رمز واحد بلا قرين): نطق عام
                    absorb(
                        fallback,
                        base.substring(startOfUnit, startOfUnit + chars)
                    )
                    i += chars
                }
                EmojiNames.isEmojiBlockCp(cp) -> {
                    val name = if (arabic) EmojiNames.arName(cp)
                        else EmojiNames.enName(cp)
                    i += chars
                    // تجاوز بقية المجموعة: ألوان بشرة، مؤشرات أشكال، وعناصر
                    // ما بعد ZWJ (عائلة/مهنة) حتى لا تُنطق مقاطع متناثرة
                    var zwjSeen = false
                    while (i < len) {
                        val c2 = base.codePointAt(i)
                        val c2chars = Character.charCount(c2)
                        when {
                            c2 in 0x1F3FB..0x1F3FF ||
                                c2 in 0xFE0E..0xFE0F -> i += c2chars
                            c2 == 0x200D -> {
                                zwjSeen = true; i += c2chars
                            }
                            EmojiNames.isEmojiBlockCp(c2) && zwjSeen -> {
                                i += c2chars; zwjSeen = false
                            }
                            else -> break
                        }
                    }
                    absorb(name ?: fallback, base.substring(startOfUnit, i))
                }
                else -> {
                    flushGroup()
                    sb.append(base, i, i + chars)
                    i += chars
                }
            }
        }
        flushGroup()
        return Normalizer.normalize(sb.toString().trim(), Normalizer.Form.NFC)
    }

    /** عدد التكرار ككلمةٍ منطوقة بلغة تسمية الأسماء (ثلاثة/three). */
    private fun emojiCountWord(count: Int, arabic: Boolean): String =
        if (arabic) NumberWordsConverter.numberToWords(count)
        else NumberSpeech.toEnglishWords(count)

    /** يلحق اسم إيموجي يفصله عن جاره بمسافة من الجهتين: إن لصِق اسمُ
     *  الإيموجي بكلمةٍ تالية بلا مسافة («مرحباً😀مرحبا») كانت الكلمةُ
     *  تلتصق بالاسم — الناتج النهائي يمر عبر [CleanupStep] فيُضمّ
     *  المسافات المتكررة وتُزال الطرفية (مع [trim]). */
    private fun appendEmojiName(sb: StringBuilder, name: String) {
        if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') sb.append(' ')
        sb.append(name).append(' ')
    }

    /** تحويل رقم لكلمات عربية (يدعم حتى التريليونات، والكسور العشرية) —
     *  يفوّض لمحرك الأعداد المشترك في خط المعالجة. */
    fun numberToWords(number: Number): String =
        NumberWordsConverter.numberToWords(number)

    /**
     * إحاطة الأرقام الخام المتبقية (غير الموسومة سلفاً) بعلامات الحجز.
     */
    fun markRemainingDigits(text: String): String {
        val matcher = PATTERN_UNMARKED_DIGITS.matcher(text)
        if (!matcher.find()) return text
        matcher.reset()
        val sb = StringBuilder(text.length + 32)
        while (matcher.find()) {
            val marked = matcher.group(1)
            val digits = matcher.group(2)
            if (marked != null) {
                matcher.appendReplacement(
                    sb,
                    Matcher.quoteReplacement(marked)
                )
            } else if (digits != null) {
                val wrapped = "${EngineMarkers.NUMBER_MARK_START}$digits" +
                    "${EngineMarkers.NUMBER_MARK_END}"
                matcher.appendReplacement(
                    sb,
                    Matcher.quoteReplacement(wrapped)
                )
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun stripNumberMarkersIfUnmarked(
        text: String,
        markNumbers: Boolean
    ): String =
        if (!markNumbers && (text.contains(EngineMarkers.NUMBER_MARK_START) ||
            text.contains(EngineMarkers.NUMBER_MARK_END))) {
            text.replace(EngineMarkers.NUMBER_MARK_START, "")
                .replace(EngineMarkers.NUMBER_MARK_END, "")
        } else {
            text
        }

    companion object {
        private val COMMA_OR_AND_DIGITS = Regex(
            """(?<=[\d\u0660-\u0669\u06F0-\u06F9\uE031])""" +
                """(?:\s*،\s*|\s*و\s*|\s+and\s+|\s*,\s+|\s+,\s*)""" +
                """(?=[\d\u0660-\u0669\u06F0-\u06F9\uE030])""",
            RegexOption.IGNORE_CASE
        )
        private val COMMA_ATTACHED_ARABIC = Regex(
            """(?<=[\d\u0660-\u0669\u06F0-\u06F9\uE031])\s*،\s*|""" +
                """\s*،\s*(?=[\d\u0660-\u0669\u06F0-\u06F9\uE030])"""
        )

        /** إزالة كل الفواصل وواو العطف بين الأرقام عربي أو إنجليزي. */
        fun stripCommasBetweenDigits(input: String): String {
            if (input.indexOf(',') < 0 && input.indexOf('،') < 0 &&
                input.indexOf('و') < 0 &&
                !input.contains("and", ignoreCase = true)
            ) {
                return input
            }
            var res = input.replace(COMMA_OR_AND_DIGITS, " ")
            res = res.replace(COMMA_ATTACHED_ARABIC, " ")
            return res
        }

        /** نمط الروابط المحجوبة (نفس نمط [UrlStep]). */
        private val PATTERN_URL_EN = Pattern.compile(
            """(?i)\b(?:https?://|www\.)[^\s<>"']+|""" +
                """\b[a-zA-Z0-9_.-]+\.[a-zA-Z]{2,}(?::\d+)?/[^\s<>"']*"""
        )

        /**
         * نمط التقاط الأرقام الخام المتبقية دون وسمها مسبقاً بعلامات الحجز.
         */
        private val PATTERN_UNMARKED_DIGITS = Pattern.compile(
            "(${EngineMarkers.NUMBER_MARK_START}" +
                "[^${EngineMarkers.NUMBER_MARK_END}]*" +
                "${EngineMarkers.NUMBER_MARK_END})|" +
                "([0-9\u0660-\u0669\u06F0-\u06F9]+(?:[.,\u066B\u066C]" +
                "[0-9\u0660-\u0669\u06F0-\u06F9]+)*)"
        )

        /** أول محرف في منطقة الاستخدام الخاص يُستخدم لحجز الروابط. */
        private const val URL_MASK_BASE = '\uE000'

        /** نمط الفراغات الموحد المخبأ لتجنب تكرار الترجمة عند كل جملة. */
        private val PATTERN_WHITESPACE = Regex("""\s+""")
    }
}