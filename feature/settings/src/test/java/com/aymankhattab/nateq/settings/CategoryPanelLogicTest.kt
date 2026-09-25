package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات لوحة فئات الأصوات بعد إعادة التصميم (منطق نقي بلا أندرويد):
 *  محتوى سبnner الفئات العلوي وترتيبه، خريطة فئة المتصل الفرعية حسب اللغة،
 *  وقاعدة «المحرك المخصص» للفئات. */
class CategoryPanelLogicTest {

    @Test
    fun categoryEntries_haveExpectedOrderAndKeys() {
        assertEquals(
            listOf(
                SettingsRepository.VOICE_CATEGORY_DEFAULT,
                SettingsRepository.VOICE_CATEGORY_TIME,
                SettingsRepository.VOICE_CATEGORY_NUMBERS,
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS,
                SettingsRepository.VOICE_CATEGORY_EMOJI,
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
            ),
            CategoryPanelEntry.entries.map { it.categoryKey }
        )
    }

    @Test
    fun callerEntry_isTheOnlyCallerLabeled() {
        val callerByName = CategoryPanelEntry.entries
            .firstOrNull { it.isCaller }
        assertEquals(CategoryPanelEntry.CALLER, callerByName)
        val nonCaller = CategoryPanelEntry.entries
            .filter { it != CategoryPanelEntry.CALLER }
        assertTrue(nonCaller.isNotEmpty())
        assertTrue(nonCaller.none { it.isCaller })
    }

    @Test
    fun callerSubCategory_mapsArabicAndEnglish() {
        assertEquals(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
            callerSubCategory("ar")
        )
        assertEquals(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN,
            callerSubCategory("en")
        )
        // امتدادات BCP-47 تُحسب على لغتها بعيداً عن الحروف الكبيرة.
        assertEquals(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN,
            callerSubCategory("en-US")
        )
        assertEquals(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
            callerSubCategory("ar_EG")
        )
    }

    @Test
    fun onlyStaticDefaultHasNoDedicatedEngine() {
        assertFalse(
            categoryHasDedicatedEngine(
                SettingsRepository.VOICE_CATEGORY_DEFAULT
            )
        )
        assertTrue(
            categoryHasDedicatedEngine(
                SettingsRepository.VOICE_CATEGORY_TIME
            )
        )
        assertTrue(
            categoryHasDedicatedEngine(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        )
        assertTrue(
            categoryHasDedicatedEngine(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
        )
        assertTrue(
            categoryHasDedicatedEngine(
                SettingsRepository.VOICE_CATEGORY_EMOJI
            )
        )
        // فئات المتصل الفرعية (عربي/إنجليزي) ليست «الفئة الافتراضية»
        // فيحتفظ كل جانب بمحركه الخاص.
        assertTrue(
            categoryHasDedicatedEngine(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
            )
        )
        assertTrue(
            categoryHasDedicatedEngine(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
            )
        )
    }
}