package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات هجرة مرآة الإقلاع المباشر Direct Boot mirror:
 * التحقق من حجب جهات الاتصال وهوية المتصل، وهجرة المفاتيح المستبعدة
 * مع الحفاظ التام على مفاتيح الإقلاع الأساسية وتفضيلات المستخدم الأصلية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DirectBootMirrorMigrationTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun isAllowedInBootMirror_whitelistedKeys_returnTrue() {
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("primary_engine")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("preferred_engine")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("app_language")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("secondary_language")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("speech_rate")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("boot_speech_enabled")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("emergency_engine")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("preferred_voice_ar")
        )
        assertTrue(
            SettingsRepository.isAllowedInBootMirror("speech_rate_ar")
        )
    }

    @Test
    fun isAllowedInBootMirror_contactAndCallerKeys_returnFalse() {
        assertFalse(
            SettingsRepository.isAllowedInBootMirror(
                SettingsRepository.KEY_CONTACT_VOICES
            )
        )
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("contact_voice_overrides")
        )
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("contact_12345_voice")
        )
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("caller_name_format")
        )
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("caller_announcement")
        )
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("phone_mode")
        )
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("call_record_mode")
        )
    }

    @Test
    fun isAllowedInBootMirror_arbitraryKeys_returnFalse() {
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("arbitrary_unknown_key")
        )
        assertFalse(
            SettingsRepository.isAllowedInBootMirror("user_password_hash")
        )
    }

    @Test
    fun updateBootMirrorIfNeeded_purgesExcludedKeys_andPreservesAllowed() {
        val repo = SettingsRepository.create(context)
        val deviceContext = context.createDeviceProtectedStorageContext()
        val mirrorPrefs = deviceContext.getSharedPreferences(
            SettingsRepository.BOOT_MIRROR_PREFS,
            Context.MODE_PRIVATE
        )

        // محاكاة وجود مفاتيح مستبعدة ومحذوفة سابقة داخل المرآة
        mirrorPrefs.edit()
            .putString("contact_voices", "{\"0100000000\":\"Laila\"}")
            .putString("caller_name_format", "arabic")
            .putString("primary_engine", "old_engine")
            .commit()

        // تعيين مفاتيح في الإعدادات الرئيسية
        repo.setEmergencyEngine("vocalizer")
        val mainNormalPrefs = context.getSharedPreferences(
            "nateq_settings",
            Context.MODE_PRIVATE
        )
        mainNormalPrefs.edit()
            .putString("primary_engine", "vocalizer")
            .putString(
                SettingsRepository.KEY_CONTACT_VOICES,
                "{\"0100000000\":\"Laila\"}"
            )
            .commit()

        // تشغيل الترحيل
        repo.updateBootMirrorIfNeeded()

        // التحقق من أن المرآة قد حذفت المفاتيح المستبعدة واحتفظت بالمسموح
        assertNull(mirrorPrefs.getString("contact_voices", null))
        assertNull(mirrorPrefs.getString("caller_name_format", null))
        assertEquals(
            "vocalizer",
            mirrorPrefs.getString("primary_engine", null)
        )
        assertEquals(
            "vocalizer",
            mirrorPrefs.getString("emergency_engine", null)
        )

        // التحقق من بقاء التفضيلات الأصلية في المخزن الرئيسي دون مساس
        assertEquals(
            "{\"0100000000\":\"Laila\"}",
            mainNormalPrefs.getString(
                SettingsRepository.KEY_CONTACT_VOICES,
                null
            )
        )
        assertEquals("vocalizer", repo.getEmergencyEngine())
    }
}
