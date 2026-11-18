package com.aymankhattab.nateq.core.audio.engine

/**
 * دور المقطع في خطة النطق الموحدة:
 * نص عادي، أو رقم، أو اسم إيموجي (بند ع٤٢-9)، أو حوار/سرد
 * (قارئ متعدد الأصوات).
 */
enum class SpeechRole {
    TEXT,
    NUMBER,
    EMOJI,
    /** مقطع حوار (بين علامات اقتباس/شرطات حوار). */
    DIALOGUE,
    /** مقطع سرد/وصف خارج الحوار. */
    NARRATION
}
