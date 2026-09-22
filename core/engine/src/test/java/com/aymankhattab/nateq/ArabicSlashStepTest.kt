package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.pipeline.ArabicSlashStep
import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات خطوة إزالة نطق الشرطة المائلة في الجمل العربية. */
class ArabicSlashStepTest {

    @Test
    fun slash_replacedWithSpace_inArabic() {
        assertEquals("خسر 1 0", ArabicSlashStep.apply("خسر 1/0"))
        assertEquals("1 0", ArabicSlashStep.apply("1/0"))
        assertEquals("نعم لا", ArabicSlashStep.apply("نعم/لا"))
        assertEquals("و أو", ArabicSlashStep.apply("و/أو"))
        assertEquals("أ   ب", ArabicSlashStep.apply("أ / ب"))
    }

    @Test
    fun noSlash_returnsOriginalInput() {
        val text = "جملة عربية عادية بلا شرطة"
        assertEquals(text, ArabicSlashStep.apply(text))
    }

    @Test
    fun english_preservesSlash() {
        assertEquals("1/0", ArabicSlashStep.applyEnglish("1/0"))
        assertEquals("and/or", ArabicSlashStep.applyEnglish("and/or"))
    }
}
