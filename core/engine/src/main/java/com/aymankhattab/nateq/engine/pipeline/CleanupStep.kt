package com.aymankhattab.nateq.engine.pipeline

import java.util.regex.Pattern

/**
 * تنظيف المسافات الزائدة
 * (ضم المتكررة وإزالة ما قبل علامات الترقيم
 * والطرفية).
 */
internal object CleanupStep : TextProcessingStep {

    private val PATTERN_MULTI_SPACE = Pattern.compile("""\s+""")
    private val PATTERN_SPACE_BEFORE = Pattern.compile("""\s+([،؛.!?])""")
    // علامات التحكم الاتجاهي (LRM/RLM/LRE/RLE/LRI…) قد تصل مجتزأةً من
    // إشعارات/نصوص خارجية — تُستبدل بمسافة (لجاماً بين الكلمات لا يلصقها)
    // ثم تضغطها خطوة المسافات المتتالية فيغدو النص سليماً للمحرك.
    private val PATTERN_BIDI_CONTROL =
        Pattern.compile("""[\u200E\u200F\u202A-\u202E\u2066-\u2069]""")

    override fun apply(input: String): String {
        val stripped = PATTERN_BIDI_CONTROL.matcher(input).replaceAll(" ")
        return PATTERN_SPACE_BEFORE.matcher(
            PATTERN_MULTI_SPACE.matcher(stripped).replaceAll(" ")
        ).replaceAll("$1").trim()
    }
}