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
    fun ringBuffer_keepsOnlyLastTenEntries() {
        for (i in 1..25) {
            RepeatSpellController.updateLastSpokenText("نص $i")
        }
        assertEquals(10, RepeatSpellController.size())
        assertEquals("نص 25", RepeatSpellController.getLastSpokenText())
        val history = RepeatSpellController.history()
        assertEquals("نص 25", history.first().text)
        assertEquals("نص 16", history.last().text)
    }

    @Test
    fun otpLikeDigits_areNotRecorded() {
        RepeatSpellController.updateLastSpokenText("1234")
        assertEquals(0, RepeatSpellController.size())
    }

    @Test
    fun otpFormattedSpacedDigits_areNotRecorded() {
        RepeatSpellController.updateLastSpokenText("1 2 3 4 5 6")
        assertEquals(0, RepeatSpellController.size())
        assertNull(RepeatSpellController.getLastSpokenText())
    }

    @Test
    fun otpArabicIndicDigits_areNotRecorded() {
        RepeatSpellController.updateLastSpokenText("١ ٢ ٣ ٤ ٥ ٦")
        assertEquals(0, RepeatSpellController.size())
        assertNull(RepeatSpellController.getLastSpokenText())
    }

    @Test
    fun otpWordRepresentation_isNotRecorded() {
        RepeatSpellController.updateLastSpokenText(
            "واحد اثنان ثلاثة أربعة خمسة ستة"
        )
        assertEquals(0, RepeatSpellController.size())
        assertNull(RepeatSpellController.getLastSpokenText())
        RepeatSpellController.updateLastSpokenText(
            "one two three four five six"
        )
        assertEquals(0, RepeatSpellController.size())
        assertNull(RepeatSpellController.getLastSpokenText())
    }

    @Test
    fun sensitiveAnnouncements_areNotRecorded() {
        RepeatSpellController.updateLastSpokenText(
            "نص سري للغاية", isSensitive = true
        )
        assertEquals(0, RepeatSpellController.size())
        assertNull(RepeatSpellController.getLastSpokenText())
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
