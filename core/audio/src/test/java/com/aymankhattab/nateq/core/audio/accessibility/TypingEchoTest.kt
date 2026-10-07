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
}
