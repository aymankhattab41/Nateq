package com.aymankhattab.nateq.engine.pipeline

/**
 * خطوة واحدة في خط معالجة النصوص القابلة للنطق.
 *
 * كل خطوة تستقبل ناتج سابقتها وتُعيد نصاً جاهزاً للخطوة التالية (Chain of
 * Responsibility / Pipeline)، فيبقى ترتيب المعالجة الأصلي محفوظاً حرفياً عبر
 * قائمة مرتبة يملكها منسّق [TextProcessor]. لا تحمل الخطوات أي حالة عابرة،
 * فتبقى قابلة للاختبار المنفرد وإعادة الاستخدام.
 */
internal interface TextProcessingStep {
    /** يُطبّق الخطوة على النص ويرجع ناتجها الجاهز للخطوة التالية. */
    fun apply(input: String): String
}