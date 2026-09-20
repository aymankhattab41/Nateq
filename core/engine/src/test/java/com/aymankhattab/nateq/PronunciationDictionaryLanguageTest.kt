package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.PronunciationDictionary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات النطاق اللغوي للقاموس (بند الأوامر د.3.5): إدخالاتُ لغةٍ تُخزَّن
 * وتُقرأ مع بقية الطبقات تحت "dictionary_scopes"، يُدمج العامُّ فوقه الخاص
 * عند النطق، ولا تنكسر الاستدعاءاتُ القائمة بلا وسم.
 *
 * ملاحظة بيئة: التخزين المشفّر لا يُنشأ تحت Robolectric فيعمل القاموس
 * بالذاكرة فقط ([isPersistent] = false) — لذا تُفحص الحالة لا القيمة
 * الراجعة للحفظ، ولا يُختبر الثبات عبر المثيلات هنا.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PronunciationDictionaryLanguageTest {

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
    fun `language entries stay scoped and hidden from global`() {
        dict.addEntry("د.", "دكتور", "ar-EG")
        dict.addEntry("ص", "صفحة")
        assertEquals(mapOf("ص" to "صفحة"), dict.getAllEntries())
        assertEquals(mapOf("د." to "دكتور"), dict.getAllEntries("ar-EG"))
        assertEquals(
            emptyMap<String, String>(), dict.getAllEntries("en")
        )
    }

    @Test
    fun `apply merges global first then language wins`() {
        dict.addEntry("د.", "دكتور")
        dict.addEntry("د.", "دكتوراه", "ar-EG")
        dict.addEntry("م", "متر", "ar-EG")
        assertEquals("يقول دكتور", dict.apply("يقول د."))
        assertEquals(
            "يقول دكتوراه ثم متر", dict.apply("يقول د. ثم م", "ar-EG")
        )
        assertEquals(
            "no overlay for en",
            "يقول دكتور", dict.apply("يقول د.", "en")
        )
    }

    @Test
    fun `removing last language entry empties only that layer`() {
        dict.addEntry("API", "إيه بي آي", "en")
        dict.addEntry("ص", "صفحة")
        dict.removeEntry("API", "en")
        assertEquals(
            emptyMap<String, String>(), dict.getAllEntries("en")
        )
        assertEquals(mapOf("ص" to "صفحة"), dict.getAllEntries())
        assertFalse(dict.removeEntry("API", "en"))
    }

    @Test
    fun `import and export respect language scope`() {
        dict.addEntry("قديم", "مقابل")
        assertTrue(dict.importFromJson("""{"جديد": "صالح"}""", true, "fr"))
        assertEquals("صالح", dict.getAllEntries("fr")["جديد"])
        assertEquals(null, dict.getAllEntries()["جديد"])
        val exported = dict.exportToJson("fr")
        assertTrue(exported.contains("صالح"))
        assertFalse(exported.contains("مقابل"))
    }
}