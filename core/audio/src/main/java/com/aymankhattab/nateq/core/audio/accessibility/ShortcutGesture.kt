package com.aymankhattab.nateq.core.audio.accessibility

/**
 * إيماءات مفاتيح الصوت القابلة للربط (ع٤١-ب1).
 * - الضغط المطوّل: حدثٌ يصدره النظام عند الاستمرار بالضغط.
 * - الضغط المزدوج/الثلاثي: نقراتٌ متتابعة خلال نافذة زمنية قصيرة.
 * - BOTH_HOLD: استمرارُ ضغط المفتاحين معاً (يُكتشف عبر مؤقّت الطوارئ
 *   في NateqAccessibilityService).
 */
enum class ShortcutGesture {
    VOL_UP_LONG,
    VOL_DOWN_LONG,
    BOTH_HOLD,
    VOL_UP_DOUBLE,
    VOL_DOWN_DOUBLE,
    VOL_UP_TRIPLE,
    VOL_DOWN_TRIPLE;

    companion object {
        /** استرجاع إيماءة بالاسم، أو null إن لم تُعرف. */
        fun fromName(name: String?): ShortcutGesture? =
            entries.firstOrNull { it.name == name }
    }
}
