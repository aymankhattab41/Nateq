package com.aymankhattab.nateq.core.audio.accessibility

/**
 * الأفعال القابلة للربط بإيماءات مفاتيح الصوت (ع٤١-ب1.1).
 * NONE تعني أن الإيماءة غير مربوطةٍ بفعلٍ فلا تُستهلك.
 */
enum class ShortcutAction {
    READ_SCREEN,
    READ_CLIPBOARD,
    REPEAT_LAST,
    SPELL_LAST,
    WHERE_AM_I,
    TOGGLE_CONTINUOUS,
    TOGGLE_PAUSE,
    OCR_FOCUS,
    SHOW_QUICK_MENU,
    READ_FULL_URL,
    REPEAT_PREVIOUS,
    SPEECH_HISTORY,
    EXPAND_READ_MORE,
    NEXT_ENGINE,
    PREVIOUS_ENGINE,
    SCREEN_ROTOR,
    SCREEN_SEARCH,
    SCREEN_SEARCH_NAV,
    READ_ARTICLE,
    READ_CLIPBOARD_STACK,
    PASTE_CLIPBOARD_STACK,
    TYPO_SUGGESTIONS,
    QUICK_CALC,
    TRANSLATE_ELEMENT,
    PASSWORD_COUNT,
    TOGGLE_SCREEN_CURTAIN,
    QUICK_DICTIONARY,
    NONE;

    companion object {
        /** استرجاع فعل بالاسم، أو NONE إن لم يُعرف. */
        fun fromName(name: String?): ShortcutAction =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}
