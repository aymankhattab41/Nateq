package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات ترقية «اقرأ لاحقاً»:
 * 1. عدم القطع الصامت للنصوص الطويلة وحفظها كاملة في تخزين مناسب.
 * 2. سياسة القطع مع الإشعار الصريح SAVED_TRUNCATED.
 * 3. المقارنة الموحدة canonicalization لمنع التكرار الزائف.
 * 4. توليد معرفات فريدة غير متصادمة لمنع الحذف العرضي عند تطابق الوقت.
 * 5. ترحيل المعرفات القديمة وحفظ الترتيب وحد العدد (50).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadLaterDelegateTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val storageDir = File(context.filesDir, "read_later_texts")
        storageDir.deleteRecursively()
        repo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        repo.unregister()
        SettingsRepository.resetInstanceForTesting()
        val storageDir = File(context.filesDir, "read_later_texts")
        storageDir.deleteRecursively()
    }

    @Test
    fun canonicalization_detectsDuplicatesWithWhitespaceAndBidi() {
        val baseText = "نص للاختبار مع فواصل"
        val withBidi = "\u200Eنص للاختبار مع فواصل\u200F  \r\n"

        assertEquals(
            SettingsRepository.ReadLaterSaveResult.SAVED,
            repo.saveReadLaterText(baseText)
        )
        // يجب أن يكتشف أنه مكرر رغم وجود محارف bidi وفواصل أسطر مختلفة
        assertEquals(
            SettingsRepository.ReadLaterSaveResult.DUPLICATE,
            repo.saveReadLaterText(withBidi)
        )
    }

    @Test
    fun longText_savedFullyUnderSaveFullPolicy() {
        repo.setReadLaterLongTextPolicy(
            SettingsRepository.READ_LATER_POLICY_SAVE_FULL
        )
        // نص طويل يتجاوز سقف 20,000 حرف
        val veryLongText = "أ".repeat(25_000)

        val result = repo.saveReadLaterText(veryLongText)
        assertEquals(SettingsRepository.ReadLaterSaveResult.SAVED, result)

        val items = repo.getReadLaterItems()
        assertEquals(1, items.size)
        // يجب استعادة النص كاملاً دون أي بتر صامت
        assertEquals(25_000, items[0].text.length)
        assertEquals(veryLongText, items[0].text)
    }

    @Test
    fun longText_truncatedWithNoticeUnderTruncatePolicy() {
        repo.setReadLaterLongTextPolicy(
            SettingsRepository.READ_LATER_POLICY_TRUNCATE
        )
        val veryLongText = "ب".repeat(25_000)

        val result = repo.saveReadLaterText(veryLongText)
        assertEquals(
            SettingsRepository.ReadLaterSaveResult.SAVED_TRUNCATED,
            result
        )

        val items = repo.getReadLaterItems()
        assertEquals(1, items.size)
        assertEquals(20_000, items[0].text.length)
    }

    @Test
    fun collisionFreeIds_preventAccidentalDeletionOfSameTimestampItems() {
        // محاكاة عنصرين محفوظين بنفس الطابع الزمني في النسخة القديمة
        val sp = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        val payload = "[" +
            "{\"id\":1000,\"text\":\"العنصر الأول\",\"ts\":12345}," +
            "{\"id\":1000,\"text\":\"العنصر الثاني\",\"ts\":12345}" +
            "]"
        sp.edit().putString(SettingsRepository.KEY_READ_LATER, payload).commit()

        val freshRepo = SettingsRepository(context)
        val items = freshRepo.getReadLaterItems()
        assertEquals(2, items.size)

        // يجب أن يتم ترحيلهما بمعرفين فريدين مختلفين
        val id1 = items[0].id
        val id2 = items[1].id
        assertNotEquals(id1, id2)

        // حذف العنصر الأول يجب ألا يحذف العنصر الثاني
        assertTrue(freshRepo.removeReadLaterItem(id1))
        val remaining = freshRepo.getReadLaterItems()
        assertEquals(1, remaining.size)
        assertEquals(id2, remaining[0].id)
        assertEquals("العنصر الثاني", remaining[0].text)

        freshRepo.unregister()
    }

    @Test
    fun orderingAndCapping_maintainedAccurately() {
        for (i in 1..60) {
            repo.addReadLaterText("رسالة رقم $i")
        }
        val items = repo.getReadLaterItems()
        // السقف 50 عنصراً
        assertEquals(50, items.size)
        // الأحدث يظهر في المقدمة
        assertEquals("رسالة رقم 60", items[0].text)
        assertEquals("رسالة رقم 11", items[49].text)
    }
}
