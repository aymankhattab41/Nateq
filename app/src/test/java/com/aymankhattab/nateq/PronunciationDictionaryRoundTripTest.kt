package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.PronunciationDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات القاموس الشخصي: تصدير/استيراد دائري، **الفشل الذريّ عند
 * أي مدخلٍ فاسد أو تجاوزِ سقف**، المطابقةُ بلا حدود (الالتصاقُ
 * بالأرقام وداخل الكلمات)، وسلامة التزامن عبر kotlinx-coroutines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class PronunciationDictionaryRoundTripTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dict = PronunciationDictionary(context)
    }

    // ===== التصدير/الاستيراد الدائري =====

    @Test
    fun exportThenImport_preservesEntries() {
        dict.addEntry("د.", "دكتور")
        dict.addEntry("أ.د", "أستاذ دكتور")
        dict.addEntry("HTTP", "إتش تي تي بي")

        val json = dict.exportToJson()
        assertTrue("JSON يحتوي على البيانات", json.isNotEmpty())

        val fresh = PronunciationDictionary(context)
        assertTrue(fresh.importFromJson(json))

        val entries = fresh.getAllEntries()
        assertEquals(3, entries.size)
        assertEquals("دكتور", entries["د."])
        assertEquals("أستاذ دكتور", entries["أ.د"])
        assertEquals("إتش تي تي بي", entries["HTTP"])
    }

    @Test
    fun exportThenImport_applyWorks() {
        dict.addEntry("متر", "مترا")
        val json = dict.exportToJson()

        val fresh = PronunciationDictionary(context)
        assertTrue(fresh.importFromJson(json))
        // القاموس يستبدل الكلمة/الوحدة فقط؛ تحويل العدد إلى كلمةٍ
        // عملُ NumberStep في المعالج الكامل، لا القاموس.
        assertEquals("الطلب 5 مترا", fresh.apply("الطلب 5 متر"))
    }

    /**
     * تجاوزُ سقف المدخلات **يُفشل الاستيراد كاملاً** (قرار المدير:
     * لا استيراد ناقصٍ صامت). كان `break` يقتطعُ عند الحدّ فيخرج
     * المستخدمُ بقاموسٍ ناقصٍ بلا إشعار، ثم يظنّ أنه كامل.
     * وحارسُ هذا هو ما يُمنع به فقدُ مدخلاتٍ بلا عِلم.
     */
    @Test
    fun importExceedingMaxEntries_failsAndLeavesDictionaryIntact() {
        val over = PronunciationDictionary.MAX_IMPORT_ENTRIES + 10
        val json = entriesJson(over) { "key$it" to "val$it" }
        assertFalse("تجاوزُ السقف يجب أن يُفشل", dict.importFromJson(json))
        assertEquals(0, dict.getAllEntries().size)
    }

    /**
     * السقفُ تراكميٌّ مع الدمج، وتجاوزُه يُفشل **دون أن يمسّ** القاموس
     * القائمَ — فالكتابةُ تتمّ بعد التحقّق كله فلا استبدالٌ جزئي.
     */
    @Test
    fun importMerge_exceedingCumulativeCap_failsAndKeepsPrevious() {
        val half = PronunciationDictionary.MAX_IMPORT_ENTRIES / 2
        val first = entriesJson(half) { "a$it" to "v$it" }
        assertTrue(dict.importFromJson(first))
        assertEquals(half, dict.getAllEntries().size)

        val second = entriesJson(half + 1) { "b$it" to "v$it" }
        assertFalse("الدمجُ المتجاوزُ للسقف يجب أن يُفشل",
            dict.importFromJson(second, merge = true))
        assertEquals("القاموسُ القائمُ لا يُمسّ", half,
            dict.getAllEntries().size)
    }

    /**
     * عقدُ الفشل الذري (قرار المدير: أي خطأ يُفشل الملفَ كلَّه):
     * مدخلٌ واحدٌ فاسدٌ — قيمةٌ عددية، أو مفتاحٌ فارغ، أو طولٌ
     * متجاوز — يمنع **دخول شيءٍ أصلاً**، فلا استيراد جزئي. وكان
     * الصفُّ الفاسد يُتخطّى صامتاً فيضيع بلا خبر.
     */
    @Test
    fun importWithAnyInvalidEntry_failsAtomically() {
        assertFalse("قيمةٌ ليست نصّاً", dict.importFromJson("""{"أ": 5}"""))
        assertFalse("مفتاحٌ فارغ", dict.importFromJson("""{"  ": "صالح"}"""))
        assertFalse("قيمةٌ فارغة", dict.importFromJson("""{"أ": ""}"""))
        assertFalse(
            "JSON تالف",
            dict.importFromJson("""{"أ": "ب",,}""")
        )
        assertFalse("ملفٌ فارغ", dict.importFromJson("{}"))
        assertEquals("لا مدخل واحداً دخل", 0, dict.getAllEntries().size)
    }

    /** مدخلٌ صالحٌ واحدٌ مع فاسدٍ لا يُستورد — لا تحقّق جزئي. */
    @Test
    fun importWithGoodAndBadEntries_takesNothing() {
        assertFalse(
            dict.importFromJson("""{"صالح": "نعم", "فاسد": 42}""")
        )
        assertEquals(0, dict.getAllEntries().size)
    }

    /** الملفُ الذي يتجاوز سقف الحجم يُرفض قبل التجزئة. */
    @Test
    fun importExceedingMaxBytes_fails() {
        val tooBig = "x".repeat(PronunciationDictionary.MAX_IMPORT_BYTES + 1)
        assertFalse(dict.importFromJson(tooBig))
    }

    /** مبانٍ JSON كبير بلا تكرارٍ يدوي. */
    private fun entriesJson(
        count: Int,
        keyOf: (Int) -> Pair<String, String>
    ): String =
        buildString {
            append("{")
            for (i in 0 until count) {
                if (i > 0) append(",")
                val (k, v) = keyOf(i)
                append("\"").append(k).append("\":\"").append(v).append("\"")
            }
            append("}")
        }

    // ===== سلامة التزامن عبر Coroutine =====

    @Test
    fun concurrentApply_noCorruption() = runBlocking {
        dict.addEntry("متر", "مترا")
        dict.addEntry("كيلوغرام", "كيلوجرام")
        dict.addEntry("د.", "دكتور")

        val texts = (1..200).map { "طلب $it متر و كيلوغرام و د." }

        val results = with(Dispatchers.Default) {
            texts.map { text ->
                async { dict.apply(text) }
            }.awaitAll()
        }

        // كل نتيجة يجب أن تحتوي الاستبدالات الصحيحة
        for (result in results) {
            assertTrue(
                "يجب أن يحتوي على 'مترا': $result",
                result.contains("مترا")
            )
            assertTrue(
                "يجب أن يحتوي على 'كيلوجرام': $result",
                result.contains("كيلوجرام")
            )
            assertTrue(
                "يجب أن يحتوي على 'دكتور': $result",
                result.contains("دكتور")
            )
        }
    }

    @Test
    fun concurrentAddEntries_noException() = runBlocking {
        with(Dispatchers.Default) {
            (0 until 100).map { i ->
                async { dict.addEntry("key$i", "value$i") }
            }.awaitAll()
        }
        assertEquals(100, dict.getAllEntries().size)
    }

    // ===== «بلا حدود»: الالتصاقُ بالأرقام والكلمات =====

    /**
     * عقدُ الالتصاق (بند 3.4: أي مسارٍ جديد يجب أن يُثبت صنفَه) —
     * **هذا هو العيبُ الذي أخبر به المستخدم**: مفتاحٌ ملتصقٌ برقمٍ
     * على اليمين أو اليسار كان صامتاً فيُقرأ ولا يُطبَّق. الآن يُطبَّق
     * بلا شرطٍ على أيّ جهة.
     */
    @Test
    fun keyAdjacentToDigits_appliesOnBothSides() {
        dict.addEntry("جم", "جرام")
        dict.addEntry("كم", "كيلومتر")
        assertEquals("وزنه ٥٠جرام", dict.apply("وزنه ٥٠جم"))
        assertEquals("جرام٥٠", dict.apply("جم٥٠"))
        assertEquals("٢كيلومتر", dict.apply("٢كم"))
        assertEquals("كيلومتر٢", dict.apply("كم٢"))
    }

    /**
     * **الأثرُ المقصودُ لقرارٍ بلا حدود:** المفتاحُ يُطبَّق داخل كلمةٍ
     * أطول فيشوّهها. كان هذا ممنوعاً بحدّ الكلمة؛ وهو الآن سلوكٌ
     * مقصودٌ وواضحٌ لا مُصادفة — والمستخدمُ يدخل مفتاحه بيده.
     */
    @Test
    fun shortKeyAppliesInsideLongerWord() {
        dict.addEntry("م", "متر")
        // «مرحبا» = م+رحبا ← «متر»+«رحبا»؛ الاستبدالُ يلصق ولا يقطع.
        assertEquals("متررحبا", dict.apply("مرحبا"))
        dict.addEntry("ت", "تن")
        assertEquals("تنواصل", dict.apply("تواصل"))
    }

    /**
     * وما يخفّف أثرَه: **الأطولُ يُطبَّق أولاً** عند بدايةٍ واحدة،
     * فمفتاحُ «جم» يحجبُ «ج» داخله — يُطبَّق الأطولُ ولا يُقتطع
     * بالجزئ.
     */
    @Test
    fun longerKeyWinsOverShorterOneInsideIt() {
        dict.addEntry("ج", "غرام")
        dict.addEntry("جم", "جرام")
        assertEquals("جرام", dict.apply("جم"))
    }

    @Test
    fun keyAtStartOfText_withTrailingSpace() {
        dict.addEntry("كيلوغرام", "كيلوجرام")
        assertEquals("كيلوجرام والآن", dict.apply("كيلوغرام والآن"))
    }

    @Test
    fun keyAtEndOfText_withLeadingSpace() {
        dict.addEntry("كيلوغرام", "كيلوجرام")
        assertEquals("أعمل كيلوجرام", dict.apply("أعمل كيلوغرام"))
    }

    @Test
    fun overlappingKeys_longestWins() {
        dict.addEntry("HTTP", "إتش تي تي بي")
        dict.addEntry("HTTPS", "إتش تي تي بي إس")
        // المفتاح الأطول (HTTPS) يستبدل كاملاً ولا يُقتطع بجزئه الأقصر (HTTP)
        assertEquals(" إتش تي تي بي إس كامل", dict.apply(" HTTPS كامل"))
    }

    @Test
    fun dottedAbbreviation_nextLetterAllowed() {
        dict.addEntry("د.", "دكتور")
        // النقطة تسمح بالحرف التالي (استثناء حد الكلمة)
        assertEquals("دكتورأحمد", dict.apply("د.أحمد"))
    }

    @Test
    fun dottedAbbreviation_atEndOfSentence() {
        dict.addEntry("د.", "دكتور")
        // النقطة جزءٌ من المفتاح فتُستبدل معه (بدل بقاء نقطة نهاية الجملة)
        assertEquals("نهاية دكتور", dict.apply("نهاية د."))
    }
}
