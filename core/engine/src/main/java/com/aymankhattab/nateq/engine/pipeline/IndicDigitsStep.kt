package com.aymankhattab.nateq.engine.pipeline

/** تطبيع الأرقام الشرقية والفارسية والهندية إلى غربية — يفوّض إلى util المشترك. */
internal object IndicDigitsStep : TextProcessingStep {

    override fun apply(input: String): String =
        com.aymankhattab.nateq.util.LocaleUtils.normalizeIndicDigits(input)
}