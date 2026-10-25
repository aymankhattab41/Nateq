package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.TextProcessor
import com.aymankhattab.nateq.engine.pipeline.TimeStep
import com.aymankhattab.nateq.engine.pipeline.UnitStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 54 — الأمر 3 (جُمادى) والأمر 4 (الإنجليزية والدلالات).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OrdersRound54EngineTest {

    private lateinit var context: Context
    private lateinit var processor: TextProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        processor = TextProcessor(context)
    }

    // --- اختبارات الأمر 3: حماية جُمادى والأشهر الهجرية ---

    @Test
    fun testOrder3_hijriMonthJumadaVocalized() {
        val input = "اليوم، الأَحَد 13 جُمَادَى الأُولَى 1448"
        val out = processor.processSemantics(input, "ar")
        assertFalse(out.contains("جنيه"))
        assertFalse(out.contains("مساء"))
        assertFalse(out.contains("متر"))
        assertTrue(out.contains("جُمَادَى"))
    }

    @Test
    fun testOrder3_hijriMonthJumadaUnvocalized() {
        val input = "13 جمادى الأولى"
        val out = processor.processSemantics(input, "ar")
        assertFalse(out.contains("جنيه"))
        assertFalse(out.contains("مساء"))
        assertFalse(out.contains("متر"))
        assertTrue(out.contains("جمادى"))
    }

    @Test
    fun testOrder3_allHijriMonthsAfterDays() {
        val months = listOf(
            "مُحَرَّم", "صَفَر", "رَبِيع الأَوَّل", "رَبِيع الآخِر",
            "جُمَادَى الأُولَى", "جُمَادَى الآخِرَة", "رَجَب",
            "شَعْبَان", "رَمَضَان", "شَوَّال", "ذُو القَعْدَة",
            "ذُو الحِجَّة"
        )
        val days = listOf("1", "9", "13", "29")
        for (month in months) {
            for (day in days) {
                val input = "$day $month"
                val out = processor.processSemantics(input, "ar")
                assertFalse(
                    "Failed for $input: contains جنيه", out.contains("جنيه")
                )
                assertFalse(
                    "Failed for $input: contains مساء", out.contains("مساء")
                )
            }
        }
    }

    @Test
    fun testOrder3_realCurrencyAndUnitsPreserved() {
        val out13J = processor.processSemantics("13 ج", "ar")
        assertTrue(out13J.contains("جنيه"))

        val out50J = processor.processSemantics("50ج", "ar")
        assertTrue(out50J.contains("جنيه"))

        val out13Jm = processor.processSemantics("13جم", "ar")
        assertFalse(out13Jm.contains("جنيه"))

        val out5M = processor.processSemantics("5 م", "ar")
        assertTrue(out5M.contains("متر") || out5M.contains("أمتار"))

        val outTime5M = processor.processSemantics("الساعة 5 م", "ar")
        assertTrue(outTime5M.contains("مساء"))
    }

    // --- اختبارات الأمر 4: الإنجليزية والوقت والوحدات ---

    @Test
    fun testOrder4_timeStepEnglish() {
        val timeStep = TimeStep()
        val out515 = timeStep.applyEnglish("5:15 PM")
        assertEquals("five fifteen PM", out515)

        val out1715 = timeStep.applyEnglish("17:15")
        assertEquals("five fifteen PM", out1715)

        val out5Am = timeStep.applyEnglish("5:00 AM")
        assertEquals("five AM", out5Am)

        val out5PmSuffix = timeStep.applyEnglish("5 PM")
        assertEquals("five PM", out5PmSuffix)
    }

    @Test
    fun testOrder4_unitStepEnglish() {
        val out5Km = UnitStep.applyEnglish("5 km")
        assertEquals("5 kilometers", out5Km)

        val out1Km = UnitStep.applyEnglish("1 km")
        assertEquals("1 kilometer", out1Km)

        val out10Mb = UnitStep.applyEnglish("10 MB")
        assertEquals("10 megabytes", out10Mb)
    }

    @Test
    fun testOrder4_processSemanticsEnglish() {
        val input = "at 5:15 PM"
        val out = processor.processSemantics(input, "en")
        assertTrue(out.contains("five fifteen PM"))
    }
}
