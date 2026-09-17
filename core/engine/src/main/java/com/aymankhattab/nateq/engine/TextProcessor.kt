package com.aymankhattab.nateq.engine

import android.content.Context
import com.aymankhattab.nateq.core.engine.PunctuationLevels
import com.aymankhattab.nateq.core.engine.SynthesisConfig
import com.aymankhattab.nateq.engine.pipeline.AcronymStep
import com.aymankhattab.nateq.engine.pipeline.CleanupStep
import com.aymankhattab.nateq.engine.pipeline.CurrencyStep
import com.aymankhattab.nateq.engine.pipeline.DateStep
import com.aymankhattab.nateq.engine.pipeline.DictionaryStep
import com.aymankhattab.nateq.engine.pipeline.EmojiStripStep
import com.aymankhattab.nateq.engine.pipeline.IndicDigitsStep
import com.aymankhattab.nateq.engine.pipeline.NumberStep
import com.aymankhattab.nateq.engine.pipeline.NumberWordsConverter
import com.aymankhattab.nateq.engine.pipeline.PhoneNumberStep
import com.aymankhattab.nateq.engine.pipeline.PunctuationStep
import com.aymankhattab.nateq.engine.pipeline.RomanNumeralStep
import com.aymankhattab.nateq.engine.pipeline.SymbolStep
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
     *  محلياً — قراءة لحظية لتفضيل التاريخ الهجري لا أكثر. */
    private val injectedSettings: SynthesisConfig? = null,
    /** القاموس المحقون عبر Hilt إن وُجد (يمرره NateqTtsService) — وإلا يُبنى
     *  محلياً للاختبارات؛ مثيل موحَّد مع نسخة الواجهة ورصد لحظي لقرصه. */
    private val injectedDict: PronunciationDictionary? = null
) {

    private val pronunciationDict =
        injectedDict ?: PronunciationDictionary(context)

    /**
     * هل نطق أسماء الإيموجي مفعّل؟ بلا حقنة Settings
     * (الاختبارات) يُفترض مفعّل.
     */
    private val emojiEnabled: Boolean
        get() = injectedSettings?.isEmojiPronunciationEnabled() ?: true

    /**
     * هل التهجئة الذكية (نطق الحرف المفرد بأسمائه وحركاته) مفعّلة؟
     * بلا حقنة Settings تُفترض معطّلة.
     */
    private val smartSpellingEnabled: Boolean
        get() = injectedSettings?.isSmartSpellingEnabled() ?: false

    /** خطوة نطق علامات الترقيم — تتبع مستوى «البعض/الكل» قراءةً لحظية. */
    private val punctuationStep = PunctuationStep {
        injectedSettings?.getPunctuationLevel() ?: PunctuationLevels.SOME
    }

    // خطوات التمهيد: تُنفَّذ قبل بوابة المسار السريع
    // (تطبيع/تشكيل/إيموجي/قاموس).
    // «إزالة الإيموجي» شرطية: تعمل فقط عند تعطيل نطقها (إلا تُعيد النص كما هو).
    private val preamble: List<TextProcessingStep> = listOf(
        IndicDigitsStep,
        TashkeelStripStep,
        EmojiStripStep { emojiEnabled },
        DictionaryStep(pronunciationDict)
    )

    // الخطوات الدلالية المبكرة: تُطبَّق على النص الكامل قبل تقسيم اللغة
    // (عبر processSemantics) حتى لا ينفصل رمزُ العملة/الوحدة (حروف لاتينية
    // مثل «USD») إلى مقطعٍ إنجليزي مستقل — فينقطع «1500 USD» إلى مبلغٍ عربي
    // ورمزٍ إنجليزي. ثم يكررها المسار الثقيل على المقطع العربي (ناتجُها
    // كلماتٌ عربية بلا أرقام فلا يطابقها نمطٌ مجدداً — سلوك مطابق).
    // **بند 2.7:** تطبيع الأرقام الشرقية/الفارسية/الهندية أولُها: بدونها
    // كانت processSemantics تُشاهد «١٥٠٠ USD» بلا تطبيع فتنشطر للمقاطع،
    // وأنماط العملات/الوحدات/الأوقات لا تعترف بأرقامٍ شرقية (أنماطها \d
    // غربية) — الخطوة مطابقة للهوية بعد مسار التمهيد فلا ضرر من تكرارها.
    private val baseSteps: List<TextProcessingStep> = listOf(
        IndicDigitsStep,
        UrlStep,
        DateStep(injectedSettings),
        TimeStep,
        CurrencyStep,
        UnitStep,
        AcronymStep
    )

    // الخطوات الثقيلة (تنطبق فقط إن فشل المسار السريع) — ترتيبها مُطابق تماماً
    // لترتيب معالجة النص الأصلي: روابط → تواريخ → أوقات → عملات → وحدات →
    // رومانية → هواتف → رموز → ترقيم → أرقام. تُعالَج الروابط أولاً حمايةً لها
    // من أي تشويه تلحقه خطوة لاحقة (تاريخ/وقت/عملة داخل الرابط مثل
    // 2026-03-09)، ويأتي نطق الترقيم بعد الحماية فترى الروابط/التواريخ/
    // الهواتف منقّاةً ولا يمس أجزاءها.
    private val heavySteps: List<TextProcessingStep> = baseSteps + listOf(
        RomanNumeralStep,
        PhoneNumberStep,
        SymbolStep,
        punctuationStep,
        NumberStep
    )

    // المسار الإنجليزي (اللغة الثانية): نفس بنية الخطوات بمفردات إنجليزية.
    // يُستثنى القاموس العربي والتشكيل (خاصّان بالعربية)، والوحدات/الأوقات/
    // المختصرات/الرومانية (تُنتج كلمات عربية محضة). الروابط تُحجب مؤقتاً
    // كي لا تشوّهها خطوة الترقيم.
    private val englishPreamble: List<TextProcessingStep> = listOf(
        IndicDigitsStep,
        EmojiStripStep { emojiEnabled }
    )

    // الدلالات المبكرة للإنجليزية: تطبيع الأرقام ثم التواريخ/العملات، فلا
    // ينفصل رمزُ العملة («USD») عن مبلغه عند تقسيم اللغة.
    private val englishBaseSteps: List<TextProcessingStep> = listOf(
        IndicDigitsStep,
        DateStep(injectedSettings),
        CurrencyStep
    )

    // ثقيل الإنجليزي: يواصل بعد الدلالات المبكرة بالهاتف فالرموز فالترقيم
    // ثم الأرقام.
    private val englishHeavySteps: List<TextProcessingStep> =
        englishBaseSteps + listOf(
            PhoneNumberStep,
            SymbolStep,
            punctuationStep,
            NumberStep
        )

    /**
     * معالجة نص كامل وتحويله لصيغة نطق طبيعية.
     * @param languageTag كود اللغة (مثلاً "ar"، "en"، "ar-EG")
     *                    — العربية لها مسارها، والإنجليزية مسارٌ موازٍ
     *                      بمفرداتها، وبقية اللغات تُعاد كما هي.
     */
    fun process(
        text: String,
        languageTag: String = LanguageCode.AR.tag
    ): String {
        if (text.isBlank()) return text

        // توحيد الترميز إلى NFC عند المدخل: نصٌ مفكوك الترميز (حرفٌ منفصل
        // عن تشكيله/شدّته أو إيموجي مفكك المكونات) يُطوى إلى صورته المركّبة
        // قبل أي خطوة — فتعمل التشكيل/الأرقام/الإيموجي على صيغة متطابقة
        // بلا ازدواج بين شكلٍ مركّبٍ وشكلٍ مفكوكٍ يأتي من أي مصدر خارجي.
        var result = Normalizer.normalize(text, Normalizer.Form.NFC)

        // التهجئة الذكية: حرف مفرد (عربي بتشكيله أو لاتيني) يُنطق باسمه
        // كاملاً («بَ» ← «باء مفتوحة»، «A» ← «Capital Alpha») قبل أي
        // تحويل — يقودها TalkBack عند التنقل الحرفي بأحرفٍ منفردة.
        if (smartSpellingEnabled) {
            SmartSpeller.spell(result, languageTag)?.let { return it }
        }

        // نطق أسماء الإيموجي (بدل حذفها) قبل مسار العربية ليغطي الإنجليزية
        // واللغات الأخرى أيضاً — الناتج لا يُمرَّر لأي تحويل لاحق خارج العربية.
        val expanded =
            if (emojiEnabled) expandEmojis(result, languageTag) else null

        // الإنجليزية: مسار موازٍ بمفرداتها (لا تحويل أرقامٍ إنجليزية إلى
        // كلماتٍ عربية) — الأرقام/التواريخ/العملات/الهواتف/الرموز/الترقيم
        // تُنطق إنجليزية، مع حجب الروابط كي لا تشوّهها خطوةُ الترقيم.
        if (LanguageCode.isEnglish(languageTag)) {
            var out = expanded ?: result
            for (step in englishPreamble) out = step.applyEnglish(out)
            val urls = ArrayList<String>()
            out = maskUrls(out, urls)
            if (requiresEnglishPipeline(out)) {
                for (step in englishHeavySteps) out = step.applyEnglish(out)
            }
            out = unmaskUrls(out, urls)
            return CleanupStep.apply(out)
        }

        // اللغات الأخرى (fr/de/es…) تُعاد كما هي بعد توسيع الإيموجي فقط.
        if (!LanguageCode.isArabic(languageTag)) {
            if (expanded != null) return CleanupStep.apply(expanded)
            return result
        }

        // text قد يحوي إيموجي عُرضت أسماؤها (expanded) أو تُحذف لاحقاً
        if (expanded != null) result = expanded
        for (step in preamble) result = step.apply(result)

        // المسار السريع (Fast-path): إن لم يحتوِ النص على أي محفِّز لأرقام
        // الرموز/الصيغ (أرقام، فواصل، رموز عملة، حروف رومانية...) — أي نص
        // عربي صافٍ بلا أرقام — نتخطى كل مراحل regex الثقيلة (التواريخ/
        // الأوقات/العملات/الروابط/الرومانية/الهواتف/الأرقام/الرموز) ونذهب
        // مباشرةً لتنظيف المسافات. يوفّر تريليونات المطابقات على كل إعلان.
        if (!requiresRegexPipeline(result)) return CleanupStep.apply(result)

        for (step in heavySteps) result = step.apply(result)
        return CleanupStep.apply(result)
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
     */
    fun processSemantics(
        text: String,
        languageTag: String = LanguageCode.AR.tag
    ): String {
        if (text.isBlank()) return text
        // توحيد NFC عند المدخل مطابقاً لـ[process] — يبقى الإخراج مطابقاً
        // لنمط التقسيم المعدَّ مسبقاً على صيغة موحَّدة.
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (LanguageCode.isEnglish(languageTag)) {
            if (!requiresEnglishPipeline(normalized)) {
                return CleanupStep.apply(normalized)
            }
            var english = normalized
            for (step in englishBaseSteps) {
                english = step.applyEnglish(english)
            }
            return english
        }
        if (!LanguageCode.isArabic(languageTag)) return normalized
        if (!requiresRegexPipeline(normalized)) {
            return CleanupStep.apply(normalized)
        }
        var result = normalized
        for (step in baseSteps) result = step.apply(result)
        return result
    }

    /**
     * فحص سريع لكل الحروف: هل يحتوي النص أي محفِّز يستدعي مراحل regex الثقيلة؟
     * المحفِّزات هي: أي حرف/رقم لاتيني (أرقام/فواصل/رموز/حروف رومانية وعملات
     * ورسميات مثل USD/SAR)، رموز العملة (€¥₹…)، أي رمز حسابي/عام، وعلامات عربية
     * خاصة (٪، ﷼) — فإذا خلا النص منها (عربي خالص بلا أرقام) نتخطى كل المراحل
     * ونكتفي بالتنظيف، فيتسارع معالجة السنة/الرسائل/
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

    /** استرجاع الروابط التي حجبتها [maskUrls] إلى مواضع محارف الحجز. */
    private fun unmaskUrls(text: String, store: List<String>): String {
        if (store.isEmpty()) return text
        var out = text
        for (i in store.indices) {
            out = out.replace((URL_MASK_BASE + i).toString(), store[i])
        }
        return out
    }

    /**
     * توسيع الإيموجي ورموز المشاعر إلى أسمائها القابلة للنطق (عربي/إنجليزي
     * حسب languageTag). يشمل: كودات Unicode الموثّقة في EmojiNames، الإيموجي
     * النصيّ (☺), رموز المشاعر النصية (":)", ":) ", "<3"…)، والأعلام (رمزا
     * منطقة متجاوران). الإيموجي غير الموثّق يُنطق بالكلمة العامة الثابتة.
     * تُسقط تعديلات ألوان البشرة ومؤشرات الأشكال و ZWJ بصمت، ويُدمج
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
        while (i < len) {
            val cp = base.codePointAt(i)
            val chars = Character.charCount(cp)
            when {
                EmojiNames.isEmojiModifier(cp) -> {
                    // تعديلات منفصلة (ZWJ/ألوان بشرة/مؤشر أشكال) تُسقط بصمت
                    i += chars
                }
                EmojiNames.isRegionalIndicator(cp) -> {
                    val nextIdx = i + chars
                    if (nextIdx < len) {
                        val next = base.codePointAt(nextIdx)
                        if (EmojiNames.isRegionalIndicator(next)) {
                            val code = EmojiNames.buildCountryCode(cp, next)
                            appendEmojiName(
                                sb,
                                EmojiNames.flagReadingName(code, arabic)
                            )
                            i = nextIdx + Character.charCount(next)
                            continue
                        }
                    }
                    // علم غير مكتمل (رمز واحد بلا قرين): نطق عام
                    appendEmojiName(sb, fallback)
                    i += chars
                }
                EmojiNames.isEmojiBlockCp(cp) -> {
                    val name = if (arabic) EmojiNames.arName(cp)
                        else EmojiNames.enName(cp)
                    appendEmojiName(sb, name ?: fallback)
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
                }
                else -> {
                    sb.append(base, i, i + chars)
                    i += chars
                }
            }
        }
        return Normalizer.normalize(sb.toString().trim(), Normalizer.Form.NFC)
    }

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

    private companion object {
        /** نمط الروابط المحجوبة في المسار الإنجليزي (نفس نمط [UrlStep]). */
        private val PATTERN_URL_EN = Pattern.compile(
            """(?i)\b(?:https?://|www\.)[^\s<>"']+"""
        )

        /** أول محرف في منطقة الاستخدام الخاص يُستخدم لحجز الروابط. */
        private const val URL_MASK_BASE = '\uE000'
    }
}