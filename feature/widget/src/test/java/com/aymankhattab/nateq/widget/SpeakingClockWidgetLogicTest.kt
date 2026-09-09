package com.aymankhattab.nateq.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات منطق أداة الساعة الناطقة (SpeakingClockWidget):
 *  قرارُ قبول/رفض البث حسب مكوّنه، بخاصة رفض الحالة الغائبة (null)
 *  التي كانت تتجاهل النقر صامتاً. */
class SpeakingClockWidgetLogicTest {

    private val selfPackage = "com.aymankhattab.nateq"
    private val selfClass = "com.aymankhattab.nateq.widget.SpeakingClockWidget"

    @Test
    fun acceptsExactComponent() {
        assertEquals(
            true,
            isComponentOurs(selfPackage, selfClass, selfPackage, selfClass)
        )
    }

    @Test
    fun rejectsNullComponent() {
        assertEquals(
            false,
            isComponentOurs(null, null, selfPackage, selfClass)
        )
    }

    @Test
    fun rejectsNullPackageOnly() {
        assertEquals(
            false,
            isComponentOurs(null, selfClass, selfPackage, selfClass)
        )
    }

    @Test
    fun rejectsMismatchedPackage() {
        assertEquals(
            false,
            isComponentOurs(
                "com.other.app", selfClass, selfPackage, selfClass
            )
        )
    }

    @Test
    fun rejectsMismatchedClass() {
        assertEquals(
            false,
            isComponentOurs(
                selfPackage, "com.other.Cls", selfPackage, selfClass
            )
        )
    }

    @Test
    fun rejectsEmptyPackageBlock() {
        // مكوّن بلا حزمة: تُرفض، فلا يُنطق عندما يصل بثٌّ لا يُحدّد حزمةً.
        assertEquals(
            false,
            isComponentOurs("", selfClass, selfPackage, selfClass)
        )
    }
}