package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.engine.PronunciationDictionary

/** تطبيق القاموس الشخصي (أعلى أولوية في المعالجة) على النص. */
internal class DictionaryStep(
    private val dictionary: PronunciationDictionary
) : TextProcessingStep {

    override fun apply(input: String): String = dictionary.apply(input)
}