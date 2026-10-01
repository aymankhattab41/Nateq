package com.aymankhattab.nateq.core.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات مقسّم مقاطع النطق — ضمانة عدم تجاوز الحدّ وعدم فقد حرف. */
class SpeechChunkerTest {

    @Test
    fun `short text stays single chunk`() {
        assertEquals(
            listOf("نص قصير"),
            SpeechChunker.split("نص قصير")
        )
    }

    @Test
    fun `long text splits under the cap`() {
        val words = (1..120).joinToString(" ") { "كلمة$it" }
        val chunks = SpeechChunker.split(words)
        assertTrue("ينقسم النص الطويل", chunks.size > 1)
        assertTrue(
            "كل مقطع ضمن السقف",
            chunks.all { it.length <= SpeechChunker.MAX_CHARS }
        )
    }

    @Test
    fun `chunks keep every word in order`() {
        val words = (1..120).joinToString(" ") { "كلمة$it" }
        val rejoined = SpeechChunker.split(words).joinToString(" ")
        assertEquals("لا يُفقد حرف ولا ترتيب", words, rejoined)
    }

    @Test
    fun `cut prefers sentence delimiters over later spaces`() {
        // علامة الجملة (الموضع 59) تسبق المسافة (الموضع 61) داخل النافذة
        // فيُقطع عند علامة الجملة لا عند أوّل مسافة بعدها.
        val text = "أ".repeat(60) + "." + " " + "ب".repeat(80)
        val chunks = SpeechChunker.split(text, 100)
        assertTrue("ينقسم عند الحدّ", chunks.size >= 2)
        assertTrue(
            "أول مقطع ينتهي عند علامة الجملة",
            chunks[0].endsWith(".")
        )
    }

    @Test
    fun `text without spaces is cut hard but keeps all characters`() {
        val text = "أ".repeat(650)
        val chunks = SpeechChunker.split(text)
        assertEquals("لا يُفقد حرف", text, chunks.joinToString(""))
        assertTrue(
            "كل مقطع ضمن السقف",
            chunks.all { it.length <= SpeechChunker.MAX_CHARS }
        )
    }

    @Test
    fun `newline ends a chunk`() {
        val text = "أ".repeat(45) + "\n" + "ب".repeat(60)
        val chunks = SpeechChunker.split(text, 60)
        assertTrue("ينقسم عند السطر", chunks.size >= 2)
        assertTrue("أول مقطع بلا سطر جديد", !chunks[0].contains("\n"))
    }

    @Test
    fun `custom cap is honoured`() {
        val text = (1..60).joinToString(" ") { "كلمة$it" }
        val chunks = SpeechChunker.split(text, 50)
        assertTrue(
            "كل مقطع ضمن الحد المخصص",
            chunks.all { it.length <= 50 }
        )
    }

    @Test
    fun `blank-ish text is returned unchanged`() {
        assertEquals(listOf("   "), SpeechChunker.split("   "))
    }
}