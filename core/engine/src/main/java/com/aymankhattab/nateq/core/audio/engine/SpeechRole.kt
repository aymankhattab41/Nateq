package com.aymankhattab.nateq.core.audio.engine

/**
 * دور المقطع في خطة النطق الموحدة:
 * نص عادي، أو رقم، أو اسم إيموجي (بند ع٤٢-9).
 */
enum class SpeechRole {
    TEXT,
    NUMBER,
    EMOJI
}
