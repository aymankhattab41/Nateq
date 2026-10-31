package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.common.VisibleEngines
import com.aymankhattab.nateq.core.common.VisibleLanguages
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** اختبارات لوحة فئات الأصوات بعد إعادة التصميم (منطق نقي بلا أندرويد):
 *  محتوى سبnner الفئات العلوي وترتيبه، خريطة فئة المتصل الفرعية حسب اللغة،
 *  وقاعدة «المحرك المخصص» للفئات. */
class CategoryPanelLogicTest {

    @Before
    fun setUp() {
        VisibleEngines.resetForTest()
        VisibleLanguages.resetForTest()
    }

    @After
    fun tearDown() {
        VisibleEngines.resetForTest()
        VisibleLanguages.resetForTest()
    }

    @Test
    fun categoryEntries_haveExpectedOrderAndKeys() {
        assertEquals(
            listOf(
                SettingsRepository.VOICE_CATEGORY_TIME,
                SettingsRepository.VOICE_CATEGORY_BATTERY,
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
        assertTrue(
            categoryHasDedicatedEngine(
                SettingsRepository.VOICE_CATEGORY_BATTERY
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

    @Test
    fun visibleEngines_filtersEventAndCallerEngines() {
        val eng1 = EnginePicker.InstalledEngine("pkg.google", "Google")
        val eng2 = EnginePicker.InstalledEngine("pkg.vocalizer", "Vocalizer")
        val eng3 = EnginePicker.InstalledEngine("pkg.hidden", "Hidden")
        val all = listOf(eng1, eng2, eng3)

        VisibleEngines.setFilter(true, setOf("pkg.google", "pkg.vocalizer"))

        val filtered = all.filter { VisibleEngines.isVisible(it.packageName) }
        assertEquals(listOf(eng1, eng2), filtered)
        assertFalse(filtered.any { it.packageName == "pkg.hidden" })

        // محرك محفوظ محجوب يعود إلى 0 (أول محرك مرئي)
        val hiddenIdx = engineIndexFor(filtered, "pkg.hidden")
        assertEquals(0, hiddenIdx)

        val visibleIdx = engineIndexFor(filtered, "pkg.vocalizer")
        assertEquals(1, visibleIdx)
    }

    @Test
    fun visibleLanguages_filtersEventLanguages() {
        VisibleLanguages.setFilter(true, setOf("ar", "en"))

        val allLangs = listOf("ar", "en", "fr", "de")
        val visible = VisibleLanguages.filter(allLangs)

        assertEquals(listOf("ar", "en"), visible)
        assertFalse(VisibleLanguages.isVisible("fr"))
        assertFalse(VisibleLanguages.isVisible("de"))
        assertTrue(VisibleLanguages.isVisible("ar-EG"))
        assertTrue(VisibleLanguages.isVisible("en-US"))
    }
}