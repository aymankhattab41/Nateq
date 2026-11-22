package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * اختبارات سياسة الخصوصية وحجب البيانات الحساسة:
 * فحص متتبع النصوص المنطوقة LastSpokenTextsTracker وحجب المكالمات
 * والرموز السرية وسقوف الذاكرة وحجم العبارة.
 */
class PrivacyAndRedactionTest {

    @Before
    fun setUp() {
        LastSpokenTextsTracker.clear()
    }

    @Test
    fun callerSource_isExcludedByDefault() {
        LastSpokenTextsTracker.record(
            rawText = "مكالمة واردة من أحمد",
            source = SpeechEntryPoints.Source.CALLER
        )
        assertTrue(LastSpokenTextsTracker.getRecent().isEmpty())
    }

    @Test
    fun sensitiveSourceKeyword_isExcluded() {
        LastSpokenTextsTracker.record(
            rawText = "رمز التحقق 123456",
            source = "OTP"
        )
        LastSpokenTextsTracker.record(
            rawText = "كلمة المرور السرية",
            source = "Password_Manager"
        )
        LastSpokenTextsTracker.record(
            rawText = "اسم المتصل",
            source = "caller_id"
        )
        assertTrue(LastSpokenTextsTracker.getRecent().isEmpty())
    }

    @Test
    fun sensitiveFlag_isExcluded() {
        LastSpokenTextsTracker.record(
            rawText = "نص عادي لكنه حساس",
            source = "ScreenReader",
            isSensitive = true
        )
        assertTrue(LastSpokenTextsTracker.getRecent().isEmpty())
    }

    @Test
    fun normalSpeech_isRecorded() {
        LastSpokenTextsTracker.record(
            rawText = "مرحباً بكم في تطبيق ناطق",
            source = "قارئ الشاشة"
        )
        val recent = LastSpokenTextsTracker.getRecent()
        assertEquals(1, recent.size)
        assertEquals("مرحباً بكم في تطبيق ناطق", recent[0])
    }

    @Test
    fun longText_isBoundedTo256Chars() {
        val longText = "أ".repeat(400)
        LastSpokenTextsTracker.record(
            rawText = longText,
            source = "Test"
        )
        val recent = LastSpokenTextsTracker.getRecent()
        assertEquals(1, recent.size)
        assertEquals(256, recent[0].length)
    }

    @Test
    fun totalMemoryLimit_isEnforced() {
        // إضافة 30 عنصراً بطول 200 حرف = 6000 حرف > الحد الأقصى 4096
        for (i in 1..30) {
            LastSpokenTextsTracker.record(
                rawText = "نص_$i: " + "س".repeat(190),
                source = "Test"
            )
        }
        val entries = LastSpokenTextsTracker.getRecentEntries()
        assertTrue(entries.size <= 20)
        val totalChars = entries.sumOf { it.rawText.length }
        assertTrue(
            "إجمالي الأحرف $totalChars يجب ألا يتجاوز 4096",
            totalChars <= 4096
        )
    }

    @Test
    fun clear_removesAllEntries() {
        LastSpokenTextsTracker.record(rawText = "نص 1")
        LastSpokenTextsTracker.record(rawText = "نص 2")
        assertEquals(2, LastSpokenTextsTracker.getRecent().size)

        LastSpokenTextsTracker.clear()
        assertTrue(LastSpokenTextsTracker.getRecent().isEmpty())
    }

    @Test
    fun formatDisplay_doesNotExposeSecretKeywords() {
        LastSpokenTextsTracker.record(
            rawText = "القائمة الرئيسية",
            source = "System"
        )
        val entry = LastSpokenTextsTracker.getRecentEntries().first()
        val display = entry.formatDisplay()
        assertTrue(display.contains("القائمة الرئيسية"))
        assertFalse(display.contains("password"))
        assertFalse(display.contains("otp"))
    }
}
