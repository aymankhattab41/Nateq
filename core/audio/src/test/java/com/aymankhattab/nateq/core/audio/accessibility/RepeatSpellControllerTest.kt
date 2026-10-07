package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatSpellControllerTest {

    @Test
    fun `spellText breaks words into letters and spaces`() {
        assertEquals("ن ا ط ق", RepeatSpellController.spellText("ناطق"))
        assertEquals(
            "ك ت ا ب مسافة ج د ي د",
            RepeatSpellController.spellText("كتاب جديد")
        )
    }

    @Test
    fun `updateLastSpokenText updates reference`() {
        RepeatSpellController.updateLastSpokenText("تجربة النطق")
        assertEquals(
            "تجربة النطق",
            RepeatSpellController.getLastSpokenText()
        )
    }
}
