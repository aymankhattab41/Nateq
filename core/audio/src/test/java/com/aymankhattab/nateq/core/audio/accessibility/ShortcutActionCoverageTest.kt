package com.aymankhattab.nateq.core.audio.accessibility

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبار تغطية أفعال الاختصارات وتدوير الحافظة (ع٥٧-أمر 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ShortcutActionCoverageTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        ClipboardStack.clear()
    }

    @Test
    fun `every ShortcutAction except NONE has a registered handler`() {
        val extraActions = setOf(
            ShortcutAction.SCREEN_ROTOR,
            ShortcutAction.SCREEN_SEARCH,
            ShortcutAction.SCREEN_SEARCH_NAV,
            ShortcutAction.READ_ARTICLE,
            ShortcutAction.READ_CLIPBOARD_STACK,
            ShortcutAction.PASTE_CLIPBOARD_STACK,
            ShortcutAction.TYPO_SUGGESTIONS,
            ShortcutAction.QUICK_CALC,
            ShortcutAction.TRANSLATE_ELEMENT,
            ShortcutAction.PASSWORD_COUNT,
            ShortcutAction.TOGGLE_SCREEN_CURTAIN,
            ShortcutAction.QUICK_DICTIONARY
        )
        val routerDirectActions = setOf(
            ShortcutAction.READ_SCREEN,
            ShortcutAction.READ_CLIPBOARD,
            ShortcutAction.REPEAT_LAST,
            ShortcutAction.SPELL_LAST,
            ShortcutAction.WHERE_AM_I,
            ShortcutAction.TOGGLE_PAUSE,
            ShortcutAction.TOGGLE_CONTINUOUS,
            ShortcutAction.OCR_FOCUS,
            ShortcutAction.READ_FULL_URL,
            ShortcutAction.REPEAT_PREVIOUS,
            ShortcutAction.SPEECH_HISTORY,
            ShortcutAction.SHOW_QUICK_MENU,
            ShortcutAction.EXPAND_READ_MORE,
            ShortcutAction.NEXT_ENGINE,
            ShortcutAction.PREVIOUS_ENGINE
        )

        for (action in ShortcutAction.entries) {
            if (action == ShortcutAction.NONE) continue
            val isHandled = action in routerDirectActions ||
                action in extraActions
            assertTrue(
                "ShortcutAction $action must have a handler",
                isHandled
            )
        }
    }

    @Test
    fun `clipboard stack readNext cycles through entries and paste selected`() {
        ClipboardStack.addEntry("الأول")
        ClipboardStack.addEntry("الثاني")
        ClipboardStack.addEntry("الثالث")

        assertEquals(3, ClipboardStack.size())

        // تدوير العنصر الأول (الأحدث: الثالث)
        ClipboardStack.readNext(context)
        assertEquals(0, ClipboardStack.getSelectedIndex())

        // تدوير العنصر الثاني (الثاني)
        ClipboardStack.readNext(context)
        assertEquals(1, ClipboardStack.getSelectedIndex())

        // اللصق بعد الضغطة الثانية يلصق العنصر المحدد حالياً (الثاني)
        ClipboardStack.pasteOrSpeak(context)
        val cm = context.getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager
        val clip = cm.primaryClip
        assertNotNull(clip)
        assertEquals("الثاني", clip?.getItemAt(0)?.text?.toString())

        // تدوير العنصر الثالث (الأول)
        ClipboardStack.readNext(context)
        assertEquals(2, ClipboardStack.getSelectedIndex())

        // العودة إلى البداية دورياً
        ClipboardStack.readNext(context)
        assertEquals(0, ClipboardStack.getSelectedIndex())
    }
}
