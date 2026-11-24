package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsAuthorityIpcTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        repo.unregister()
        SettingsRepository.resetInstanceForTesting()
    }

    @Test
    fun transactionToBundle_explicitNullsRepresentedAsRemovals() {
        val tx = SettingsTransaction(
            clearFirst = false,
            removals = listOf("old_key"),
            puts = mapOf(
                "app_language" to null,
                "preferred_engine" to "vocalizer",
                "speech_rate" to 1.2f,
                "announcement_speech_language" to null
            )
        )

        val bundle = SettingsAuthority.transactionToBundle(tx)
        val parsedTx = SettingsAuthority.bundleToTransaction(bundle)

        // كل المفاتيح المسندة لقيم null يجب أن تُنقل ضمن removals
        assertTrue(parsedTx.removals.contains("old_key"))
        assertTrue(parsedTx.removals.contains("app_language"))
        assertTrue(parsedTx.removals.contains("announcement_speech_language"))

        // المفاتيح غير الـ null تُنقل ضمن puts
        assertEquals("vocalizer", parsedTx.puts["preferred_engine"])
        assertEquals(1.2f, parsedTx.puts["speech_rate"])
        assertFalse(parsedTx.puts.containsKey("app_language"))
    }

    @Test
    fun nullableSetters_clearValuesCorrectly() {
        // تعيين قيم أولاً
        repo.setAppLanguage("ar")
        repo.setAnnouncementSpeechLanguage("en")
        repo.setEmergencyVoice("salma")
        repo.setLanguageForCategory("caller", "ar")

        assertEquals("ar", repo.getAppLanguage())
        assertEquals("en", repo.getAnnouncementSpeechLanguage())
        assertEquals("salma", repo.getEmergencyVoice())
        assertEquals("ar", repo.getLanguageForCategory("caller"))

        // تعيين null للإزالة
        repo.setAppLanguage(null)
        repo.setAnnouncementSpeechLanguage(null)
        repo.setEmergencyVoice(null)
        repo.setLanguageForCategory("caller", null)

        assertNull(repo.getAppLanguage())
        assertNull(repo.getAnnouncementSpeechLanguage())
        assertNull(repo.getEmergencyVoice())
        assertNull(repo.getLanguageForCategory("caller"))
    }

    @Test
    fun snapshotEditor_preservesOperationOrderAndClear() {
        val prefs = repo.prefs
        // تعديل متسلسل: إضافة ثم مسح ثم إضافة
        prefs.edit()
            .putString("k1", "v1")
            .putString("k2", "v2")
            .commit()

        assertEquals("v1", prefs.getString("k1", null))
        assertEquals("v2", prefs.getString("k2", null))

        // إزالة عبر putString(null)
        prefs.edit()
            .putString("k1", null)
            .putString("k3", "v3")
            .commit()

        assertNull(prefs.getString("k1", null))
        assertEquals("v2", prefs.getString("k2", null))
        assertEquals("v3", prefs.getString("k3", null))

        // clear() مع put بعده
        prefs.edit()
            .clear()
            .putString("k_new", "v_new")
            .commit()

        assertNull(prefs.getString("k2", null))
        assertNull(prefs.getString("k3", null))
        assertEquals("v_new", prefs.getString("k_new", null))
    }

    @Test
    fun lateArrivingResult_doesNotOverwriteNewerSnapshot() {
        val prefs = repo.prefs as? SnapshotPrefs
        if (prefs == null) return

        // استبدال لقطة بمراجعة 5
        prefs.replaceSnapshot(mapOf("key" to "rev5_val"), newRevision = 5L)
        assertEquals("rev5_val", prefs.getString("key", null))
        assertEquals(5L, prefs.getRevision())

        // محاولة استبدال بلقطة قديمة بمراجعة 3 (نتيجة متأخرة)
        prefs.replaceSnapshot(mapOf("key" to "rev3_val"), newRevision = 3L)

        // يجب أن ترفض اللقطة القديمة وتبقى قيمة المراجعة 5
        assertEquals("rev5_val", prefs.getString("key", null))
        assertEquals(5L, prefs.getRevision())
    }
}
