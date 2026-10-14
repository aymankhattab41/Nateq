package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * حارس ع٢٩-10 بند 3: مطابقة اسم الصوت صارمة — بادئة `vocalizer_`
 * تُرفع عن الطرفين، وأي اسم آخر لا يطابق **لا يسقط على أول قائمة**
 * (السقوط كان يخفي الخطأ ويقرأ صوتاً غير المطلوب).
 */
class VocalizerVoiceMatchTest {

    private fun match(engineName: String?, requested: String): Boolean =
        VocalizerHostSession.matchesVoiceName(engineName, requested)

    @Test
    fun matchesExactNameWithAndWithoutPrefix() {
        assertTrue(match("Laila", "Laila"))
        assertTrue(match("Laila", "vocalizer_Laila"))
        assertTrue(match("vocalizer_Laila", "vocalizer_Laila"))
        assertTrue(match("vocalizer_Laila", "Laila"))
    }

    @Test
    fun matchesIgnoreCase() {
        assertTrue(match("LAILA", "laila"))
        assertTrue(match("laila", "vocalizer_LAILA"))
    }

    @Test
    fun rejectsUnknownOrBlankNames() {
        assertFalse(match("Laila", "Maged"))
        assertFalse(match("Laila", "vocalizer_Maged"))
        assertFalse(match(null, "Laila"))
        assertFalse(match("", "Laila"))
        assertFalse(match("   ", "Laila"))
    }

    @Test
    fun rejectsPartialNames() {
        assertFalse(match("Laila Extra", "Laila"))
        assertFalse(match("Lai", "Laila"))
    }

    @Test
    fun matchesCatalogItemIds() {
        assertTrue(match("Laila", "vocalizer-ara-sau-laila-compact"))
        assertTrue(match("Laila", "vocalizer-ara-sau-laila-high"))
        assertTrue(match("Maged", "vocalizer-ara-sau-maged-compact"))
        assertFalse(match("Laila", "vocalizer-ara-sau-maged-compact"))
    }
}
