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
 * اختبارات إضافية للقاموس الشخصي: تصدير/استيراد دائري، الحد الأقصى التراكمي
 * (5000)، سلامة التزامن عبر kotlinx-coroutines، وحالات حدود جديدة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
        dict.addEntry("م", "متر")
        val json = dict.exportToJson()

        val fresh = PronunciationDictionary(context)
        assertTrue(fresh.importFromJson(json))
        // القاموس يستبدل الكلمة/الوحدة فقط؛ تحويل العدد إلى كلمةٍ عملُ
        // NumberStep في المعالج الكامل، لا القاموس.
        assertEquals("الطلب 5 متر", fresh.apply("الطلب 5 م"))
    }

    // ===== الحد الأقصى التراكمي =====

    @Test
    fun importExceedsMaxEntries_onlyAcceptedUpToLimit() {
        // بناء JSON يحتوي على 5010 إدخالات
        val entries = buildString {
            append("{")
            for (i in 0 until 5010) {
                if (i > 0) append(",")
                append("\"key$i\":\"val$i\"")
            }
            append("}")
        }
        assertTrue(dict.importFromJson(entries))
        assertEquals("الحد الأقصى 5000", 5000, dict.getAllEntries().size)
    }

    @Test
    fun importMerge_respectsCumulativeCap() {
        // إضافة 3000 ثم 3000 أخرى: المجموع يتجاوز 5000
        val batch1 = buildString {
            append("{")
            for (i in 0 until 3000) {
                if (i > 0) append(",")
                append("\"a$i\":\"v$i\"")
            }
            append("}")
        }
        assertTrue(dict.importFromJson(batch1))
        assertEquals(3000, dict.getAllEntries().size)

        val batch2 = buildString {
            append("{")
            for (i in 0 until 3000) {
                if (i > 0) append(",")
                append("\"b$i\":\"v$i\"")
            }
            append("}")
        }
        assertTrue(dict.importFromJson(batch2, merge = true))
        assertEquals("الحد التراكمي 5000", 5000, dict.getAllEntries().size)
    }

    // ===== سلامة التزامن عبر Coroutine =====

    @Test
    fun concurrentApply_noCorruption() = runBlocking {
        dict.addEntry("م", "متر")
        dict.addEntry("ك", "كيلوغرام")
        dict.addEntry("د.", "دكتور")

        // كل مفتاح معزول بمسافات حتى تقع على حدود كلمة صحيحة
        // (المفاتيح المتلاصقة بحروف عربية لا تطابقها حدود الكلمة — بالتصميم)
        val texts = (1..200).map { "طلب $it م و ك و د." }

        val results = with(Dispatchers.Default) {
            texts.map { text ->
                async { dict.apply(text) }
            }.awaitAll()
        }

        // كل نتيجة يجب أن تحتوي الاستبدالات الصحيحة
        for (result in results) {
            assertTrue("يجب أن يحتوي على 'متر': $result", result.contains("متر"))
            assertTrue("يجب أن يحتوي على 'كيلوغرام': $result", result.contains("كيلوغرام"))
            assertTrue("يجب أن يحتوي على 'دكتور': $result", result.contains("دكتور"))
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

    // ===== حالات حدود جديدة =====

    @Test
    fun arabicLetterAfterBlocksSubstitution() {
        dict.addEntry("م", "متر")
        // حرف عربي بعد المفتاح → لا استبدال
        assertEquals("مرحبا", dict.apply("مرحبا"))
    }

    @Test
    fun arabicLetterBeforeBlocksSubstitution() {
        dict.addEntry("ت", "تن")
        // حرف عربي قبل المفتاح → لا استبدال
        assertEquals(" contacted", dict.apply(" contacted"))
    }

    @Test
    fun keyAtStartOfText_withTrailingSpace() {
        dict.addEntry("م", "متر")
        assertEquals("متر والآن", dict.apply("م والآن"))
    }

    @Test
    fun keyAtEndOfText_withLeadingSpace() {
        dict.addEntry("م", "متر")
        assertEquals("أعمل متر", dict.apply("أعمل م"))
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