package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadLaterStorageAndBackupTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository
    private lateinit var storageDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        storageDir = File(context.filesDir, "read_later_texts")
        storageDir.deleteRecursively()
        storageDir.mkdirs()
        repo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        repo.unregister()
        SettingsRepository.resetInstanceForTesting()
        storageDir.deleteRecursively()
    }

    @Test
    fun textLongerThan20k_savedAndReadVerbatim() {
        val original = "مرحبا يا ناطق ".repeat(2000) // ~28,000 حرف
        assertTrue(original.length > 20_000)

        val result = repo.saveReadLaterText(original)
        assertEquals(SettingsRepository.ReadLaterSaveResult.SAVED, result)

        val items = repo.getReadLaterItems(loadFullText = false)
        assertEquals(1, items.size)
        assertTrue(items[0].hasFullText)
        assertEquals(
            SettingsRepository.MAX_READ_LATER_TEXT_CHARS,
            items[0].text.length
        )

        val fullText = repo.getReadLaterFullText(items[0].id)
        assertNotNull(fullText)
        assertEquals(original.trim(), fullText)
    }

    @Test
    fun diskWriteFailure_returnsFailed_doesNotCorruptIndex() {
        // جعل مجلد التخزين غير قابل للكتابة لمحاكاة فشل القرص
        val readonlyDir = File(context.filesDir, "read_later_texts")
        readonlyDir.deleteRecursively()
        // إنشاء ملف بنفس الاسم لمنع mkdirs وإنشاء الملفات
        readonlyDir.createNewFile()

        val longText = "أ".repeat(25_000)
        val result = repo.saveReadLaterText(longText)
        assertEquals(SettingsRepository.ReadLaterSaveResult.FAILED, result)

        // الفهرس يجب أن يبقى نظيفاً فارغاً
        val items = repo.getReadLaterItems()
        assertTrue(items.isEmpty())

        readonlyDir.delete()
    }

    @Test
    fun removalAndCapping_updateIndexFirstBeforeDeletingFiles() {
        // إضافة 3 نصوص طويلة
        for (i in 1..3) {
            val txt = "نص طويل رقم $i ".repeat(1500)
            assertEquals(
                SettingsRepository.ReadLaterSaveResult.SAVED,
                repo.saveReadLaterText(txt)
            )
        }

        val items = repo.getReadLaterItems()
        assertEquals(3, items.size)
        val targetId = items[1].id
        val targetFile = File(storageDir, "text_$targetId.txt")
        assertTrue(targetFile.exists())

        // حذف العنصر
        assertTrue(repo.removeReadLaterItem(targetId))
        val remaining = repo.getReadLaterItems()
        assertEquals(2, remaining.size)
        assertFalse(remaining.any { it.id == targetId })

        // انتظار حذف الملف غير المتزامن
        Thread.sleep(100L)
        assertFalse(targetFile.exists())
    }

    @Test
    fun orphanCleanup_deletesOrphansWithoutErasingActiveContent() {
        repo.saveReadLaterText("عنصر نشط أول")
        repo.saveReadLaterText("عنصر نشط ثان طويل ".repeat(2000))
        val activeItems = repo.getReadLaterItems()
        assertEquals(2, activeItems.size)

        // إنشاء ملف يتيم وملف مؤقت
        val orphanFile = File(storageDir, "text_9999999.txt")
        orphanFile.writeText("نص يتيم", Charsets.UTF_8)
        val tmpFile = File(storageDir, "text_8888888.tmp")
        tmpFile.writeText("مؤقت مهجور", Charsets.UTF_8)
        assertTrue(orphanFile.exists())
        assertTrue(tmpFile.exists())

        // تشغيل التنظيف
        repo.cleanReadLaterOrphans()
        Thread.sleep(150L)

        // الملفات اليتيمة والمؤقتة تُحذف
        assertFalse(orphanFile.exists())
        assertFalse(tmpFile.exists())

        // الملفات النشطة تبقى سليمة
        for (item in activeItems) {
            if (item.hasFullText) {
                assertTrue(File(storageDir, "text_${item.id}.txt").exists())
            }
        }
    }

    @Test
    fun clearReadLater_clearsIndexAndFilesSafely() {
        repo.saveReadLaterText("نص طويل للتفريغ ".repeat(2000))
        assertEquals(1, repo.getReadLaterItems().size)

        assertTrue(repo.clearReadLater())
        assertTrue(repo.getReadLaterItems().isEmpty())

        Thread.sleep(100L)
        val files = storageDir.listFiles()?.filter { it.name.endsWith(".txt") }
        assertTrue(files.isNullOrEmpty())
    }

    @Test
    fun duplicateCheck_doesNotReadDiskWhenPreviewsDiffer() {
        val text1 = "نص ألف ".repeat(2000)
        val text2 = "نص باء ".repeat(2000)

        assertEquals(
            SettingsRepository.ReadLaterSaveResult.SAVED,
            repo.saveReadLaterText(text1)
        )
        // نص مختلف بنفس الطول تماماً ولكنه يختلف في المعاينة
        assertEquals(
            SettingsRepository.ReadLaterSaveResult.SAVED,
            repo.saveReadLaterText(text2)
        )

        // نفس النص مكرر
        assertEquals(
            SettingsRepository.ReadLaterSaveResult.DUPLICATE,
            repo.saveReadLaterText(text1)
        )
    }
}
