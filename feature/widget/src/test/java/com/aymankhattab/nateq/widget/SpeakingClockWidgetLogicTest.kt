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

    // ═══ بند 4.1: حراسة بث النقر (توكن سري) ═══

    @Test
    fun acceptsExactSpeakToken() {
        // التوكنَ السري وحده هو ما يضعه تطبيقنا عند بناء نية النقر.
        assertEquals(true, hasSpeakToken(WIDGET_TOKEN_VALUE))
    }

    @Test
    fun rejectsNullToken() {
        // بثٌّ مصنوعٌ بلا التوكن (تطبيق خارجي صنع Intent ساذجاً) يُرفض.
        assertEquals(false, hasSpeakToken(null))
    }

    @Test
    fun rejectsBlankToken() {
        assertEquals(false, hasSpeakToken(""))
    }

    @Test
    fun rejectsForgedToken() {
        // لا قيمةَ تعسفيةٍ تجتاز — التوكن قيمة ثابتة خاصة غير مُوثَّقة.
        assertEquals(false, hasSpeakToken("random-forgery"))
    }
}