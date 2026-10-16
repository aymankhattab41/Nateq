package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة لمدير الاتصال السريع (SpeedDialManager) وذاكرة 1-10000.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SpeedDialManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SpeedDialManager.clearAll(context)
    }

    @Test
    fun saveAndGetSlot_worksCorrectly() {
        assertTrue(
            SpeedDialManager.saveSlot(
                context,
                1,
                "أيمن",
                "01012938221"
            )
        )

        val entry = SpeedDialManager.getSlot(context, 1)
        assertNotNull(entry)
        assertEquals(1, entry?.slot)
        assertEquals("أيمن", entry?.name)
        assertEquals("01012938221", entry?.phoneNumber)
    }

    @Test
    fun multiSlotSupport_allowsDifferentNumbersForSamePerson() {
        // شخص واحد بأرقام متعددة في مواقع مختلفة (16، 17، 19)
        SpeedDialManager.saveSlot(context, 16, "أيمن", "01012938221")
        SpeedDialManager.saveSlot(context, 17, "أيمن", "01234567890")
        SpeedDialManager.saveSlot(context, 19, "أيمن", "01111111111")

        val e16 = SpeedDialManager.getSlot(context, 16)
        val e17 = SpeedDialManager.getSlot(context, 17)
        val e19 = SpeedDialManager.getSlot(context, 19)

        assertEquals("01012938221", e16?.phoneNumber)
        assertEquals("01234567890", e17?.phoneNumber)
        assertEquals("01111111111", e19?.phoneNumber)
    }

    @Test
    fun slotBounds_validatesRange1To10000() {
        assertFalse(SpeedDialManager.isValidSlot(0))
        assertFalse(SpeedDialManager.isValidSlot(10001))
        assertFalse(SpeedDialManager.isValidSlot(-5))

        assertTrue(SpeedDialManager.isValidSlot(1))
        assertTrue(SpeedDialManager.isValidSlot(5000))
        assertTrue(SpeedDialManager.isValidSlot(10000))

        assertFalse(SpeedDialManager.saveSlot(context, 0, "علي", "123"))
        assertFalse(SpeedDialManager.saveSlot(context, 10001, "علي", "123"))
        assertNull(SpeedDialManager.getSlot(context, 0))
    }

    @Test
    fun deleteSlot_removesOnlyTargetSlot() {
        SpeedDialManager.saveSlot(context, 1, "أحمد", "01000000001")
        SpeedDialManager.saveSlot(context, 2, "محمود", "01000000002")

        assertTrue(SpeedDialManager.deleteSlot(context, 1))
        assertNull(SpeedDialManager.getSlot(context, 1))

        val remaining = SpeedDialManager.getSlot(context, 2)
        assertNotNull(remaining)
        assertEquals("محمود", remaining?.name)
    }

    @Test
    fun getAllAssignedSlots_returnsSortedList() {
        SpeedDialManager.saveSlot(context, 19, "ج", "333")
        SpeedDialManager.saveSlot(context, 1, "أ", "111")
        SpeedDialManager.saveSlot(context, 16, "ب", "222")

        val all = SpeedDialManager.getAllAssignedSlots(context)
        assertEquals(3, all.size)
        assertEquals(1, all[0].slot)
        assertEquals(16, all[1].slot)
        assertEquals(19, all[2].slot)
    }
}
