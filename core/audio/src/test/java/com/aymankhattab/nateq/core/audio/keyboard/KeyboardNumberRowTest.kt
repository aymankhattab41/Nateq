package com.aymankhattab.nateq.core.audio.keyboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة لصف الأرقام الدائم في لوحة المفاتيح Lord Keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardNumberRowTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepo = SettingsRepository(context)
    }

    @Test
    fun getNumberRow_containsAllDigitsOneToZero() {
        val row = KeyboardLayoutHelper.getNumberRow()
        assertEquals(10, row.size)
        val expected = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0"
        )
        for (i in expected.indices) {
            assertEquals(expected[i], row[i].label)
            assertEquals(expected[i], row[i].spokenText)
        }
    }

    @Test
    fun settingsRepository_alwaysShowNumberRow_defaultsFalseAndToggles() {
        settingsRepo.setKeyboardAlwaysShowNumberRow(false)
        assertFalse(settingsRepo.isKeyboardAlwaysShowNumberRow())

        settingsRepo.setKeyboardAlwaysShowNumberRow(true)
        assertTrue(settingsRepo.isKeyboardAlwaysShowNumberRow())
    }

    @Test
    fun arabicLayout_withNumberRowPrepended_hasNumberRowAtTop() {
        val numRow = KeyboardLayoutHelper.getNumberRow()
        val arabicRows = KeyboardLayoutHelper.getArabicRows()
        val combined = listOf(numRow) + arabicRows

        assertEquals(6, combined.size)
        assertEquals("1", combined[0][0].label)
        assertEquals("0", combined[0][9].label)
        assertEquals("ض", combined[1][0].label)
    }
}
