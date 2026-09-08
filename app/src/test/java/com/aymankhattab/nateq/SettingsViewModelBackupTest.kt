package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات النسخ الاحتياطي في SettingsViewModel: بناء JSON واستعادته
 * مع كل الأنواع (عداد/عشري/منطقي/نص/مجموعة/قاموس/أسماء متصلين)،
 * والحدود الدفاعية (حجم/عدد/إصدار/بنية).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelBackupTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var dict: PronunciationDictionary
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
        dict = PronunciationDictionary(context)
        vm = SettingsViewModel(settings, dict)
        // تنظيف نظيف قبل كل اختبار
        context.getSharedPreferences("nateq_settings", 0).edit().clear().commit()
    }

    // ===== الدورة الكاملة: بناء + استعادة =====

    @Test
    fun roundTrip_restoresAllTypes() {
        // تعبئة الإعدادات بأنواع مختلفة
        settings.setTimeAnnouncementInterval(15)
        settings.setTimeAnnouncementFormat("digital")
        settings.setTime24Hour(true)
        settings.setHijriDateEnabled(true)
        settings.setDefaultSpeechRate(1.35f)
        settings.setPowerSaverModeEnabled(true)
        settings.setPowerSaverBatteryThreshold(35)
        settings.setNumberReadingMode(3)

        // إدخالات القاموس
        dict.addEntry("ص", "صفحة")
        dict.addEntry("د.", "دكتور")

        // أسماء متصلين
        settings.setCustomCallerNames(mapOf("0123456789" to "أحمد", "9876543210" to "سارة"))

        // بناء النسخة الاحتياطية
        val json = vm.buildBackupJson()
        assertTrue("JSON غير فارغ", json.isNotEmpty())

        // مسح كل شيء وإنشاء مثيلات جديدة
        context.getSharedPreferences("nateq_settings", 0).edit().clear().commit()
        val freshSettings = SettingsRepository(context)
        val freshDict = PronunciationDictionary(context)
        val freshVm = SettingsViewModel(freshSettings, freshDict)

        assertTrue("استعادة ناجحة", freshVm.applyBackupJson(json))

        // التحقق من كل نوع
        assertEquals(15, freshSettings.getTimeAnnouncementInterval())
        assertEquals("digital", freshSettings.getTimeAnnouncementFormat())
        assertTrue(freshSettings.isTime24Hour())
        assertTrue(freshSettings.isHijriDateEnabled())
        assertEquals(1.35f, freshSettings.getDefaultSpeechRate(), 0.001f)
        assertTrue(freshSettings.isPowerSaverModeEnabled())
        assertEquals(35, freshSettings.getPowerSaverBatteryThreshold())
        assertEquals(3, freshSettings.getNumberReadingMode())

        // القاموس
        val entries = freshDict.getAllEntries()
        assertEquals(2, entries.size)
        assertEquals("صفحة", entries["ص"])
        assertEquals("دكتور", entries["د."])

        // أسماء المتصلين
        assertEquals(
            mapOf("0123456789" to "أحمد", "9876543210" to "سارة"),
            freshSettings.getCustomCallerNames()
        )
    }

    @Test
    fun roundTrip_incrementsRevision() {
        // نسخة فارغة لا تُعدّل شيئاً فتُعيد false دون زيادة مراجعة
        val empty = vm.buildBackupJson()
        assertEquals(0, vm.settingsRevision.value)
        assertFalse(vm.applyBackupJson(empty))
        assertEquals(0, vm.settingsRevision.value)

        // نسخة تحوي إعداداً واحداً تُطبَّق فترفع المراجعة في كل مرة
        settings.setTimeAnnouncementInterval(15)
        val json = vm.buildBackupJson()
        assertTrue(vm.applyBackupJson(json))
        assertEquals(1, vm.settingsRevision.value)
        assertTrue(vm.applyBackupJson(json))
        assertEquals(2, vm.settingsRevision.value)
    }

    // ===== الإصدار =====

    @Test
    fun invalidVersion2_returnsFalse() {
        assertFalse(vm.applyBackupJson("""{"version":2,"settings":{}}"""))
    }

    @Test
    fun missingVersion_returnsFalse() {
        assertFalse(vm.applyBackupJson("""{"settings":{}}"""))
    }

    // ===== البنية الفاسدة =====

    @Test
    fun malformedJson_returnsFalse() {
        assertFalse(vm.applyBackupJson("not json at all"))
    }

    @Test
    fun emptyJson_returnsFalse() {
        assertFalse(vm.applyBackupJson("{}"))
    }

    // ===== الحد الأقصى للحجم (2 ميغابايت) =====

    @Test
    fun oversized_returnsFalse() {
        val huge = """{"version":1,"settings":{},"callerNames":{},"dictionary":[""" +
            (1..200_000).joinToString(",") { """["$it","value$it"]""" } +
            "]}]"
        assertFalse(vm.applyBackupJson(huge))
    }

    // ===== الحد الأقصى للعناصر (5000) =====

    @Test
    fun tooManyCallerNames_returnsFalse() {
        val callerObj = buildString {
            append("{")
            for (i in 0 until 5001) {
                if (i > 0) append(",")
                append("\"$i\":\"name$i\"")
            }
            append("}")
        }
        val json = """{"version":1,"settings":{},"callerNames":$callerObj,"dictionary":[]}"""
        assertFalse(vm.applyBackupJson(json))
    }

    @Test
    fun tooManySettings_returnsFalse() {
        val settingsObj = buildString {
            append("{")
            for (i in 0 until 5001) {
                if (i > 0) append(",")
                append("\"key$i\":{\"type\":\"string\",\"value\":\"val$i\"}")
            }
            append("}")
        }
        val json = """{"version":1,"settings":$settingsObj,"callerNames":{},"dictionary":[]}"""
        assertFalse(vm.applyBackupJson(json))
    }

    // ===== تجاوز حدود النوع =====

    @Test
    fun intOutOfRange_skipped() {
        val json = """{"version":1,"settings":{
            "time_announcement_interval":{"type":"int","value":99999999999},
            "number_reading_mode":{"type":"int","value":4}
        },"callerNames":{},"dictionary":[]}"""
        assertTrue("العنصر الصالح يُعيد true", vm.applyBackupJson(json))
        // القيد خارج المدى يُتخطى → يبقى الافتراضي
        assertEquals(30, settings.getTimeAnnouncementInterval())
        assertEquals(4, settings.getNumberReadingMode())
    }

    @Test
    fun float_withinRange_restored() {
        val json = """{"version":1,"settings":{
            "default_speech_rate":{"type":"float","value":1.75}
        },"callerNames":{},"dictionary":[]}"""
        assertTrue(vm.applyBackupJson(json))
        assertEquals(1.75f, settings.getDefaultSpeechRate(), 0.001f)
    }

    // ===== القاموس في النسخ الاحتياطي =====

    @Test
    fun roundTrip_dictionary() {
        dict.addEntry("HTTP", "إتش تي تي بي")
        dict.addEntry("API", "إيه بي آي")

        val json = vm.buildBackupJson()

        context.getSharedPreferences("nateq_settings", 0).edit().clear().commit()
        val freshDict = PronunciationDictionary(context)
        val freshVm = SettingsViewModel(SettingsRepository(context), freshDict)
        assertTrue(freshVm.applyBackupJson(json))

        val entries = freshDict.getAllEntries()
        assertEquals(2, entries.size)
        assertEquals("إتش تي تي بي", entries["HTTP"])
        assertEquals("إيه بي آي", entries["API"])
    }

    // ===== أسماء المتصلين فقط =====

    @Test
    fun roundTrip_callerNamesOnly() {
        settings.setCustomCallerNames(mapOf("0555" to "أحمد"))

        val json = vm.buildBackupJson()

        context.getSharedPreferences("nateq_settings", 0).edit().clear().commit()
        val freshSettings = SettingsRepository(context)
        val freshVm = SettingsViewModel(freshSettings, PronunciationDictionary(context))
        assertTrue(freshVm.applyBackupJson(json))

        assertEquals(mapOf("0555" to "أحمد"), freshSettings.getCustomCallerNames())
    }

    // ===== نسخة فارغة (بدون أي بيانات) =====

    @Test
    fun backupJson_noData_applyReturnsFalse() {
        val json = vm.buildBackupJson()
        // JSON يحتوي على version وexportedAt فقط — لا إعدادات/قاموس/أسماء
        assertTrue(json.contains("\"version\":1"))
        // تطبيق نسخة فارغة يُعيد false (لا تعديلات مmeaningful)
        context.getSharedPreferences("nateq_settings", 0).edit().clear().commit()
        val freshVm = SettingsViewModel(SettingsRepository(context), PronunciationDictionary(context))
        assertFalse("نسخة فارغة لا تُعدّل شيئاً", freshVm.applyBackupJson(json))
    }
}