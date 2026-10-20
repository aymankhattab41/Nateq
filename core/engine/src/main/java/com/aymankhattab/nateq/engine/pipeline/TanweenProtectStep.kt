package com.aymankhattab.nateq.engine.pipeline

import com.aymankhattab.nateq.core.engine.EngineMarkers

/**
 * خطوة حماية التنوين الزمني للألفاظ الظرفية (مساءً، صباحاً، ظهراً، عصراً،
 * ليلاً، فجراً، نهاراً) قبل خطوة تجريد التشكيل [TashkeelStripStep].
 * تضيف العلامة الخاصة \uE020 بعد الكلمة المشكولة دون تغيير نصها.
 */
internal object TanweenProtectStep : TextProcessingStep {

    private val TANWEEN_PATTERN = Regex(
        "(?<![\\p{L}\\p{M}])(" +
            "م[\\u064E-\\u0652]?س[\\u064E-\\u0652]?ا[\\u064E-\\u0652]?ء" +
            "(?:ً|اً|ًا)|" +
            "ص[\\u064E-\\u0652]?ب[\\u064E-\\u0652]?ا[\\u064E-\\u0652]?ح" +
            "(?:اً|ًا)|" +
            "ظ[\\u064E-\\u0652]?ه[\\u064E-\\u0652]?ر" +
            "(?:اً|ًا)|" +
            "ع[\\u064E-\\u0652]?ص[\\u064E-\\u0652]?ر" +
            "(?:اً|ًا)|" +
            "ل[\\u064E-\\u0652]?ي[\\u064E-\\u0652]?ل" +
            "(?:اً|ًا)|" +
            "ف[\\u064E-\\u0652]?ج[\\u064E-\\u0652]?ر" +
            "(?:اً|ًا)|" +
            "ن[\\u064E-\\u0652]?ه[\\u064E-\\u0652]?ا[\\u064E-\\u0652]?ر" +
            "(?:اً|ًا)" +
            ")(?![\\p{L}\\p{M}])"
    )

    override fun apply(input: String): String {
        if (input.isEmpty()) return input
        return TANWEEN_PATTERN.replace(input) { matchResult ->
            "${matchResult.groupValues[1]}${EngineMarkers.TANWEEN_MARKER}"
        }
    }
}
