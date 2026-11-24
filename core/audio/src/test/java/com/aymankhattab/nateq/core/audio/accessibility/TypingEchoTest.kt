package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypingEchoTest {

    @Test
    fun `getEchoText returns character or word according to mode`() {
        assertNull(
            TypingEcho.getEchoText(
                "abc",
                "abcd",
                TypingEcho.EchoMode.OFF
            )
        )

        assertEquals(
            "d",
            TypingEcho.getEchoText(
                "abc",
                "abcd",
                TypingEcho.EchoMode.CHARACTERS
            )
        )

        assertNull(
            TypingEcho.getEchoText(
                "hello",
                "helloo",
                TypingEcho.EchoMode.WORDS
            )
        )

        assertEquals(
            "hello",
            TypingEcho.getEchoText(
                "hello",
                "hello ",
                TypingEcho.EchoMode.WORDS
            )
        )
    }

    @Test
    fun `insertionAtStart_extractsCorrectChar`() {
        // البند 14: إدراج في البداية
        assertEquals(
            "X",
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "Xabcd",
                mode = TypingEcho.EchoMode.CHARACTERS,
                fromIndex = 0,
                addedCount = 1,
                removedCount = 0
            )
        )
    }

    @Test
    fun `insertionAtMiddle_extractsCorrectChar`() {
        // البند 14: إدراج في الوسط
        assertEquals(
            "X",
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "abXcd",
                mode = TypingEcho.EchoMode.CHARACTERS,
                fromIndex = 2,
                addedCount = 1,
                removedCount = 0
            )
        )
    }

    @Test
    fun `insertionAtEnd_extractsCorrectChar`() {
        // البند 14: إدراج في النهاية
        assertEquals(
            "X",
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "abcdX",
                mode = TypingEcho.EchoMode.CHARACTERS,
                fromIndex = 4,
                addedCount = 1,
                removedCount = 0
            )
        )
    }

    @Test
    fun `replacementSameLength_extractsReplacedChar`() {
        // البند 14: استبدال نص بنفس الطول (استبدال c بـ Z)
        assertEquals(
            "Z",
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "abZcd",
                mode = TypingEcho.EchoMode.CHARACTERS,
                fromIndex = 2,
                addedCount = 1,
                removedCount = 1
            )
        )
    }

    @Test
    fun `deletion_returnsNull`() {
        // البند 14: الحذف لا ينطق حرفاً مضافاً
        assertNull(
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "acd",
                mode = TypingEcho.EchoMode.CHARACTERS,
                fromIndex = 1,
                addedCount = 0,
                removedCount = 1
            )
        )
    }

    @Test
    fun `pasting_multiChars_extractsAllAdded`() {
        // البند 14: لصق عدة محارف دفعة واحدة
        assertEquals(
            " WORLD",
            TypingEcho.getEchoText(
                oldText = "hello",
                newText = "hello WORLD",
                mode = TypingEcho.EchoMode.CHARACTERS,
                fromIndex = 5,
                addedCount = 6,
                removedCount = 0
            )
        )
    }

    @Test
    fun `arabicDiacritics_extractsFatha`() {
        // البند 14: تشكيل الحرف العربي
        assertEquals(
            "َ",
            TypingEcho.getEchoText(
                oldText = "كتب",
                newText = "كَتَبَ",
                mode = TypingEcho.EchoMode.CHARACTERS,
                fromIndex = 1,
                addedCount = 1,
                removedCount = 0
            )
        )
    }

    @Test
    fun `diffFallback_withoutIndices_handlesAllPositions`() {
        // البند 14: المسار الاحتياطي بمطابقة البادئة واللاحقة المشتركة
        assertEquals(
            "X",
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "Xabcd",
                mode = TypingEcho.EchoMode.CHARACTERS
            )
        )
        assertEquals(
            "X",
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "abXcd",
                mode = TypingEcho.EchoMode.CHARACTERS
            )
        )
        assertEquals(
            "Z",
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "abZcd",
                mode = TypingEcho.EchoMode.CHARACTERS
            )
        )
        assertNull(
            TypingEcho.getEchoText(
                oldText = "abcd",
                newText = "acd",
                mode = TypingEcho.EchoMode.CHARACTERS
            )
        )
    }

    @Test
    fun `wordsMode_withMiddleInsertion_returnsCompletedWord`() {
        assertEquals(
            "two",
            TypingEcho.getEchoText(
                oldText = "one three",
                newText = "one two three",
                mode = TypingEcho.EchoMode.WORDS,
                fromIndex = 4,
                addedCount = 4,
                removedCount = 0
            )
        )
    }

    @Test
    fun `bothMode_charactersAndWords`() {
        assertEquals(
            "a",
            TypingEcho.getEchoText(
                oldText = "hello",
                newText = "helloa",
                mode = TypingEcho.EchoMode.BOTH,
                fromIndex = 5,
                addedCount = 1,
                removedCount = 0
            )
        )
        assertEquals(
            "hello",
            TypingEcho.getEchoText(
                oldText = "hello",
                newText = "hello ",
                mode = TypingEcho.EchoMode.BOTH,
                fromIndex = 5,
                addedCount = 1,
                removedCount = 0
            )
        )
    }
}
