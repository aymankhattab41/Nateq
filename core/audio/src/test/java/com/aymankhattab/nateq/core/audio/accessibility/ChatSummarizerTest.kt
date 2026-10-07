package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSummarizerTest {

    @Test
    fun `summarize handles different sender counts accurately`() {
        assertEquals("", ChatSummarizer.summarize(emptyList(), 0))
        assertEquals(
            "5 رسائل جديدة",
            ChatSummarizer.summarize(emptyList(), 5)
        )
        assertEquals(
            "3 رسائل جديدة من أحمد",
            ChatSummarizer.summarize(listOf("أحمد"), 3)
        )
        assertEquals(
            "4 رسائل من أحمد و سارة",
            ChatSummarizer.summarize(listOf("أحمد", "سارة"), 4)
        )
        assertEquals(
            "6 رسائل من أحمد، سارة و 2 آخرين",
            ChatSummarizer.summarize(listOf("أحمد", "سارة", "علي", "منى"), 6)
        )
    }
}
