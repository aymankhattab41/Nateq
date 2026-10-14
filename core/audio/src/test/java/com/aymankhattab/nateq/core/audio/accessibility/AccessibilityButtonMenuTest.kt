package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ع٤١-ب2: قائمة زر إمكانية الوصول تحتوي كل الأفعال المطلوبة ولكلٍّ وسمٌ
 * غير فارغ (يُقرأ بـTalkBack).
 */
class AccessibilityButtonMenuTest {

    private val required = listOf(
        ShortcutAction.READ_SCREEN,
        ShortcutAction.WHERE_AM_I,
        ShortcutAction.REPEAT_LAST,
        ShortcutAction.SPELL_LAST,
        ShortcutAction.TOGGLE_PAUSE,
        ShortcutAction.READ_CLIPBOARD,
        ShortcutAction.TOGGLE_CONTINUOUS,
        ShortcutAction.OCR_FOCUS
    )

    @Test
    fun menuContainsEveryRequiredAction() {
        val actions = A11yQuickMenu.menuActions()
        for (action in required) {
            assertTrue("missing $action", action in actions)
        }
    }

    @Test
    fun everyMenuActionHasNonBlankLabel() {
        for (action in A11yQuickMenu.menuActions()) {
            assertTrue(
                "blank label for $action",
                A11yQuickMenu.labelFor(action).isNotBlank()
            )
        }
    }
}
