package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * حارس «لا نطق باسم صوتٍ غير منزّل»: كان المزوّد المدمج يستبدل أي اسمٍ
 * غير معروف في كتالوج النماذج بـ«Laila»/«Samantha» فيفشل النطق صامتاً
 * إن لم يكن الاسم الثابت منزّلاً مع أن للمحرك أصواتاً منزّلة صالحة.
 */
class VocalizerVoiceSelectionTest {

    private val laila = InstalledVocalizerVoice("Laila", "ar")
    private val maged = InstalledVocalizerVoice("Maged", "ar")
    private val ava = InstalledVocalizerVoice("Ava", "en")

    @Test
    fun keepsRequestedNameWhenInstalled() {
        assertEquals(
            "Maged",
            chooseVocalizerVoiceName(
                requestedName = "Maged",
                requestedLanguage = "ar",
                installed = listOf(laila, maged, ava)
            )
        )
    }

    @Test
    fun matchesInstalledNameIgnoringCase() {
        assertEquals(
            "Maged",
            chooseVocalizerVoiceName(
                requestedName = "maged",
                requestedLanguage = "ar",
                installed = listOf(laila, maged)
            )
        )
    }

    @Test
    fun unknownNameFallsBackToInstalledVoiceOfSameLanguage() {
        // «ar-EG» اسم صوت اللورد لا اسم صوت Vocalizer: لا يُطلب من
        // المضيف مباشرةً (كان يسقط على Laila حتى لو لم تكن منزّلة).
        assertEquals(
            "Maged",
            chooseVocalizerVoiceName(
                requestedName = "ar-EG",
                requestedLanguage = "ar",
                installed = listOf(ava, maged)
            )
        )
    }

    @Test
    fun unknownLanguageFallsBackToFirstInstalledVoice() {
        assertEquals(
            "Ava",
            chooseVocalizerVoiceName(
                requestedName = "ar-EG",
                requestedLanguage = "ar",
                installed = listOf(ava)
            )
        )
    }

    @Test
    fun emptyInstalledListKeepsRequestedName() {
        assertEquals(
            "Samantha",
            chooseVocalizerVoiceName(
                requestedName = "Samantha",
                requestedLanguage = "en",
                installed = emptyList()
            )
        )
    }

    @Test
    fun regionalLanguageMatchesPrimarySubtag() {
        assertEquals(
            "Ava",
            chooseVocalizerVoiceName(
                requestedName = "en-US",
                requestedLanguage = "en-US",
                installed = listOf(maged, ava)
            )
        )
    }
}
