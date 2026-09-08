package com.aymankhattab.nateq.engine

import android.content.Context
import com.aymankhattab.nateq.engine.pipeline.CleanupStep
import com.aymankhattab.nateq.engine.pipeline.CurrencyStep
import com.aymankhattab.nateq.engine.pipeline.DateStep
import com.aymankhattab.nateq.engine.pipeline.DictionaryStep
import com.aymankhattab.nateq.engine.pipeline.EmojiStripStep
import com.aymankhattab.nateq.engine.pipeline.IndicDigitsStep
import com.aymankhattab.nateq.engine.pipeline.NumberStep
import com.aymankhattab.nateq.engine.pipeline.NumberWordsConverter
import com.aymankhattab.nateq.engine.pipeline.PhoneNumberStep
import com.aymankhattab.nateq.engine.pipeline.RomanNumeralStep
import com.aymankhattab.nateq.engine.pipeline.SymbolStep
import com.aymankhattab.nateq.engine.pipeline.TashkeelStripStep
import com.aymankhattab.nateq.engine.pipeline.TextProcessingStep
import com.aymankhattab.nateq.engine.pipeline.TimeStep
import com.aymankhattab.nateq.engine.pipeline.UnitStep
import com.aymankhattab.nateq.engine.pipeline.UrlStep
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import java.text.Normalizer

/**
 * معالج النصوص الذكي — يحول النصوص الخام إلى نصوص قابلة للنطق طبيعياً.
 *
 * منسّق لخط معالجة نمطي [TextProcessingStep]: يمثل الترقيم/الترتيب الأصلي
 * للتحويلات (تواريخ، أوقات، عملات، وحدات، روابط، أرقام رومانية، هواتف،
 * رموز، أرقام) في خطوات منفصلة تسهل اختبارها وترتيبها، مع بقاء الواجهة
 * العامة متطابقة ([process] و [numberToWords]).
 */
