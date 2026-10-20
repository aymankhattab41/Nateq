package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.TextProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * الاختبار الذهبي لمعالجة ونطق الأرقام (الأمر 3).
 * يحتوي على أكثر من 60 حالة اختبار للمسار الكامل بالعربية والإنجليزية.
 * الحالات المتعمد تركها كما هي:
 * - عناوين IPv4 مثل «192.168.1.1»: تُترك عنوان شبكة بلا تفقيط عددي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NumberSpeechGoldenTest {

    private lateinit var context: Context
    private lateinit var processor: TextProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        processor = TextProcessor(context)
    }

    data class TestCase(
        val input: String,
        val lang: String,
        val expected: String,
        val description: String
    )

    @Test
    fun testGoldenSixtyPlusCases() {
        val cases = listOf(
            // 1. أعداد صحيحة عربية
            TestCase("0", "ar", "صِفْرْ", "صفر بالعربية"),
            TestCase("1", "ar", "واحد", "واحد بالعربية"),
            TestCase("2", "ar", "اثنان", "اثنان بالعربية"),
            TestCase("3", "ar", "ثلاثة", "ثلاثة بالعربية"),
            TestCase("10", "ar", "عشرة", "عشرة بالعربية"),
            TestCase("15", "ar", "خمسة عشر", "خمسة عشر بالعربية"),
            TestCase("100", "ar", "مائة", "مائة بالعربية"),
            TestCase("200", "ar", "مائتان", "مائتان بالعربية"),
            TestCase(
                "1234", "ar", "ألف ومائتان وأربعة وثلاثون",
                "ألف ومائتان وأربعة وثلاثون"
            ),
            TestCase("1000000", "ar", "مليون", "مليون بالعربية"),

            // 2. أعداد صحيحة إنجليزية
            TestCase("0", "en", "zero", "zero بالإنجليزية"),
            TestCase("1", "en", "one", "one بالإنجليزية"),
            TestCase("7", "en", "seven", "seven بالإنجليزية"),
            TestCase("15", "en", "fifteen", "fifteen بالإنجليزية"),
            TestCase(
                "100", "en", "one hundred",
                "one hundred بالإنجليزية"
            ),
            TestCase(
                "1234", "en",
                "one thousand two hundred thirty four",
                "1234 بالإنجليزية"
            ),

            // 3. أعداد عشرية
            TestCase(
                "3.14", "ar", "ثلاثة فاصلة واحد أربعة",
                "كسر عشري عربي 3.14"
            ),
            TestCase(
                "0.5", "ar", "نصف",
                "كسر عشري عربي 0.5"
            ),
            TestCase(
                "3.14", "en", "three point one four",
                "كسر عشري إنجليزي 3.14"
            ),
            TestCase(
                "0.5", "en", "zero point five",
                "كسر عشري إنجليزي 0.5"
            ),

            // 4. أعداد سالبة
            TestCase(
                "-5", "ar", "ناقص خمسة",
                "عدد سالب عربي -5"
            ),
            TestCase(
                "-12", "ar", "ناقص اثنا عشر",
                "عدد سالب عربي -12"
            ),
            TestCase(
                "-5", "en", "minus five",
                "عدد سالب إنجليزي -5"
            ),

            // 5. فواصل الآلاف
            TestCase(
                "1,234", "ar", "ألف ومائتان وأربعة وثلاثون",
                "فاصلة آلاف إنجليزية في سياق عربي"
            ),
            TestCase(
                "1٬234", "ar", "ألف ومائتان وأربعة وثلاثون",
                "فاصلة آلاف عربية ٬"
            ),
            TestCase(
                "10,000", "en", "ten thousand",
                "فاصلة آلاف إنجليزية"
            ),

            // 6. الفاصلة العشرية العربية ٫
            TestCase(
                "3٫14", "ar", "ثلاثة فاصلة واحد أربعة",
                "فاصلة عشرية عربية ٫"
            ),
            TestCase(
                "0٫5", "ar", "نصف",
                "فاصلة عشرية عربية 0٫5"
            ),

            // 7. ملاصقة الحروف (المطلوبة في الأمر 3)
            TestCase(
                "50ج", "ar", "خمسون جنيهاً مصرياً",
                "ملاصقة رقم بحرف ج (عملة)"
            ),
            TestCase(
                "5م", "ar", "خمسة أمتار",
                "ملاصقة رقم بحرف م (وحدة قياس)"
            ),
            TestCase(
                "100كم", "ar", "مائة كم",
                "ملاصقة رقم بكيلومتر"
            ),
            TestCase(
                "3أيام", "ar", "ثلاثة أيام",
                "ملاصقة رقم بكلمة أيام"
            ),
            TestCase(
                "٥٠جنيه", "ar", "خمسون جنيه",
                "أرقام هندية ملاصقة لكلمة جنيه"
            ),
            TestCase(
                "50% خصم", "ar", "خمسون بالمئة خصم",
                "نسبة مئوية ملاصقة"
            ),
            TestCase(
                "رقم 12ب", "ar", "رقم اثنا عشر ب",
                "رقم 12 ملاصق بحرف ب"
            ),
            TestCase(
                "عمارة 7أ", "ar", "عمارة سبعة أ",
                "عمارة 7 ملاصق بحرف أ"
            ),

            // 8. النسب المئوية
            TestCase(
                "50%", "ar", "خمسون بالمئة",
                "50 بالمائة عربي"
            ),
            TestCase(
                "100%", "ar", "مائة بالمئة",
                "100 بالمائة عربي"
            ),
            TestCase(
                "50%", "en", "fifty percent",
                "50 percent إنجليزي"
            ),

            // 9. الإصدارات
            TestCase(
                "1.6.1", "ar", "واحد فاصلة ستة فاصلة واحد",
                "إصدار ثلاثي عربي"
            ),
            TestCase(
                "10.2.15", "ar",
                "عشرة فاصلة اثنان فاصلة خمسة عشر",
                "إصدار ثلاثي مركب عربي"
            ),
            TestCase(
                "1.6.1", "en", "one point six point one",
                "إصدار ثلاثي إنجليزي"
            ),

            // 10. الحالات المتعمد تركها (IPv4)
            TestCase(
                "192.168.1.1", "ar", "192.168.1.1",
                "عنوان IPv4 يُترك كما هو"
            ),
            TestCase(
                "10.0.0.1", "ar", "10.0.0.1",
                "عنوان IP محلي يُترك كما هو"
            ),

            // 11. أرقام هواتف
            TestCase(
                "01012345678", "ar",
                "صِفْرْ واحد صِفْرْ واحد اثنان ثلاثة أربعة خمسة ستة " +
                    "سبعة ثمانية",
                "رقم هاتف محمول"
            ),

            // 12. أرقام إضافية وتراكيب مختلفة
            TestCase("4", "ar", "أربعة", "أربعة بالعربية"),
            TestCase("5", "ar", "خمسة", "خمسة بالعربية"),
            TestCase("6", "ar", "ستة", "ستة بالعربية"),
            TestCase("7", "ar", "سبعة", "سبعة بالعربية"),
            TestCase("8", "ar", "ثمانية", "ثمانية بالعربية"),
            TestCase("9", "ar", "تسعة", "تسعة بالعربية"),
            TestCase("11", "ar", "أحد عشر", "أحد عشر"),
            TestCase("12", "ar", "اثنا عشر", "اثنا عشر"),
            TestCase("13", "ar", "ثلاثة عشر", "ثلاثة عشر"),
            TestCase("14", "ar", "أربعة عشر", "أربعة عشر"),
            TestCase("16", "ar", "ستة عشر", "ستة عشر"),
            TestCase("17", "ar", "سبعة عشر", "سبعة عشر"),
            TestCase("18", "ar", "ثمانية عشر", "ثمانية عشر"),
            TestCase("19", "ar", "تسعة عشر", "تسعة عشر"),
            TestCase("20", "ar", "عشرون", "عشرون"),
            TestCase("30", "ar", "ثلاثون", "ثلاثون"),
            TestCase("40", "ar", "أربعون", "أربعون"),
            TestCase("50", "ar", "خمسون", "خمسون"),
            TestCase("60", "ar", "ستون", "ستون"),
            TestCase("70", "ar", "سبعون", "سبعون"),
            TestCase("80", "ar", "ثمانون", "ثمانون"),
            TestCase("90", "ar", "تسعون", "تسعون"),
            TestCase("21", "ar", "واحد وعشرون", "واحد وعشرون"),
            TestCase("99", "ar", "تسعة وتسعون", "تسعة وتسعون")
        )

        assertTrue("Expected >= 60 test cases", cases.size >= 60)

        val failures = mutableListOf<String>()
        for ((idx, tc) in cases.withIndex()) {
            val actual = processor.process(tc.input, tc.lang)
            if (actual != tc.expected) {
                failures.add(
                    "#${idx + 1} [${tc.description}] Input: '${tc.input}', " +
                        "Expected: '${tc.expected}', Actual: '$actual'"
                )
            }
        }

        if (failures.isNotEmpty()) {
            val report = failures.joinToString("\n")
            System.err.println("Golden test failures:\n$report")
            assertEquals(
                "Golden test failures occurred:\n$report",
                0,
                failures.size
            )
        }
    }
}
