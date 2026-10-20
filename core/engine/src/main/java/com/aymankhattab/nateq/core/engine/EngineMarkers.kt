package com.aymankhattab.nateq.core.engine

/**
 * علامات Unicode الخاصة غير المرئية (PUA) المستخدمة كحراس داخليين:
 * - \uE020: حارس التنوين الزمني (مساءً/صباحاً/ظهراً...) لمنع تجريده.
 * - \uE030 و \uE031: وسم مقاطع الأرقام المنطوقة (أرقاماً أو حروفاً).
 */
object EngineMarkers {
    const val TANWEEN_MARKER_CHAR = '\uE020'
    const val TANWEEN_MARKER = "\uE020"

    const val NUMBER_MARK_START_CHAR = '\uE030'
    const val NUMBER_MARK_START = "\uE030"

    const val NUMBER_MARK_END_CHAR = '\uE031'
    const val NUMBER_MARK_END = "\uE031"
}
