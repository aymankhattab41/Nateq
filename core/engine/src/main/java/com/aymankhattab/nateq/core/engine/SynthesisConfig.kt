package com.aymankhattab.nateq.core.engine

/**
 * [TextProcessingPreferences] — نطاق مفاتيح مراحل معالجة النصوص.
 */
interface TextProcessingPreferences {

    /** تحويل الأوقات والتواريخ إلى نطق طبيعي. الافتراضي: true. */
    fun isTimeConversionEnabled(): Boolean = true

    /** تحويل مبالغ العملات إلى نطق طبيعي. الافتراضي: true. */
    fun isCurrencyConversionEnabled(): Boolean = true

    /** تحويل وحدات القياس إلى نطق طبيعي. الافتراضي: true. */
    fun isUnitConversionEnabled(): Boolean = true

    /** تحويل الرموز إلى نطق طبيعي. الافتراضي: true. */
    fun isSymbolConversionEnabled(): Boolean = true

    /** تحويل أرقام الهواتف إلى نطق طبيعي. الافتراضي: true. */
    fun isPhoneConversionEnabled(): Boolean = true

    /** تطبيع الحروف العربية. الافتراضي: true. */
    fun isArabicNormalizationEnabled(): Boolean = true
}

/**
 * إعدادات النُطق التي يحتاجها خط إنتاج معالجة النص (pipeline) — يفصلها عن
 * مستودع الإعدادات مباشرةً لتفكيك الوحدات
 * (البند 4). ينفّذها SettingsRepository.
 */
interface SynthesisConfig : TextProcessingPreferences {

    /** تفعيل نطق أسماء الإيموجي قبل إزالتها من النص. */
    fun isEmojiPronunciationEnabled(): Boolean

    /** مستوى نطق علامات الترقيم والرموز: 0 لا شيء، 1 البعض، 2 الكل. */
    fun getPunctuationLevel(): Int

    /**
     * الحفاظ على تشكيل النصوص العربية المُرسلة للمحرك: التجريد الداخلي
     * (للمطابقة مع القواميس والأنماط) يبقى قائماً، لكن الكلمات الأصلية
     * غير المتحوّلة تُعاد بتشكيلها إلى المحرك — فتنطق الحركات/الشدة
     * بوضوح لدى المحركات العربية التي تفهم التشكيل (بند 1.7).
     */
    fun isTashkeelPreserved(): Boolean

    /** لغة نطق الأرقام: "ar" أو "en". */
    fun getNumberReadingLanguage(): String = "ar"

    /** طريقة نطق الأرقام (1..8): 1 مفردة، 2 زوجي، 3..8 ثلاثي..ثماني. */
    fun getNumberReadingMode(): Int = 1
}