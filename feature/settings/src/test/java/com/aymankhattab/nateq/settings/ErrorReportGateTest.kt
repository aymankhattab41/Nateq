package com.aymankhattab.nateq.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات باب منع تكرار جمع تقرير الأخطاء (منطق نقي JVM بلا روبولكترك):
 *  يرفض الضغطات المتتالية أثناء جمعٍ جارٍ، ويُعيد فتح الباب بعد finish. */
class ErrorReportGateTest {

    @Test
    fun secondBegin_whileCollecting_rejected() {
        val gate = ErrorReportGate()
        assertTrue(gate.tryBegin())
        // ضغطات متتالية أثناء جمعٍ جارٍ: كلها مرفوضة.
        assertFalse(gate.tryBegin())
        assertFalse(gate.tryBegin())
        assertFalse(gate.tryBegin())
    }

    @Test
    fun finish_reopensGateForNextReport() {
        val gate = ErrorReportGate()
        assertTrue(gate.tryBegin())
        gate.finish()
        // اكتمال الرحلة الأولى يفتح الباب لالتقرير التالي.
        assertTrue(gate.tryBegin())
    }

    @Test
    fun repeatedRounds_rejectOnlyWhileCollecting() {
        val gate = ErrorReportGate()
        repeat(10) {
            assertTrue(
                "جولة " + it + " تُفتح أولَ مرة",
                gate.tryBegin()
            )
            assertFalse(
                "أثناء الجمع يُرفض التكرار",
                gate.tryBegin()
            )
            gate.finish()
        }
    }
}