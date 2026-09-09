package com.aymankhattab.nateq

import com.aymankhattab.nateq.engine.EmojiSpeech
import com.aymankhattab.nateq.engine.SpeechPart
import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات مقسم نطق الإيموجي (EmojiSpeech) — منطق نقي بلا Robolectric. */
class EmojiSpeechSplitterTest {

    private fun names(parts: List<SpeechPart>): List<Pair<String, Boolean>> =
        parts.map { it.text to it.isEmojiName }

    @Test
    fun emptyText_noParts() {
        assertEquals(
            emptyList<Pair<String, Boolean>>(),
            names(EmojiSpeech.split("", true))
        )
        assertEquals(
            emptyList<Pair<String, Boolean>>(),
            names(EmojiSpeech.split("", false))
        )
    }

    @Test
    fun textWithoutEmoji_singlePlainPart() {
        val parts = EmojiSpeech.split("مرحبا بالعالم", true)
        assertEquals(listOf("مرحبا بالعالم" to false), names(parts))
    }

    @Test
    fun arabicEmoji_splitsIntoNamePart() {
        assertEquals(
            listOf("مرحبا " to false, "وجه مبتسم بعينين مبتسمتين" to true),
            names(EmojiSpeech.split("مرحبا 😊", true))
        )
    }

    @Test
    fun englishEmoji_usesEnglishName() {
        assertEquals(
            listOf("hi " to false, "smiling face with smiling eyes" to true),
            names(EmojiSpeech.split("hi 😊", false))
        )
    }

    @Test
    fun mixedEmojis_eachSpeechPartSeparate() {
        assertEquals(
            listOf(
                "نص " to false,
                "وجه مبتسم بعينين مبتسمتين" to true,
                " " to false,
                "قلب أحمر" to true
            ),
            names(EmojiSpeech.split("نص 😊 ❤️", true))
        )
    }

    @Test
    fun variantSelector_keepsBaseEmojiOnly() {
        // ❤️ = U+2764 + U+FE0F (مؤشر أشكال): المقطع هو "قلب أحمر" بلا جزيئات
        assertEquals(
            listOf("قلب أحمر" to true),
            names(EmojiSpeech.split("❤️", true))
        )
    }

    @Test
    fun flagPair_arabic_usesFlagName() {
        val sa = String(Character.toChars(0x1F1F8)) +
            String(Character.toChars(0x1F1E6))
        assertEquals(
            listOf("علم السعودية" to true),
            names(EmojiSpeech.split(sa, true))
        )
    }

    @Test
    fun flagPair_english_usesFlagName() {
        val sa = String(Character.toChars(0x1F1F8)) +
            String(Character.toChars(0x1F1E6))
        assertEquals(
            listOf("flag of Saudi Arabia" to true),
            names(EmojiSpeech.split(sa, false))
        )
    }

    @Test
    fun loneRegionalIndicator_fallsBack() {
        val lone = String(Character.toChars(0x1F1F8))
        assertEquals(
            listOf("إيموجي" to true),
            names(EmojiSpeech.split(lone, true))
        )
        assertEquals(
            listOf("emoji" to true),
            names(EmojiSpeech.split(lone, false))
        )
    }

    @Test
    fun familyEmoji_readsFirstComponentOnly() {
        // 👨👩👧 (رجل + ZWJ + امرأة + ZWJ + طفلة) → يُنطق "رجل" فقط
        val family = String(Character.toChars(0x1F468)) +
            String(Character.toChars(0x200D)) +
            String(Character.toChars(0x1F469)) +
            String(Character.toChars(0x200D)) +
            String(Character.toChars(0x1F467))
        assertEquals(
            listOf("عائلة " to false, "رجل" to true, "!" to false),
            names(EmojiSpeech.split("عائلة $family!", true))
        )
    }

    @Test
    fun unknownEmoji_fallsBack() {
        // ✇ (U+2707) غير مسجّل في القاموس
        val unknown = String(Character.toChars(0x2707))
        assertEquals(
            listOf("إيموجي" to true),
            names(EmojiSpeech.split(unknown, true))
        )
        assertEquals(
            listOf("emoji" to true),
            names(EmojiSpeech.split(unknown, false))
        )
    }

    @Test
    fun asciiEmoticon_expandsToArabicWordInsideTextPart() {
        // ":)" يُستبدل قبل التقسيم بلفظ عربي فلا يُعد مقطع إيموجي
        assertEquals(
            listOf("مرحبا ابتسامة" to false),
            names(EmojiSpeech.split("مرحبا :)", true))
        )
    }
}