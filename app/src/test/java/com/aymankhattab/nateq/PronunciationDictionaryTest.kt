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

    // ===== القاموس مفتوح: أي مفتاحٍ يُطبَّق حيث ورد =====

// حارس: الكسر المُبلَّغ — القاموسُ كان صامتاً عن كل ما يُكتب ملتصقاً
// بالرقم («٥٠ج» و«٣٠٠جم») لأن الرقمَ كان حدّاً يمنع الاستبدالَ من
// الجانبين، فتسجيلُ «جم» في القاموس لم يكن يُغيّر النطق أبداً.
// **الحدودُ أُلغيت كلها** (قرار المدير) فالملتصقُ يُطبَّق من أيّ جهة.
@Test
fun anyKeyGluedToNumber_isApplied() {
    dict.addEntry("جم", "جيم")
    assertEquals("الوزن 300جيم", dict.apply("الوزن 300جم"))
    assertEquals("الوزن 300 جيم", dict.apply("الوزن 300 جم"))
    dict.addEntry("ج", "جنيه مصري")
    assertEquals("السعر 50جنيه مصري", dict.apply("السعر 50ج"))
    // المفتاح على يسار الرقم يُطبَّق أيضاً (كان الرقمُ يسدّ الطريق)
    // ولا مفتاحُه الأقصر: «ج» و«جم» يبدأان من الموضع نفسه فيُطبَّق
    // الأطولُ «جم» ← «جيم»، فلا يُقتطع Short عمّا بعده.
    assertEquals("جيم300", dict.apply("جم300"))
}

@Test
fun gluedKey_usesArabicAndLatinDigits() {
    dict.addEntry("جم", "جيم")
    assertEquals("الوزن ٣٠٠جيم", dict.apply("الوزن ٣٠٠جم"))
    assertEquals("الوزن ٢٥٠٫٥جيم", dict.apply("الوزن ٢٥٠٫٥جم"))
}

/**
 * حارس: الرقمُ على **اليمين** لم يبقَ حدّاً. «م2» رمزُ ترتيبٍ في نظر
 * صاحب القرار، فالتطبيقُ هو المطلوب لا المنع.
 */
@Test
fun digitOnRightOfKey_appliesToo() {
    dict.addEntry("م", "متر")
    assertEquals("أرسل متر2", dict.apply("أرسل م2"))
}

    // حارس: لا سقفَ لطول المفتاح ولا شرطَ على لغته — كلمةٌ كاملةٌ تُبدَّل
    // وهي ملتصقةٌ بالرقم، في كل طولٍ (حرفٌ واحد، وثلاثة، وخمسة، وأطول)
    @Test
    fun longKeyGluedToNumber_isAlsoApplied() {
        dict.addEntry("جنيه", "جنيه مصري")
        assertEquals("المبلغ 50جنيه مصري", dict.apply("المبلغ 50جنيه"))
        dict.addEntry("دولار", "دولار أمريكي")
        assertEquals("السعر 7دولار أمريكي", dict.apply("السعر 7دولار"))
        dict.addEntry("متر", "مِتْر")
        assertEquals("الطول 5مِتْر", dict.apply("الطول 5متر"))
        dict.addEntry("باص", "حافلة")
        assertEquals("رقم 7حافلة", dict.apply("رقم 7باص"))
    }

    // حارس: الطلبُ المُبلَّغ — القاموسُ مفتوحٌ على مفاتيحَ بفراغاتٍ في
    // وسطها، تُبدَّل كاملةً بلا تصفيةٍ ولا شرط.
    @Test
    fun multiWordKey_isReplacedWholesale() {
        dict.addEntry("عبد السلام", "محمد")
        assertEquals("اتصل محمد", dict.apply("اتصل عبد السلام"))
        assertEquals(
            "السلام عليكم",
            dict.apply("السلام عليكم")
        )
        dict.addEntry("شركة الاتصالات", "الشركة")
        assertEquals(
            "went to الشركة",
            dict.apply("went to شركة الاتصالات")
        )
    }

/**
 * **الأثرُ المقصودُ لقرارٍ بلا حدود:** المفتاحُ يُطبَّق داخل كلمةٍ أطول
 * فيشوّهها — «مرحبا» ← «متررحبا». كان هذا ممنوعاً بحدّ الكلمة، وهو
 * الآن سلوكٌ معلنٌ لا مُصادفة: **المفتاحُ القصير يُدخل صاحبه في
 * Responsibility كل كلمةٍ تحويه**، وما يخفّفه أن الأطولَ يُطبَّق أولاً.
 *
 * فاحسبها قبل أن تُدخل مفتاحاً من حرفٍ واحد.
 */
