package com.aymankhattab.nateq.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.util.VoiceIdContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات تناقض محرك الفئات وصوتها وسرعة الكتالوج (الجولة 53 - الأمر 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VoiceEngineConflictTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        repository = SettingsRepository(context)
    }

    @Test
    fun testSavingVoiceFromAnotherEngine_isRejectedOrCleared() {
        repository.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "com.acapelagroup.android.tts"
        )
        // صوت Tarik يتبع فوكلايزر ولا يتبع أكابيلا
        repository.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "Tarik"
        )
        val voice = repository.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        assertNull("يجب رفض أو مسح الصوت غير التابع للمحرك", voice)
    }

    @Test
    fun testSavingAcapelaVoice_underAcapela_isAccepted() {
        repository.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "com.acapelagroup.android.tts"
        )
        // صوت ara-SAU-Leila يتبع أكابيلا صراحةً ويجب قبوله دون رفض
        repository.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "ara-SAU-Leila"
        )
        val voice = repository.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        assertEquals("ara-SAU-Leila", voice)
    }

    @Test
    fun testChangingEngine_clearsCategorySavedVoice() {
        // نضبط محركاً مدمجاً يقبل صوت فوكلايزر
        repository.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "vocalizer_embedded"
        )
        repository.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "Maged"
        )
        assertEquals(
            "Maged",
            repository.getPreferredVoiceIdForCategory(
                SettingsRepository.VOICE_CATEGORY_NUMBERS
            )
        )

        // نغير المحرك إلى جوجل
        repository.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS,
            "com.google.android.tts"
        )
        val voiceAfterChange = repository.getPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS
        )
        assertNull(
            "تغيير المحرك يجب أن يمسح الصوت المحفوظ للفئة تلقائياً",
            voiceAfterChange
        )
    }

    @Test
    fun testVoiceIdContract_detectsMismatches() {
        // صوت فوكلايزر تحت أكابيلا
        assertFalse(
            VoiceIdContract.isVoiceBelongingToEngine(
                "com.acapelagroup.android.tts",
                "Tarik"
            )
        )
        // صوت فوكلايزر تحت جوجل
        assertFalse(
            VoiceIdContract.isVoiceBelongingToEngine(
                "com.google.android.tts",
                "Tarik"
            )
        )
        // صوت جوجل تحت فوكلايزر المدمج
        assertFalse(
            VoiceIdContract.isVoiceBelongingToEngine(
                "vocalizer_embedded",
                "ar-xa-x-ard-local"
            )
        )
        // صوت أكابيلا تحت أكابيلا
        assertTrue(
            VoiceIdContract.isVoiceBelongingToEngine(
                "com.acapelagroup.android.tts",
                "ara-SAU-Leila"
            )
        )
        // صوت أكابيلا تحت فوكلايزر المدمج
        assertFalse(
            VoiceIdContract.isVoiceBelongingToEngine(
                "vocalizer_embedded",
                "ara-SAU-Leila"
            )
        )
        // صوت فوكلايزر مدمج تحت فوكلايزر المدمج
        assertTrue(
            VoiceIdContract.isVoiceBelongingToEngine(
                "vocalizer_embedded",
                "Laila"
            )
        )
    }

    @Test
    fun testRepairMismatchedSettings() {
        // محاكاة إعداد قديم متناقض في SharedPreferences مباشرة
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("engine_for_numbers", "com.acapelagroup.android.tts")
            .putString("preferred_voice_numbers", "Tarik")
            .commit()

        val engine = repository.getEngineForCategory("numbers")
        val voice = repository.getPreferredVoiceIdForCategory("numbers")
        assertEquals("com.acapelagroup.android.tts", engine)
        assertEquals("Tarik", voice)

        // التحقق من كشف التناقض
        val mismatch = !VoiceIdContract.isVoiceBelongingToEngine(engine, voice)
        assertTrue("يجب كشف التناقض في الإعداد القديم", mismatch)

        // تطبيق الإصلاح بموافقة المستخدم (مسح الصوت غير المطابق)
        repository.setPreferredVoiceIdForCategory("numbers", "")
        assertNull(
            repository.getPreferredVoiceIdForCategory("numbers")
        )
    }
}
