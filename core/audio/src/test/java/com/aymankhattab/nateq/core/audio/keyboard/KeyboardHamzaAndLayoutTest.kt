package com.aymankhattab.nateq.core.audio.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardHamzaAndLayoutTest {

    @Test
    fun arabicLayout_hasFiveRows() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        assertEquals(5, rows.size)
    }

    @Test
    fun rowThree_containsAllSixHamzasInOrder() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        val row3 = rows[2]
        val expected = listOf("أ", "إ", "آ", "ؤ", "ئ", "ء")
        val labels = row3.map { it.label }
        assertEquals(expected, labels)
    }

    @Test
    fun allHamzasHaveDistinctSpokenTexts() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        val allKeys = rows.flatten()
        val hamzaChars = listOf("أ", "إ", "آ", "ؤ", "ئ", "ء", "ا")
        val hamzaKeys = allKeys.filter { it.label in hamzaChars }

        val spokenTexts = hamzaKeys.map { it.spokenText }
        val uniqueSpokenTexts = spokenTexts.toSet()
        assertEquals(
            "Each hamza and alif must have a distinct spoken text",
            spokenTexts.size,
            uniqueSpokenTexts.size
        )
    }

    @Test
    fun hamzasPhoneticsMatchSpecifications() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        val allKeys = rows.flatten().associateBy { it.label }

        assertEquals("أحمد", allKeys["أ"]?.phonetic)
        assertEquals("إبراهيم", allKeys["إ"]?.phonetic)
        assertEquals("آدم", allKeys["آ"]?.phonetic)
        assertEquals("مؤمن", allKeys["ؤ"]?.phonetic)
        assertEquals("بئر", allKeys["ئ"]?.phonetic)
        assertEquals("سماء", allKeys["ء"]?.phonetic)
        assertEquals("ألف - أسد", allKeys["ا"]?.phonetic)
    }

    @Test
    fun functionalRows_containRequiredActionKeys() {
        val rows = KeyboardLayoutHelper.getArabicRows()
        val row4 = rows[3]
        val row5 = rows[4]
        val row4Types = row4.map { it.type }
        val row5Types = row5.map { it.type }

        assertTrue(
            row4Types.contains(KeyboardLayoutHelper.KeyType.ACTION_BACKSPACE)
        )
        assertTrue(
            row5Types.contains(KeyboardLayoutHelper.KeyType.ACTION_SPACE)
        )
        assertTrue(
            row5Types.contains(KeyboardLayoutHelper.KeyType.ACTION_ENTER)
        )
        assertTrue(
            row5Types.contains(KeyboardLayoutHelper.KeyType.ACTION_SYMBOLS)
        )
        assertTrue(
            row5Types.contains(KeyboardLayoutHelper.KeyType.ACTION_SWITCH_LANG)
        )
    }
}
