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

    /**
     * عقدُ الطبقات: العامُّ يُدمج أوّلاً ثم تفوز لغةُ النص.
     *
     * والمفاتيحُ هنا أطولُ من حرفٍ واحد عمداً: فبلا حدودٍ (قرار المدير)
     * يُطبَّق المفتاحُ القصير داخل الكلمات فيشوّه جارَه — «م» تُطابَق في
     * «ثم» فتصير «ثتر» — وهذا سلوكٌ مقصودٌ في القاموس بلا حدود، لا
     * عيبُ طبقات. فاختبارُ الطبقات لا يختلط بالاختبارِ بحكم الطول.
     */
    @Test
    fun `apply merges global first then language wins`() {
        dict.addEntry("د.", "دكتور")
        dict.addEntry("د.", "دكتوراه", "ar-EG")
        dict.addEntry("كم", "كيلومتر", "ar-EG")
        assertEquals("يقول دكتور", dict.apply("يقول د."))
        assertEquals(
            "يقول دكتوراه قطعت 5 كيلومتر",
            dict.apply("يقول د. قطعت 5 كم", "ar-EG")
        )
        assertEquals(
            "global only for en",
            "يقول دكتور قطعت 5 كم",
            dict.apply("يقول د. قطعت 5 كم", "en")
        )
    }

    /**
     * عقدُ «بلا حدود» الحاكم: المفتاحُ يُطبَّق ملتصقاً برقمٍ أو داخل كلمة
     * على أيّ جهة — وهذا ما كان معطّلاً فلم يعمل القاموس عملياً.
     *
     * **ما يحرسه فعلاً:** (أ) الالتصاقُ بالرقم يميناً ويساراً — «٥٠ج»
     * و«2كم» كانا صامتين؛ (ب) **الأطولُ يُطبَّق أولاً** عند بدايةٍ واحدة
     * فمفتاحُ «جم» يحجبُ «ج» داخله فيخرج «جرام» لا «جرامم»؛ (ج) والمفتاحُ
     * داخل كلمةٍ أطول يُطبَّق — «كمتب» ← «كيلومترتب» — وهو **الأثرُ
     * المقصودُ لقرارٍ بلا حدود**، فيُثبَّت هنا عمداً كي لا يُنسى.
     */
    @Test
    fun `key applies next to digits and inside words`() {
        dict.addEntry("جم", "جرام")
        dict.addEntry("ج", "غرام")
        dict.addEntry("كم", "كيلومتر")
        assertEquals("وزنه ٥٠غرام", dict.apply("وزنه ٥٠ج"))
        assertEquals("مسافة ٣٠٠جرام", dict.apply("مسافة ٣٠٠جم"))
        assertEquals("قارأته 2كيلومتر", dict.apply("قارأته 2كم"))
        assertEquals("كيلومترتب", dict.apply("كمتب"))
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

    @Test
    fun `diacriticsOnlyKeyIsRejectedAndDoesNotCorruptTrie`() {
        assertFalse(dict.addEntry("ً", "تنوين"))
        assertFalse(dict.addEntry("ّ", "شدة"))
        assertTrue(dict.importFromJson("""{"ً": "تنوين", "د.": "دكتور"}"""))
        assertEquals("دكتور محمد", dict.apply("د. محمد"))
        assertEquals("سلام عليكم", dict.apply("سلام عليكم"))
    }
}