@Test
fun keyInsideWord_isApplied_notBlocked() {
    dict.addEntry("م", "متر")
    // «مرحبا» = م + رحبا ← «متر» + «رحبا»؛ الاستبدالُ يلصقُ ولا يقطع.
    assertEquals("متررحبا", dict.apply("مرحبا"))
    // ولا حارسَ له يُعيد العمل؛ فالعقدُ الواحد يجب أن يبقى واحداً
    assertEquals("ألمتراً", dict.apply("ألماً"))
}

/** وما يخفّف الأثرَ: الأطولُ يُطبَّق أولاً عند بدايةٍ واحدة. */
@Test
fun longerKeyWinsInsideShorterKeyOccurrence() {
    dict.addEntry("م", "متر")
    dict.addEntry("متر", "مِتْر")
    assertEquals("مِتْر", dict.apply("متر"))
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
fun standaloneKey_stillReplaced() {
    dict.addEntry("م", "متر")
    assertEquals("قياس متر", dict.apply("قياس م"))
}

/**
 * حارس: **حدودُ الكلمة أُلغيت كلها** — لا حرفٌ قبل ولا بعد يمنع
 * الاستبدالَ anymore. كان هذا الحارسُ يقود إلى «مرحبا» سليمة، وهو
 * بالضبط ما عارضه صاحب القرار: أن يُطبَّق المفتاحُ حيث ورد.
 */
@Test
fun letter_prevAndNext_noLongerBlockCompounding() {
    dict.addEntry("م", "متر")
    assertEquals("متررحبا", dict.apply("مرحبا"))
}

    @Test
    fun dottedAbbreviation_matchesBeforeLetter() {
        // اختصار منتهٍ بنقطة متبوع بحرف: يُستبدل
        dict.addEntry("د.", "دكتور")
        assertEquals("دكتورأحمد", dict.apply("د.أحمد"))
        assertEquals("قال دكتورمحمد", dict.apply("قال د.محمد"))
    }

@Test
fun dottedAbbreviation_appliesInsideLongWordToo() {
    dict.addEntry("د.", "دكتور")
    // حرفٌ قبل النقطة لم يبقَ مانعاً (بلا حدود)
    assertEquals("ودكتورأحمد", dict.apply("ود.أحمد"))
}

    @Test
    fun longestKeyWins_overSharedPrefix() {
        dict.addEntry("د.", "دكتور")
        dict.addEntry("أ.د", "أستاذ دكتور")
        assertEquals("زور أستاذ دكتور", dict.apply("زور أ.د"))
    }

// ===== الاستيراد: الفشلُ الذريّ لا التخطي (قرار المدير) =====

/**
 * عقدُ الفشل الذري: **سطرٌ واحدٌ فاسدٌ يُفشل الملفَ كاملاً**. كان
 * السطرُ الفاسد يُتخطّى صامتاً فيخرج المستخدمُ بقاموسٍ ناقصٍ لا يعرف
 * ما ضاع منه —وهذا صمتٌ يُقنعه بأنّ كل شيءٍ دخل.
 */
@Test
fun importReplace_withAnyInvalidRow_failsAndTakesNothing() {
    dict.addEntry("قديم", "مقابل")
    val longKey = "z".repeat(
        PronunciationDictionary.MAX_KEY_LENGTH + 1
    )
    val json = "{\"\":\"قيمة فارغة\", \"   \":\"مسافة\", " +
        "\"$longKey\":\"طويل\", \"مفتاح\":\"\", \"جديد\":\"صالح\"}"
    assertFalse("أي مدخلٍ فاسد يُفشل الملف", dict.importFromJson(json))
    assertEquals(
        "والقاموسُ القائم لم يُمسّ",
        mapOf("قديم" to "مقابل"), dict.getAllEntries()
    )
}

@Test
fun importMerge_overridesDuplicates_andKeepsRest() {
    dict.addEntry("قديم", "مقابل")
    dict.addEntry("آخر", "يبقى")
    val json = "{\"قديم\":\"مقابل2\", \"جديد\":\"صالح\"}"
    assertTrue(dict.importFromJson(json, merge = true))
    assertEquals(
        mapOf("قديم" to "مقابل2", "آخر" to "يبقى", "جديد" to "صالح"),
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
