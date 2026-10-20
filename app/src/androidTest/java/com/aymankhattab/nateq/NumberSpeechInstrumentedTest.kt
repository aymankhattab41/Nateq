package com.aymankhattab.nateq

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * اختبار جهازي (instrumented) لنطق الأرقام الملاصقة على محرك أندرويد الحقيقي
 * (ICU) للتأكد من تفقيط الأرقام دون الاعتماد على حدود ASCII \b (الأمر 3).
 */
@RunWith(AndroidJUnit4::class)
class NumberSpeechInstrumentedTest {

    private lateinit var processor: TextProcessor

    @Before
    fun setUp() {
        val context =
            ApplicationProvider.getApplicationContext<android.content.Context>()
        processor = TextProcessor(context)
    }

    @Test
    fun testAttachedNumbersOnDevice() {
        // 50ج
        val r1 = processor.process("50ج", "ar")
        assertEquals("خمسون ج", r1)

        // 5م
        val r2 = processor.process("5م", "ar")
        assertEquals("خمسة م", r2)

        // 100كم
        val r3 = processor.process("100كم", "ar")
        assertEquals("مائة كم", r3)

        // 3أيام
        val r4 = processor.process("3أيام", "ar")
        assertEquals("ثلاثة أيام", r4)

        // ٥٠جنيه
        val r5 = processor.process("٥٠جنيه", "ar")
        assertEquals("خمسون جنيه", r5)

        // 50% خصم
        val r6 = processor.process("50% خصم", "ar")
        assertEquals("خمسون بالمائة خصم", r6)

        // رقم 12ب
        val r7 = processor.process("رقم 12ب", "ar")
        assertEquals("رقم اثنا عشر ب", r7)

        // عمارة 7أ
        val r8 = processor.process("عمارة 7أ", "ar")
        assertEquals("عمارة سبعة أ", r8)
    }
}
