package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس عقد جدول ربط اختصارات المفاتيح (ع٤١-ب1.2).
 */
class ShortcutBindingTableTest {

    @Test
    fun defaults_bindLongPressOnlyAsSafeDefault() {
        val table = ShortcutBindingTable.defaults()
        assertEquals(
            ShortcutAction.READ_SCREEN,
            table.actionFor(ShortcutGesture.VOL_UP_LONG)
        )
        assertEquals(
            ShortcutAction.READ_CLIPBOARD,
            table.actionFor(ShortcutGesture.VOL_DOWN_LONG)
        )
        assertEquals(
            ShortcutAction.TOGGLE_PAUSE,
            table.actionFor(ShortcutGesture.BOTH_HOLD)
        )
        assertEquals(
            ShortcutAction.NONE,
            table.actionFor(ShortcutGesture.VOL_UP_DOUBLE)
        )
        assertEquals(
            ShortcutAction.NONE,
            table.actionFor(ShortcutGesture.VOL_UP_TRIPLE)
        )
    }

    @Test
    fun decodeNullFallsBackToDefaults() {
        val table = ShortcutBindingTable.decode(null)
        assertEquals(
            ShortcutAction.READ_SCREEN,
            table.actionFor(ShortcutGesture.VOL_UP_LONG)
        )
    }

    @Test
    fun decodeEmptyMeansNothingBound() {
        val table = ShortcutBindingTable.decode("")
        for (gesture in ShortcutGesture.entries) {
            assertEquals(
                ShortcutAction.NONE,
                table.actionFor(gesture)
            )
        }
    }

    @Test
    fun encodeDecodeRoundTrip_preservesBindings() {
        val table = ShortcutBindingTable.defaults()
            .with(
                ShortcutGesture.VOL_UP_DOUBLE,
                ShortcutAction.READ_SCREEN
            )
            .with(ShortcutGesture.BOTH_HOLD, ShortcutAction.NONE)
        val decoded = ShortcutBindingTable.decode(table.encode())
        assertEquals(
            ShortcutAction.READ_SCREEN,
            decoded.actionFor(ShortcutGesture.VOL_UP_LONG)
        )
        assertEquals(
            ShortcutAction.READ_SCREEN,
            decoded.actionFor(ShortcutGesture.VOL_UP_DOUBLE)
        )
        assertEquals(
            ShortcutAction.NONE,
            decoded.actionFor(ShortcutGesture.BOTH_HOLD)
        )
    }

    @Test
    fun decodeIgnoresUnknownTokensAndGestures() {
        val decoded = ShortcutBindingTable.decode(
            "BOGUS=READ_SCREEN," +
                "VOL_UP_LONG=NOT_AN_ACTION," +
                "VOL_DOWN_LONG=READ_CLIPBOARD"
        )
        assertEquals(
            ShortcutAction.NONE,
            decoded.actionFor(ShortcutGesture.VOL_UP_LONG)
        )
        assertEquals(
            ShortcutAction.READ_CLIPBOARD,
            decoded.actionFor(ShortcutGesture.VOL_DOWN_LONG)
        )
    }

    @Test
    fun isBoundReflectsRealActionOnly() {
        val table = ShortcutBindingTable.defaults()
        assertTrue(table.isBound(ShortcutGesture.VOL_UP_LONG))
        assertTrue(!table.isBound(ShortcutGesture.VOL_DOWN_TRIPLE))
    }
}
