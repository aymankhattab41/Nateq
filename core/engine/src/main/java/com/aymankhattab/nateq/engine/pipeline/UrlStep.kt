package com.aymankhattab.nateq.engine.pipeline

/**
 * معالجة الروابط: تُترك الروابط لتُقرأ كما هي كاملةً دون أي تدخل
 * أو اختصار بناءً على تفضيل المستخدم.
 */
internal object UrlStep : TextProcessingStep {

    override fun apply(input: String): String = input

    override fun applyEnglish(input: String): String = input
}