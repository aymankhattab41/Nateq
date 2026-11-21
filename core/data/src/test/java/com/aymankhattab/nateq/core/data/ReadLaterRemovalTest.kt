package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * اختبارات عقد ميزة «اقرأ لاحقاً»: بقاء البيانات القديمة الفعلية،
 * والحفظ الدائم، ومنع التكرار، والنسخ الاحتياطي/الاستعادة الآمنة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadLaterRemovalTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        SettingsRepository.resetInstanceForTesting()
    }

    @Test
    fun init_keepsExistingReadLaterData_andOtherKeys() {
        val sp = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        val payload =
            "[{\"id\":1,\"text\":\"نص قديم\",\"ts\":100}]"
        sp.edit()
            .putString("read_later_queue", payload)
            .putInt("default_speech_rate", 150)
            .putString("unrelated_key_keep", "keep-me")
            .commit()

        val repo = SettingsRepository(context)

        val raw = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        assertTrue(raw.contains("read_later_queue"))
        val items = repo.getReadLaterItems()
        assertEquals(1, items.size)
        assertEquals("نص قديم", items[0].text)
        assertEquals(150, raw.getInt("default_speech_rate", -1))
        assertEquals("keep-me", raw.getString("unrelated_key_keep", null))

        repo.unregister()
    }

    @Test
    fun init_isIdempotent_whenNoReadLaterKey() {
        val sp = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        sp.edit().putString("keep_only", "v").commit()

        val repo1 = SettingsRepository(context)
        assertTrue(repo1.getReadLaterItems().isEmpty())
        repo1.unregister()

        SettingsRepository.resetInstanceForTesting()
        val repo2 = SettingsRepository(context)
        val raw = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        assertFalse(raw.contains("read_later_queue"))
        assertEquals("v", raw.getString("keep_only", null))
        repo2.unregister()
    }

    @Test
    fun addReadLater_persistsAcrossRepositoryInstances() {
        val repo1 = SettingsRepository(context)
        assertTrue(repo1.addReadLaterText("مرحبا"))
        assertTrue(repo1.addReadLaterText("Hello"))
        repo1.unregister()

        SettingsRepository.resetInstanceForTesting()
        val repo2 = SettingsRepository(context)
        val items = repo2.getReadLaterItems()
        assertEquals(2, items.size)
        // الأحدث يُضاف في المقدمة
        assertEquals("Hello", items[0].text)
        assertEquals("مرحبا", items[1].text)
        repo2.unregister()
    }

    @Test
    fun addReadLater_preventsDuplicateSameText() {
        val repo = SettingsRepository(context)
        assertTrue(repo.addReadLaterText("نفس النص"))
        val countAfterFirst = repo.getReadLaterItems().size
        assertTrue(repo.addReadLaterText("نفس النص"))
        assertEquals(countAfterFirst, repo.getReadLaterItems().size)
        repo.unregister()
    }

    @Test
    fun removeAndClear_workAsExpected() {
        val repo = SettingsRepository(context)
        repo.addReadLaterText("واحد")
        repo.addReadLaterText("اثنان")
        val items = repo.getReadLaterItems()
        assertEquals(2, items.size)
        val removedId = items[0].id
        assertTrue(repo.removeReadLaterItem(removedId))
        assertEquals(1, repo.getReadLaterItems().size)

        assertTrue(repo.clearReadLater())
        assertTrue(repo.getReadLaterItems().isEmpty())
        repo.unregister()
    }

    @Test
    fun exportImport_preservesReadLaterItems() {
        val repo1 = SettingsRepository(context)
        repo1.addReadLaterText("عنصر محفوظ")
        val exported = repo1.exportSettings()
        assertTrue(exported.containsKey("read_later_queue"))
        repo1.unregister()

        SettingsRepository.resetInstanceForTesting()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val repo2 = SettingsRepository(context)
        assertTrue(repo2.importSettings(exported))
        val restored = repo2.getReadLaterItems()
        assertEquals(1, restored.size)
        assertEquals("عنصر محفوظ", restored[0].text)
        repo2.unregister()
    }

    @Test
    fun import_oldBackupWithOnlyReadLaterQueue_restoresIfValid() {
        val repo = SettingsRepository(context)
        val backup = mapOf<String, Any>(
            "read_later_queue" to
                "[{\"id\":5,\"text\":\"قديم\",\"ts\":50}]"
        )

        repo.importSettings(backup)

        val items = repo.getReadLaterItems()
        assertEquals(1, items.size)
        assertEquals("قديم", items[0].text)
        repo.unregister()
    }

    @Test
    fun import_ignoresMalformedReadLaterQueue_withoutFakeItems() {
        val repo = SettingsRepository(context)
        val backup = mapOf<String, Any>(
            "read_later_queue" to "not-json"
        )
        repo.importSettings(backup)
        assertTrue(repo.getReadLaterItems().isEmpty())
        repo.unregister()
    }

    @Test
    fun saveReadLaterText_returnsCorrectStatusForEachCase() {
        val repo = SettingsRepository(context)

        assertEquals(
            SettingsRepository.ReadLaterSaveResult.EMPTY,
            repo.saveReadLaterText("   ")
        )
        assertEquals(
            SettingsRepository.ReadLaterSaveResult.SAVED,
            repo.saveReadLaterText("نص فريد جديد")
        )
        assertEquals(
            SettingsRepository.ReadLaterSaveResult.DUPLICATE,
            repo.saveReadLaterText("نص فريد جديد")
        )

        repo.unregister()
    }
}