package com.aymankhattab.nateq.core.audio.accessibility

/**
 * جدول ربط إيماءات مفاتيح الصوت بالأفعال (ع٤١-ب1.2).
 * يُخزَّن نصاً في الإعدادات (a11y_shortcut_bindings) ويُقرأ عند كل حدث
 * مفتاح، فلا يحتاج تعديلُه إعادةَ بناء الخدمة.
 */
class ShortcutBindingTable private constructor(
    private val bindings: Map<ShortcutGesture, ShortcutAction>
) {

    /** الفعل المربوط بإيماءة (NONE إن لم تُربط). */
    fun actionFor(gesture: ShortcutGesture): ShortcutAction =
        bindings[gesture] ?: ShortcutAction.NONE

    /** هل الإيماءة مربوطةٌ بفعلٍ حقيقي (غير NONE)؟ */
    fun isBound(gesture: ShortcutGesture): Boolean =
        actionFor(gesture) != ShortcutAction.NONE

    /** نسخةٌ جديدة بالربط المطلوب بلا تغيير الأصلية. */
    fun with(
        gesture: ShortcutGesture,
        action: ShortcutAction
    ): ShortcutBindingTable {
        val next = LinkedHashMap(bindings)
        if (action == ShortcutAction.NONE) {
            next.remove(gesture)
        } else {
            next[gesture] = action
        }
        return ShortcutBindingTable(next)
    }

    /** تمثيلٌ نصيٌّ ثابتٌ (بترتيب التعداد) للتخزين في الإعدادات. */
    fun encode(): String = ShortcutGesture.entries
        .mapNotNull { gesture ->
            val action = actionFor(gesture)
            if (action == ShortcutAction.NONE) {
                null
            } else {
                "${gesture.name}=${action.name}"
            }
        }
        .joinToString(",")

    companion object {

        /** الافتراضي الآمن: الضغط المطوّل فقط، بلا تغيير الصوت. */
        fun defaults(): ShortcutBindingTable = ShortcutBindingTable(
            mapOf(
                ShortcutGesture.VOL_UP_LONG to ShortcutAction.READ_SCREEN,
                ShortcutGesture.VOL_DOWN_LONG to
                    ShortcutAction.READ_CLIPBOARD,
                ShortcutGesture.BOTH_HOLD to ShortcutAction.TOGGLE_PAUSE
            )
        )

        /**
         * فكّ الترميز: null = الافتراضي؛ وسلسلةٌ فارغة = لا ربطات
         * (اختار المستخدم إلغاءها كلها).
         */
        fun decode(raw: String?): ShortcutBindingTable {
            if (raw == null) return defaults()
            val next = LinkedHashMap<ShortcutGesture, ShortcutAction>()
            for (token in raw.split(",")) {
                val parts = token.split("=")
                if (parts.size != 2) continue
                val gesture =
                    ShortcutGesture.fromName(parts[0]) ?: continue
                val action = ShortcutAction.fromName(parts[1])
                if (action == ShortcutAction.NONE) continue
                next[gesture] = action
            }
            return ShortcutBindingTable(next)
        }
    }
}
