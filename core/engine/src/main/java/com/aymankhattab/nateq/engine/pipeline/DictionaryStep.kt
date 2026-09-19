package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.PronunciationDictionary

/** تطبيق القاموس الشخصي (أعلى أولوية في المعالجة) على النص — يمرّر وسم
 *  اللغة إلى القاموس ليدمج نطاقَه العام مع نطاقِ تلك اللغة (بند د.3.5). */
internal class DictionaryStep(
    private val dictionary: PronunciationDictionary
) : TextProcessingStep {

    override fun apply(input: String): String = dictionary.apply(input)

    override fun apply(input: String, languageTag: String?): String =
        dictionary.apply(input, languageTag)
}
