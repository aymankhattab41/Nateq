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
import java.text.Normalizer

/**
 * اختبارات حساسية التشكيل في قاموس النطق: مطابقة حساسة للتشكيل افتراضياً
 * مع تكافؤ Unicode القانوني وتجاهل التطويل فقط، في جميع النطاقات والأوضاع.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PronunciationDictionaryTashkeelTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary

    // عِلْم / عَلَم / عُلِم
    private val ilm = "\u0639\u0650\u0644\u0652\u0645"   // عِلْم
    private val alam = "\u0639\u064E\u0644\u064E\u0645"  // عَلَم
    private val ulim = "\u0639\u064F\u0644\u0650\u0645"  // عُلِم
    private val plain = "\u0639\u0644\u0645"             // علم (بلا تشكيل)

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
    fun `different diacritics produce different outputs`() {
        assertTrue(dict.addEntry(ilm, "نطق-عِلْم"))
        assertTrue(dict.addEntry(alam, "نطق-عَلَم"))
        assertTrue(dict.addEntry(ulim, "نطق-عُلِم"))

        assertEquals("نطق-عِلْم", dict.apply(ilm))
        assertEquals("نطق-عَلَم", dict.apply(alam))
        assertEquals("نطق-عُلِم", dict.apply(ulim))
    }

    @Test
    fun `no replacement without exact tashkeel match`() {
        // مفتاح مشكول لا يطابق بلا تشكيل أو بتشكيل مختلف
        dict.addEntry(ilm, "نطق-عِلْم")
        assertEquals(plain, dict.apply(plain, "ar"))
        assertEquals(alam, dict.apply(alam, "ar"))
        assertEquals(ulim, dict.apply(ulim, "ar"))
        assertEquals("نطق-عِلْم", dict.apply(ilm, "ar"))

        // مفتاح غير مشكول لا يبتلع كلمة مشكولة تلقائياً
        dict.clear()
        dict.addEntry(plain, "نطق-علم")
        assertEquals(ilm, dict.apply(ilm, "ar"))
        assertEquals(alam, dict.apply(alam, "ar"))
        assertEquals(ulim, dict.apply(ulim, "ar"))
        assertEquals("نطق-علم", dict.apply(plain, "ar"))
    }

    @Test
    fun `final tanwin prevents plain key match`() {
        // «علم» لا يُستبدل داخل «علمٌ» لانتهاء الحروف قبل التنوين
        val withTanwin = "\u0639\u0644\u0645\u064C" // علمٌ
        dict.addEntry(plain, "بديل")
        assertEquals(withTanwin, dict.apply(withTanwin, "ar"))
        // لكن مفتاح بنفس التنوين يطابق
        dict.addEntry(withTanwin, "بديل-منوّن")
        assertEquals("بديل-منوّن", dict.apply(withTanwin, "ar"))
    }

    @Test
    fun `shadda with haraka and canonical order are equivalent`() {
        // شدّة + فتحة بترتيبين مكافئين قانونياً
        val base = "\u0628" // ب
        val shadda = "\u0651"
        val fatha = "\u064E"
        val order1 = base + shadda + fatha
        val order2 = base + fatha + shadda
        // NFC قد يوحّد الترتيب؛ المطلوب التكافؤ القانوني (لا تصادم/التباس)
        dict.addEntry(order1, "بَبّ")
        // المفتاح المكافئ قانونياً يُعامل كهوية واحدة (تحديث لا ازدواج)
        assertEquals(1, dict.getAllEntries().size)
        assertEquals("بَبّ", dict.apply(order1, "ar"))
        assertEquals("بَبّ", dict.apply(order2, "ar"))
    }

    @Test
    fun `dagger alef is significant`() {
        // ألف خنجرية (0670) علامة ذات معنى
        val withDagger = "\u0631\u0670\u062D\u0645\u0646" // رحٰمن (تقريبي)
        val without = "\u0631\u062D\u0645\u0646"
        dict.addEntry(withDagger, "رحمن-خنجرية")
        assertEquals("رحمن-خنجرية", dict.apply(withDagger, "ar"))
        assertEquals(without, dict.apply(without, "ar"))
    }

    @Test
    fun `partial tashkeel is distinguished`() {
        val partial = "\u0639\u0650\u0644\u0645" // عِلْم جزئي (كسرة فقط)
        dict.addEntry(partial, "جزئي")
        assertEquals("جزئي", dict.apply(partial, "ar"))
        assertEquals(ilm, dict.apply(ilm, "ar"))
        assertEquals(plain, dict.apply(plain, "ar"))
    }

    @Test
    fun `composed and decomposed hamza are equivalent`() {
        val composed = "\u0623" // أ
        val decomposed = "\u0627\u0654" // ا + همزة فوق
        val nfcComposed = Normalizer.normalize(composed, Normalizer.Form.NFC)
        val nfcDecomposed = Normalizer.normalize(decomposed, Normalizer.Form.NFC)
        // تكافؤ قانوني: يُعاملان هوية واحدة
        dict.addEntry(nfcComposed, "همزة")
        assertEquals(1, dict.getAllEntries().size)
        assertEquals("همزة", dict.apply(nfcComposed, "ar"))
        assertEquals("همزة", dict.apply(nfcDecomposed, "ar"))
    }

    @Test
    fun `tatweel is ignored separately from tashkeel sensitivity`() {
        val withTatweel =
            "\u0639\u0640\u0650\u0644\u0640\u0652\u0645" // عِـلْـم
        dict.addEntry(ilm, "نطق-عِلْم")
        assertEquals("نطق-عِلْم", dict.apply(withTatweel, "ar"))
        // التطويل لا يغيّر هوية مفتاح مشكول مختلف (يبقى النص الأصلي كما هو)
        val withTatweelAlam =
            "\u0639\u0640\u064E\u0644\u0640\u064E\u0645" // عَـلَـم
        assertEquals(withTatweelAlam, dict.apply(withTatweelAlam, "ar"))
    }

    @Test
    fun `punctuation boundaries and ANYWHERE with tashkeel`() {
        dict.addEntry(ilm, "نطق", null, "AUTO")
        assertEquals("نطق، كلمة", dict.apply("$ilm، كلمة", "ar"))
        assertEquals("(نطق)", dict.apply("($ilm)", "ar"))
        // ANYWHERE مع تشكيل (حدود كلمات ليست إذناً بتجاهل الحركات)
        dict.clear()
        dict.addEntry(ilm, "نطق", null, "ANYWHERE")
        assertEquals("قبلنطقبعد", dict.apply("قبل${ilm}بعد", "ar"))
        // لكن لا يطابق تشكيل مختلف حتى في ANYWHERE
        assertEquals("قبل${alam}بعد", dict.apply("قبل${alam}بعد", "ar"))
    }

    @Test
    fun `emoji around diacritized word`() {
        dict.addEntry(ilm, "نطق")
        assertEquals("😀 نطق 😀", dict.apply("😀 $ilm 😀", "ar"))
        assertEquals("😀نطق😀", dict.apply("😀$ilm😀", "ar"))
    }

    @Test
    fun `scopes and longest priority and trace`() {
        // نطاق عام ولغوي
        dict.addEntry(ilm, "عام")
        dict.addEntry(ilm, "مصري", "ar-EG")
        assertEquals("عام", dict.apply(ilm, "ar"))
        assertEquals("مصري", dict.apply(ilm, "ar-EG"))

        // أولوية الأطول عند عدم تعارض
        dict.clear()
        val ilmPrefix = "\u0639\u0650\u0644\u0652" // عِلْ
        dict.addEntry(ilmPrefix, "قصير", null, "ANYWHERE")
        dict.addEntry(ilm, "طويل", null, "ANYWHERE")
        assertEquals("طويل", dict.apply(ilm, "ar"))

        // trace يطابق قرار apply (حسم التداخلات)
        val traces = dict.traceMatches(ilm, "ar")
        val accepted = traces.filter { it.accepted }
        assertEquals(1, accepted.size)
        assertEquals("طويل", accepted[0].value)
    }

    @Test
    fun `add remove import export and persist keep old records and values`() {
        dict.addEntry(ilm, "نطق-عِلْم")
        dict.addEntry(alam, "نطق-عَلَم")
        // تصدير/استيراد يحفظان المفاتيح وقيم النطق كما هي (بالتشكيل)
        val json = dict.exportToJson()
        assertTrue(json.contains("نطق-عِلْم"))
        assertTrue(json.contains("نطق-عَلَم"))

        dict.clear()
        assertTrue(dict.importFromJson(json))
        val entries = dict.getAllEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.values.contains("نطق-عِلْم"))
        assertTrue(entries.values.contains("نطق-عَلَم"))
        assertEquals("نطق-عِلْم", dict.apply(ilm, "ar"))
        assertEquals("نطق-عَلَم", dict.apply(alam, "ar"))

        // إعادة التحميل بعد إنشاء مثيل جديد (محاكاة إعادة التشغيل)
        val reloaded = PronunciationDictionary(context)
        val reEntries = reloaded.getAllEntries()
        assertEquals(2, reEntries.size)
        assertEquals("نطق-عِلْم", reloaded.apply(ilm, "ar"))
        assertEquals("نطق-عَلَم", reloaded.apply(alam, "ar"))
        // النص غير المطابق يبقى كما هو بعد إعادة التحميل
        assertEquals(plain, reloaded.apply(plain, "ar"))

        // الحذف بحسب الهوية المطابقة
        assertTrue(dict.removeEntry(ilm))
        assertEquals(1, dict.getAllEntries().size)
        assertEquals(ilm, dict.apply(ilm, "ar"))

        // النص غير المطابق يبقى بتشكيله (لا حذف تشكيل)
        val mixed = "بداية $alam نهاية"
        dict.clear()
        dict.addEntry(ilm, "بديل")
        assertEquals(mixed, dict.apply(mixed, "ar"))
    }

    @Test
    fun `trace reports diacritic near miss and selected key`() {
        dict.addEntry(ilm, "نطق-عِلْم")
        val traces = dict.traceMatches(alam, "ar")
        assertTrue(traces.isNotEmpty())
        val nearMiss = traces.firstOrNull {
            !it.accepted && it.key == ilm &&
                it.reason.contains("التشكيل مختلف")
        }
        assertTrue(
            "يجب الإبلاغ عن اختلاف التشكيل مع المفتاح المختار",
            nearMiss != null
        )
        // لا استبدال فعلي
        assertEquals(alam, dict.apply(alam, "ar"))
    }

    @Test
    fun `replacement keeps user tashkeel and unmatched text unchanged`() {
        val spoken = "\u0627\u0650\u0639\u0652\u0644\u064E\u0645" // اِعْلَم بتشكيل
        dict.addEntry(plain, spoken)
        assertEquals(spoken, dict.apply(plain, "ar"))
        // النص غير المطابق لا يُجرّد تشكيله
        val untouched = "هذا $alam مع $ilm"
        assertEquals(untouched, dict.apply(untouched, "ar"))
    }
}
