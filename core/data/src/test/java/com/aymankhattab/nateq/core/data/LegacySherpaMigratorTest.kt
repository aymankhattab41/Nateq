package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.ConvertPreferencesCodec
import com.aymankhattab.nateq.engine.LanguageSpeechPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * اختبارات هجرة وحذف بيانات محرك Sherpa-ONNX / Piper القديمة (ع٤٠-7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacySherpaMigratorTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val sp = context.getSharedPreferences(
            "nateq_settings",
            Context.MODE_PRIVATE
        )
        sp.edit().clear().commit()
        settings = SettingsRepository(context)
    }

    @Test
    fun cleanupLegacyFiles_removesFoldersAndSherpaFiles() {
        val filesDir = context.filesDir
        val sherpaDir = File(filesDir, "SherpaTTS").apply { mkdirs() }
        File(sherpaDir, "model.onnx").writeBytes(ByteArray(10))

        val piperDir = File(filesDir, "Piper").apply { mkdirs() }
        File(piperDir, "test.onnx").writeBytes(ByteArray(10))

        val espeakDir = File(filesDir, "espeak-ng-data").apply { mkdirs() }
        File(espeakDir, "phondata").writeBytes(ByteArray(10))

        val statusFile = File(filesDir, "engine_status_sherpa_onnx.json")
            .apply { writeText("{}") }
        val logFile = File(filesDir, "hostlog_sherpa.txt")
            .apply { writeText("log") }

        val downloadsDir = File(filesDir, "downloads").apply { mkdirs() }
        val piperDownload = File(downloadsDir, "ar_kareem_piper.onnx.part")
            .apply { writeBytes(ByteArray(20)) }
        val keepDownload = File(downloadsDir, "vocalizer_laila.hdr")
            .apply { writeBytes(ByteArray(20)) }

        val migrator = LegacySherpaMigrator(context, settings)
        val migrated = migrator.migrateIfNeeded(showToast = false)

        assertTrue(migrated)
        assertFalse("مجلد SherpaTTS حُذف", sherpaDir.exists())
        assertFalse("مجلد Piper حُذف", piperDir.exists())
        assertFalse("مجلد espeak-ng-data حُذف", espeakDir.exists())
        assertFalse("ملف الحالة حُذف", statusFile.exists())
        assertFalse("ملف السجل حُذف", logFile.exists())
        assertFalse("تنزيل piper الجزئي حُذف", piperDownload.exists())
        assertTrue("ملف التنزيل الآخر بقي", keepDownload.exists())
    }

    @Test
    fun categoryEngines_resetToNullWhenPointingToSherpa() {
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_TIME,
            "sherpa_onnx_embedded"
        )
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY,
            "vocalizer_embedded"
        )
        settings.setEngineForCategory(
            SettingsRepository.ANNOUNCE_CATEGORY_SMS,
            "sherpa_piper"
        )
        settings.setEngineForCategory(
            SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
            "piper"
        )
        settings.setAnnouncementFallbackEngine("sherpa_onnx_piper")

        val migrator = LegacySherpaMigrator(context, settings)
        migrator.migrateIfNeeded(showToast = false)

        assertNull(
            "محرك فئة الوقت أُعيد إلى الافتراضي",
            settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_TIME
            )
        )
        assertEquals(
            "محرك فئة البطارية لم يتغير",
            "vocalizer_embedded",
            settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_BATTERY
            )
        )
        assertNull(
            "محرك فئة الرسائل أُعيد إلى الافتراضي",
            settings.getEngineForCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_SMS
            )
        )
        assertNull(
            "محرك فئة المتصل أُعيد إلى الافتراضي",
            settings.getEngineForCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR
            )
        )
        assertNull(
            "المحرك الاحتياطي أُعيد إلى الافتراضي",
            settings.getAnnouncementFallbackEngine()
        )
    }

    @Test
    fun convertPreferences_resetsSherpaEnginesToNull() {
        val sp = context.getSharedPreferences(
            "nateq_settings",
            Context.MODE_PRIVATE
        )
        val initialMap = mapOf(
            "ar" to LanguageSpeechPrefs(
                engine = "sherpa_onnx_embedded",
                voiceName = "ar_voice"
            ),
            "en" to LanguageSpeechPrefs(
                engine = "vocalizer_embedded",
                voiceName = "en_voice"
            )
        )
        sp.edit().putString(
            "convert_language_prefs",
            ConvertPreferencesCodec.toJson(initialMap)
        ).commit()

        settings.setConvertEngine1("sherpa_onnx_piper")
        settings.setConvertEngine2("vocalizer_embedded")

        val migrator = LegacySherpaMigrator(context, settings)
        migrator.migrateIfNeeded(showToast = false)

        val updatedMap = settings.allConvertLanguagePreferences()
        assertNull(
            "محرك العربية في التحويل أُعيد إلى null",
            updatedMap["ar"]?.engine
        )
        assertEquals(
            "محرك الإنجليزية في التحويل لم يتغير",
            "vocalizer_embedded",
            updatedMap["en"]?.engine
        )
        assertNull(
            "محرك السلوت 1 أُعيد إلى null",
            settings.getConvertEngine1()
        )
        assertEquals(
            "محرك السلوت 2 بقي دون تغيير",
            "vocalizer_embedded",
            settings.getConvertEngine2()
        )
    }

    @Test
    fun deadPrefKeys_areRemovedAndMigrationFlagIsSet() {
        val sp = context.getSharedPreferences(
            "nateq_settings",
            Context.MODE_PRIVATE
        )
        sp.edit()
            .putBoolean("embedded_storage_migrated_SHERPA", true)
            .putBoolean("embedded_storage_migrated_PIPER", true)
            .commit()

        val migrator = LegacySherpaMigrator(context, settings)
        val firstRun = migrator.migrateIfNeeded(showToast = false)

        assertTrue(firstRun)
        assertTrue(
            "تم وضع راية اكتمال الهجرة",
            sp.getBoolean(LegacySherpaMigrator.KEY_MIGRATED, false)
        )
        assertFalse(
            "مفتاح التفضيلات الميت SHERPA أُزيل",
            sp.contains("embedded_storage_migrated_SHERPA")
        )
        assertFalse(
            "مفتاح التفضيلات الميت PIPER أُزيل",
            sp.contains("embedded_storage_migrated_PIPER")
        )

        // محاولة التشغيل مرة ثانية تعود بـ false دون إعادة التنفيذ
        val secondRun = migrator.migrateIfNeeded(showToast = false)
        assertFalse("لا يُعاد التنفيذ بعد اكتمال الهجرة", secondRun)
    }
}
