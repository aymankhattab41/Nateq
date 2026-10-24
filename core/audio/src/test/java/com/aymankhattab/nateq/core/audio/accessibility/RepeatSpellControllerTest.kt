package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RepeatSpellControllerTest {

    @Before
    fun setUp() = RepeatSpellController.clear()

    @After
    fun tearDown() = RepeatSpellController.clear()

    @Test
    fun spellText_usesArabicLetterNames() {
        assertEquals(
            "نون ألف طاء قاف",
            RepeatSpellController.spellText("ناطق")
        )
    }

    @Test
    fun spellText_keepsSpacesAndNamesSymbols() {
        assertEquals(
            "كاف تاء ألف باء مسافة جيم دال ياء دال",
            RepeatSpellController.spellText("كتاب جديد")
        )
        assertEquals("آت هاش", RepeatSpellController.spellText("@#"))
        assertEquals("واحد اثنان", RepeatSpellController.spellText("12"))
    }

    @Test
    fun spellText_spellsEachCharacterByName() {
        assertEquals(
            "ألف باء",
            RepeatSpellController.spellText("اب")
        )
    }

    @Test
    fun updateLastSpokenText_updatesReference() {
        RepeatSpellController.updateLastSpokenText("تجربة النطق")
        assertEquals(
            "تجربة النطق",
            RepeatSpellController.getLastSpokenText()
        )
    }

    @Test
    fun ringBuffer_keepsOnlyLastTwentyEntries() {
        for (i in 1..25) {
            RepeatSpellController.updateLastSpokenText("نص $i")
        }
        assertEquals(20, RepeatSpellController.size())
        assertEquals("نص 25", RepeatSpellController.getLastSpokenText())
        val history = RepeatSpellController.history()
        assertEquals("نص 25", history.first().text)
        assertEquals("نص 6", history.last().text)
    }

    @Test
    fun otpLikeDigits_areNotRecorded() {
        RepeatSpellController.updateLastSpokenText("1234")
        assertEquals(0, RepeatSpellController.size())
    }

    @Test
    fun callAnnouncements_areNotRecorded() {
        RepeatSpellController.updateLastSpokenText("مكالمة", isCall = true)
        assertEquals(0, RepeatSpellController.size())
    }

    @Test
    fun blankText_isNotRecorded() {
        RepeatSpellController.updateLastSpokenText("   ")
        RepeatSpellController.updateLastSpokenText(null)
        assertEquals(0, RepeatSpellController.size())
    }

    @Test
    fun clear_emptiesHistory() {
        RepeatSpellController.updateLastSpokenText("شيء")
        RepeatSpellController.clear()
        assertEquals(0, RepeatSpellController.size())
        assertNull(RepeatSpellController.getLastSpokenText())
    }
}
