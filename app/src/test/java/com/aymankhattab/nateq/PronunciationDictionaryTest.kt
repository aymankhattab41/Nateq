package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.PronunciationDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.io.File

/**
 * اختبارات قاموس النطق الشخصي (Robolectric).
 * في بيئة الاختبار قد يكون التخزين المشفّر (Keystore) متاحاً
 * أو لا؛ ذاكرة القاموس
 * سليمة في الحالتين، وتُجرَّب نضارة القرص المشفّر بشرط توفر التخزين فعلاً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class PronunciationDictionaryTest {

    private lateinit var context: Context
    private lateinit var dict: PronunciationDictionary

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dict = PronunciationDictionary(context)
    }

    // ===== حدّ الرقم: اختصارُ وحدةٍ/عملةٍ ملتصقٌ به =====

    // حارس: الكسر المُبلَّغ — القاموسُ كان صامتاً عن كل ما يُكتب ملتصقاً
    // بالرقم («٥٠ج» و«٣٠٠جم») لأن الرقمَ كان حدّاً يمنع الاستبدالَ من
    // الجانبين، فتسجيلُ «جم» في القاموس لم يكن يُغيّر النطق أبداً.
    @Test
    fun amountAbbreviationGluedToNumber_isNowReplaced() {
        dict.addEntry("جم", "جيم")
        assertEquals("الوزن 300جيم", dict.apply("الوزن 300جم"))
        assertEquals("الوزن 300 جيم", dict.apply("الوزن 300 جم"))
        dict.addEntry("ج", "جنيه مصري")
        assertEquals("السعر 50جنيه مصري", dict.apply("السعر 50ج"))
        dict.addEntry("م", "متر")
        assertEquals("الطول 5متر", dict.apply("الطول 5م"))
        dict.addEntry("كم", "كيلومتر")
        assertEquals("المسافة 12كيلومتر", dict.apply("المسافة 12كم"))
    }

    @Test
    fun gluedAmountAbbreviation_usesArabicAndLatinDigits() {
        dict.addEntry("جم", "جيم")
        assertEquals("الوزن ٣٠٠جيم", dict.apply("الوزن ٣٠٠جم"))
        assertEquals("الوزن ٢٥٠٫٥جيم", dict.apply("الوزن ٢٥٠٫٥جم"))
    }

    // حارس: الرقمُ على اليمين يبقى حدّاً — «م2» رمزُ ترتيبٍ لا وحدة
    @Test
    fun digitOnRightOfKey_stillBlocksSubstitution() {
        dict.addEntry("م", "متر")
        assertEquals("أرسل م2", dict.apply("أرسل م2"))
    }

    // حارس: الكلمةُ الطويلةُ الملتصقةُ بالرقم لا تُبدَّل (سقفُ ثلاثة أحرف
    // يفصل اختصارَ الوحدة عن الكلمة)
    @Test
    fun longArabicWordGluedToNumber_staysUntouched() {
        dict.addEntry("جنيه", "جنيه مصري")
        assertEquals("المبلغ 50جنيه", dict.apply("المبلغ 50جنيه"))
        // وكلمةٌ من ثلاثة أحرفٍ فوق الزناد تُعامَل معاملةَ الاختصار
        dict.addEntry("دولار", "دولار أمريكي")
        assertEquals("السعر 7دولار", dict.apply("السعر 7دولار"))
    }

    @Test
    fun threeLetterArabicWordGluedToNumber_countsAsAbbreviation() {
        // سقفُ ثلاثة أحرفٍ قرارٌ واعٍ: «متر» و«جم» و«كجم» اختصاراتٌ
        // يلتصقُن بالرقم، وكلمةٌ من ثلاثة أحرفٍ ملتصقةٌ به نادرة.
        dict.addEntry("باص", "حافلة")
        assertEquals("رقم 7حافلة", dict.apply("رقم 7باص"))
    }

    // حارس: داخلَ الكلمةِ لا استبدالُ أبداً («مج 5» و«5ممتاز»)
    @Test
    fun keyInsideWord_stillNeverReplaced() {
        dict.addEntry("جم", "جيم")
        dict.addEntry("م", "متر")
        assertEquals("مجموع 5", dict.apply("مجموع 5"))
        assertEquals("5ممتاز", dict.apply("5ممتاز"))
        assertEquals("مرحبا", dict.apply("مرحبا"))
    }

    // ===== حدود الكلمات في آلة Aho-Corasick (البند 10-2) =====

    @Test
    fun digitGluedToAmountAbbreviation_isApplied() {
        // عقدٌ متغيّر عمداً: كان الرقمُ يمنعُ الاستبدالَ من الجانبين فلا
        // يُطبَّق القاموسُ على ما يُكتب في العربية ملتصقاً بالرقم، فتسجيلُ
        // «م» في القاموس لم يكن يُغيّر النطق. الآن «50م» ← «50متر».
        dict.addEntry("م", "متر")
        assertEquals("الطلب 50متر", dict.apply("الطلب 50م"))
        assertEquals("السعر 50متر وعشرة", dict.apply("السعر 50م وعشرة"))
    }

    @Test
    fun digit_follows_key_blocksSubstitution() {
        dict.addEntry("م", "متر")
        // حرف بعد الرقم مباشرة في وحدات مثل "م2"
        assertEquals("أرسل م2", dict.apply("أرسل م2"))
    }

    @Test
    fun standaloneKey_stillReplaced() {
        dict.addEntry("م", "متر")
        assertEquals("قياس متر", dict.apply("قياس م"))
    }

    @Test
    fun letter_prevAndNext_stillBlocksCompounding() {
        dict.addEntry("م", "متر")
        // الكلمة الأطول تبقى سليمة ولا تُحوَّل داخل "مرحبا"
        assertEquals("مرحبا", dict.apply("مرحبا"))
        assertEquals("ألماً", dict.apply("ألماً"))
    }

    @Test
    fun dottedAbbreviation_matchesBeforeLetter() {
        // اختصار منتهٍ بنقطة متبوع بحرف: يُستبدل
        dict.addEntry("د.", "دكتور")
        assertEquals("دكتورأحمد", dict.apply("د.أحمد"))
        assertEquals("قال دكتورمحمد", dict.apply("قال د.محمد"))
    }

    @Test
    fun dottedAbbreviation_blockedWhenPartOfLongWord() {
        dict.addEntry("د.", "دكتور")
        // حرف قبل النقطة يمنع الاستبدال داخل كلمة أطول
        assertEquals("ود.أحمد", dict.apply("ود.أحمد"))
    }

    @Test
    fun longestKeyWins_overSharedPrefix() {
        dict.addEntry("د.", "دكتور")
        dict.addEntry("أ.د", "أستاذ دكتور")
        assertEquals("زور أستاذ دكتور", dict.apply("زور أ.د"))
    }

    // ===== الاستيراد: الدمج والتخطي (البند 10-3) =====

    @Test
    fun importReplace_skipsInvalidRows() {
        dict.addEntry("قديم", "مقابل")
        val longKey = "z".repeat(201) // أطول من MAX_KEY_LENGTH (200)
        val json = "{\"\":\"قيمة فارغة\", \"   \":\"مسافة\", " +
            "\"$longKey\":\"طويل\", \"مفتاح\":\"\", \"جديد\":\"صالح\"}"
        assertTrue(dict.importFromJson(json))
        assertEquals(mapOf("جديد" to "صالح"), dict.getAllEntries())
    }

    @Test
    fun importMerge_keepsExisting_andOverridesDuplicates() {
        dict.addEntry("قديم", "مقابل")
        val json = "{\"قديم\":\"مقابل2\", \"جديد\":\"صالح\", \"مفتاح\":\"\"}"
        assertTrue(dict.importFromJson(json, merge = true))
        assertEquals(
            mapOf("قديم" to "مقابل2", "جديد" to "صالح"),
            dict.getAllEntries()
        )
    }

    @Test
    fun import_allInvalid_returnsFalseAndKeepsExisting() {
        dict.addEntry("قديم", "مقابل")
        val json = "{\"\":\"قيمة فارغة\", \"   \":\"مسافة\"}"
        assertFalse(dict.importFromJson(json))
        assertFalse(dict.importFromJson(json, merge = true))
        // الاستبدال الفاشل لا يمسح القاموس الحالي
        assertEquals(mapOf("قديم" to "مقابل"), dict.getAllEntries())
    }

    @Test
    fun import_badSyntax_returnsFalseAndKeepsExisting() {
        dict.addEntry("قديم", "مقابل")
        assertFalse(dict.importFromJson("not json at all"))
        assertEquals(mapOf("قديم" to "مقابل"), dict.getAllEntries())
    }

    @Test
    fun import_oversized_rejected() {
        dict.addEntry("قديم", "مقابل")
        val huge = "{\"" + "x".repeat(2 * 1024 * 1024) + "\":\"y\"}"
        assertFalse(dict.importFromJson(huge))
        assertEquals(mapOf("قديم" to "مقابل"), dict.getAllEntries())
    }

    @Test
    fun import_replaceClears_oldBeforeImport() {
        dict.addEntry("قديم", "مقابل")
        val json = "{\"منخفض\":\"عالٍ\"}"
        assertTrue(dict.importFromJson(json))
        assertEquals(mapOf("منخفض" to "عالٍ"), dict.getAllEntries())
    }

    // ===== النضارة عبر القرص المشفّر (البند 10-1) =====

    @Test
    fun crossInstance_reloadPicksUpUiEdits() {
        // Robolectric بلا Keystore (ذاكرة فقط): لا يمكن التحقق من القرص هنا
        if (!dict.isPersistent()) return

        val ui = PronunciationDictionary(context)
        val engine = PronunciationDictionary(context)
        // الواجهة تضيف إدخالاً بعد أن بُني المحرك (مثيل منفصل ببيانات قديمة)
        ui.addEntry("زبدة", "سمنة")
        // apply() يرصد طابع القرص فيلتقط التعديل دون إعادة تشغيل الخدمة
        assertEquals("سمنة", engine.apply("زبدة"))
    }

    // ===== خنق فحص القرص (≥1.5 ثانية) والتبديل الذرّي (البند 10-4) =====

    private fun prefsFile(): File {
        val dir = context.filesDir.parentFile
        return File(dir, "shared_prefs/nateq_pronunciation_dict.xml")
    }

    @Test
    fun reloadIfChanged_diskCheckThrottledWithinWindow() {
        if (!dict.isPersistent()) return
        val file = prefsFile()
        assertTrue(file.exists())
        // الموضع الحر الأول: فحص القرص فوري (لا تغيير بعد ← false)
        assertFalse(dict.reloadIfChanged())
        // تغيّر القرص، لكن ضمن نافذة الخنق (1.5 ثانية): لا يُكتشف الآن
        file.setLastModified(System.currentTimeMillis() + 100_000L)
        assertFalse(dict.reloadIfChanged())
        assertFalse(dict.reloadIfChanged())
    }

    @Test
    fun reloadIfChanged_noThrottle_detectsStampChangeImmediately() {
        if (!dict.isPersistent()) return
        val noThrottle = PronunciationDictionary(context, 0L)
        val file = prefsFile()
        assertTrue(file.exists())
        noThrottle.reloadIfChanged() // الموضع الحر الأول بلا تغيير
        file.setLastModified(System.currentTimeMillis() + 100_000L)
        assertTrue(noThrottle.reloadIfChanged())
    }
}