class TextProcessor(
    private val context: Context,
    /** المرجع المحقون عبر Hilt إن وُجد (يمرره NateqTtsService)، وإلا يُبنى
     *  محلياً — قراءة لحظية لتفضيل التاريخ الهجري لا أكثر. */
    private val injectedSettings: SettingsRepository? = null,
    /** القاموس المحقون عبر Hilt إن وُجد (يمرره NateqTtsService) — وإلا يُبنى
     *  محلياً للاختبارات؛ مثيل موحَّد مع نسخة الواجهة ورصد لحظي لقرصه. */
    private val injectedDict: PronunciationDictionary? = null
) {

    private val pronunciationDict = injectedDict ?: PronunciationDictionary(context)

    /** هل نطق أسماء الإيموجي مفعّل؟ بلا حقنة Settings (الاختبارات) يُفترض مفعّل. */
    private val emojiEnabled: Boolean
        get() = injectedSettings?.isEmojiPronunciationEnabled() ?: true

    // خطوات التمهيد: تُنفَّذ قبل بوابة المسار السريع (تطبيع/تشكيل/إيموجي/قاموس).
    // «إزالة الإيموجي» شرطية: تعمل فقط عند تعطيل نطقها (إلا تُعيد النص كما هو).
    private val preamble: List<TextProcessingStep> = listOf(
        IndicDigitsStep,
        TashkeelStripStep,
        EmojiStripStep { emojiEnabled },
        DictionaryStep(pronunciationDict)
    )

    // الخطوات الثقيلة (تنطبق فقط إن فشل المسار السريع) — ترتيبها مُطابق تماماً
    // لترتيب معالجة النص الأصلي: تواريخ → أوقات → عملات → وحدات → روابط →
    // رومانية → هواتف → رموز → أرقام.
    private val heavySteps: List<TextProcessingStep> = listOf(
        DateStep(context, injectedSettings),
        TimeStep,
        CurrencyStep,
        UnitStep,
        UrlStep,
        RomanNumeralStep,
        PhoneNumberStep,
        SymbolStep,
        NumberStep
    )

    /**
     * معالجة نص كامل وتحويله لصيغة نطق طبيعية.
     * @param languageTag كود اللغة (مثلاً "ar"، "en"، "ar-EG")
     *                    — المعالجة مخصصة للغة العربية فقط؛ اللغات الأخرى تُعاد كما هي.
     */
    fun process(text: String, languageTag: String = LanguageCode.AR.tag): String {
        if (text.isBlank()) return text

        // نطق أسماء الإيموجي (بدل حذفها) قبل مسار العربية ليغطي الإنجليزية
        // واللغات الأخرى أيضاً — الناتج لا يُمرَّر لأي تحويل لاحق خارج العربية.
        val expanded = if (emojiEnabled) expandEmojis(text, languageTag) else null

        // المعالجة مخصصة للعربية فقط؛ الإنجليزية واللغات الأخرى تُعاد كما هي
        // بعد توسيع الإيموجي فقط (لا يجوز تحويل أرقام إنجليزية إلى كلمات عربية)
        if (!LanguageCode.isArabic(languageTag)) {
            return if (expanded != null) CleanupStep.apply(expanded) else text
        }

        // text قد يحوي إيموجي عُرضت أسماؤها (expanded) أو تُحذف لاحقاً
        var result = expanded ?: text
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
     * فحص سريع لكل الحروف: هل يحتوي النص أي محفِّز يستدعي مراحل regex الثقيلة؟
     * المحفِّزات هي: أي حرف/رقم لاتيني (أرقام/فواصل/رموز/حروف رومانية وعملات
     * ورسميات مثل USD/SAR)، رموز العملة (€¥₹…)، أي رمز حسابي/عام، وعلامات عربية
     * خاصة (٪، ﷼) — فإذا خلا النص منها (عربي خالص بلا أرقام) نتخطى كل المراحل
     * ونكتفي بالتنظيف، فيتسارع معالجة السنة/الرسائل/الإشعارات العادية بشكل كبير.
     */
    private fun requiresRegexPipeline(text: String): Boolean {
        for (i in text.indices) {
            val code = text[i].code
            when {
                // أي حرف/رقم/علامة لاتينية (استثناء الفراغ) — يشمل الأرقام
                // والفواصل ورموز العمليات و @ و # وحروف العملات والرومانية
                // والرسمية، وعلامات مثل ° × ÷ (كلها دون U+0600).
                code < 0x600 && code != 0x20 -> return true
                // ٪ (عربي للمئة) — تُستبدل في SYMBOL_NAMES_GENERAL
                code == 0x66A -> return true
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
        val fallback = if (arabic) EmojiNames.AR_FALLBACK else EmojiNames.EN_FALLBACK
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
                            sb.append(' ').append(EmojiNames.flagReadingName(code, arabic))
                            i = nextIdx + Character.charCount(next)
                            continue
                        }
                    }
                    // علم غير مكتمل (رمز واحد بلا قرين): نطق عام
                    sb.append(' ').append(fallback)
                    i += chars
                }
                EmojiNames.isEmojiBlockCp(cp) -> {
                    val name = if (arabic) EmojiNames.arName(cp) else EmojiNames.enName(cp)
                    sb.append(' ').append(name ?: fallback)
                    i += chars
                    // تجاوز بقية المجموعة: ألوان بشرة، مؤشرات أشكال، وعناصر
                    // ما بعد ZWJ (عائلة/مهنة) حتى لا تُنطق مقاطع متناثرة
                    var zwjSeen = false
                    while (i < len) {
                        val c2 = base.codePointAt(i)
                        val c2chars = Character.charCount(c2)
                        when {
                            c2 in 0x1F3FB..0x1F3FF || c2 in 0xFE0E..0xFE0F -> i += c2chars
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

    /** تحويل رقم لكلمات عربية (يدعم حتى التريليونات، والكسور العشرية) —
     *  يفوّض لمحرك الأعداد المشترك في خط المعالجة. */
    fun numberToWords(number: Number): String = NumberWordsConverter.numberToWords(number)
}