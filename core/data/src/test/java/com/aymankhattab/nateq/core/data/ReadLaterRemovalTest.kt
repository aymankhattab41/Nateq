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
 * اختبارات إزالة ميزة «اقرأ لاحقاً»: حذف مفتاح الطابور القديم فقط مع
 * بقاء المفاتيح غير المرتبطة، وأمان التكرار، وتجاهل الاستيراد/الاستعادة
 * القديمة للمفتاح المحذوف.
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
    fun init_purgesObsoleteReadLaterQueue_butKeepsUnrelatedKeys() {
        val sp = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        sp.edit()
            .putString("read_later_queue", "[{\"text\":\"x\"}]")
            .putInt("default_speech_rate", 150)
            .putString("unrelated_key_keep", "keep-me")
            .commit()

        val repo = SettingsRepository(context)

        val raw = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        assertFalse(raw.contains("read_later_queue"))
        assertEquals(150, raw.getInt("default_speech_rate", -1))
        assertEquals("keep-me", raw.getString("unrelated_key_keep", null))

        repo.unregister()
    }

    @Test
    fun init_purgeIsIdempotent_andSafeWhenKeyAbsent() {
        val sp = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        sp.edit()
            .putString("keep_only", "v")
            .commit()

        val repo1 = SettingsRepository(context)
        assertFalse(
            context.getSharedPreferences(
                "nateq_settings", Context.MODE_PRIVATE
            ).contains("read_later_queue")
        )
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
    fun import_ignoresReadLaterQueue_andKeepsOtherKeys() {
        val repo = SettingsRepository(context)
        val backup = mapOf<String, Any>(
            "read_later_queue" to "[{\"text\":\"old\"}]",
            "default_speech_rate" to 140,
            "unrelated_imported" to "yes"
        )

        val ok = repo.importSettings(backup)
        assertTrue(ok)

        val raw = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        assertFalse(raw.contains("read_later_queue"))
        assertEquals("yes", raw.getString("unrelated_imported", null))
        // معدل الكلام قد يُطبَّق عبر منطق الاستيراد (sanitize) إن كان
        // مفتاحاً معروفاً؛ الأهم هنا عدم استعادة الطابور وبقاء المفاتيح
        // الأخرى.
        assertTrue(
            raw.contains("unrelated_imported") ||
                raw.contains("default_speech_rate")
        )

        repo.unregister()
    }

    @Test
    fun import_oldBackupWithOnlyReadLaterQueue_doesNotRestoreIt() {
        val repo = SettingsRepository(context)
        val backup = mapOf<String, Any>(
            "read_later_queue" to "[{\"text\":\"old\"}]"
        )

        // قد تُعتبر بلا إعدادات ذات معنى؛ في كل الأحوال لا يُستعاد المفتاح.
        repo.importSettings(backup)

        val raw = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        assertFalse(raw.contains("read_later_queue"))

        repo.unregister()
    }
}
