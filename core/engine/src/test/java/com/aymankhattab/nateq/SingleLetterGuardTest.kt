package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.engine.SingleLetterGuard
import com.aymankhattab.nateq.core.engine.SynthesisConfig
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SingleLetterGuardTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dict = PronunciationDictionary(context)
    }

    @After
    fun tearDown() {
        dict.clear()
    }

    @Test
    fun isSingleLetterUtterance_detectsIsolatedLetters() {
        assertTrue(SingleLetterGuard.isSingleLetterUtterance("م"))
        assertTrue(SingleLetterGuard.isSingleLetterUtterance("م."))
        assertTrue(SingleLetterGuard.isSingleLetterUtterance(" أ "))
        assertTrue(SingleLetterGuard.isSingleLetterUtterance("k"))
        assertTrue(SingleLetterGuard.isSingleLetterUtterance("لا"))
        assertTrue(SingleLetterGuard.isSingleLetterUtterance("لأ"))

        assertFalse(SingleLetterGuard.isSingleLetterUtterance("5"))
        assertFalse(SingleLetterGuard.isSingleLetterUtterance("%"))
        assertFalse(SingleLetterGuard.isSingleLetterUtterance("5 م"))
        assertFalse(SingleLetterGuard.isSingleLetterUtterance("متر"))
    }

    @Test
    fun getLetterName_mapsArabicAndEnglishCorrectly() {
        assertEquals("ميم", SingleLetterGuard.getLetterName("م"))
        assertEquals("ألف همزة فوق", SingleLetterGuard.getLetterName("أ"))
        assertEquals("ألف همزة تحت", SingleLetterGuard.getLetterName("إ"))
        assertEquals("ألف مدّة", SingleLetterGuard.getLetterName("آ"))
        assertEquals("همزة", SingleLetterGuard.getLetterName("ء"))
        assertEquals("لام ألف", SingleLetterGuard.getLetterName("لا"))
        assertEquals("kay", SingleLetterGuard.getLetterName("k"))
        assertEquals("kay", SingleLetterGuard.getLetterName("K"))
    }

    @Test
    fun resolve_singleLetterMode_nameVsDictionaryVsAsWritten() {
        dict.addEntry("م", "متر")

        fun simulateReaderPath(
            text: String,
            lang: String,
            mode: String
        ): String {
            val resolved = SingleLetterGuard.resolve(text, mode)
            return resolved ?: dict.apply(text, lang)
        }

        // في وضع اسم الحرف: تُنطق ميم
        assertEquals("ميم", simulateReaderPath("م", "ar", "name"))
        assertEquals(
            "ألف همزة فوق",
            simulateReaderPath("أ", "ar", "name")
        )
        assertEquals("kay", simulateReaderPath("k", "en", "name"))
        assertEquals("ميم", simulateReaderPath("م.", "ar", "name"))

        // 5 م ليست حرفاً مفرداً فيُطبَّق القاموس
        assertEquals("5 متر", simulateReaderPath("5 م", "ar", "name"))

        // في وضع القاموس: تُنطق متر
        assertEquals("متر", simulateReaderPath("م", "ar", "dictionary"))

        // في وضع كما هو مكتوب: تُترك م بلا قاموس
        assertEquals("م", simulateReaderPath("م", "ar", "as_written"))
    }
}
