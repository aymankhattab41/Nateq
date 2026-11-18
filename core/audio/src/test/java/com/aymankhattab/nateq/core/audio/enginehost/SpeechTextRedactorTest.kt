package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * البند ١٥: نصّ قارئ الشاشة لا يظهر في أي سجل أو تقرير تشخيص يُعرض
 * للمستخدم — فقط طولٌ وبصمة مختصرة تربط السجل بالطلب.
 */
class SpeechTextRedactorTest {

    @Test
    fun describe_doesNotLeakTextAndKeepsLengthAndBytes() {
        val secret = "رقم حسابي ١٢٣٤Because"
        val desc = SpeechTextRedactor.describe(secret)

        assertFalse(desc.contains(secret))
        assertTrue(desc.contains("len=${secret.length}"))
        assertTrue(desc.contains("bytes="))
        assertTrue(desc.contains("sha="))
    }

    @Test
    fun redactQuotedText_replacesOnlyQuotedSpeechText() {
        val line = "nSpeak(text='كلمة سرية', cb=callback)"
        val redacted = SpeechTextRedactor.redactQuotedText(line)

        assertFalse(redacted.contains("كلمة سرية"))
        assertFalse(redacted.contains("text='"))
        assertTrue(redacted.contains("cb=callback"))
        assertTrue(redacted.contains("text=<len="))
    }
}
