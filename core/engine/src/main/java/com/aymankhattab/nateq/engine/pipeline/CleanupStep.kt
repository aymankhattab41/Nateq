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

    override fun apply(input: String): String {
        return PATTERN_SPACE_BEFORE.matcher(
            PATTERN_MULTI_SPACE.matcher(input).replaceAll(" ")
        ).replaceAll("$1").trim()
    }
}