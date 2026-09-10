package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * يغطي إصلاحات المتحدث المشترك:
 * - معرّف نطق فريد لكل جزء حتى داخل نفس المللي ثانية (بند [3]) — تكرار
 *   الزمن وحده كان يفلتر أجزاءً من الإعلان بفعل onDone مبكر.
 * - قائمة مستمعي الاكتمال (بند [8]): تُستدعى كلها عند الاكتمال، والإزالة
 *   لا تمسّ غيرها، وخطأ أحد المستمعين لا يُسقط البقية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnnouncementSpeakerTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun `utterance ids stay unique within the same millisecond`() {
        val ids = (1..1000).map { AnnouncementSpeaker.nextUtteranceId() }
        assertEquals("معرّفات النطق فريدة دوماً", 1000, ids.toSet().size)
    }

    @Test
    fun `completion listeners are all invoked and independently removed`() {
        val speaker = AnnouncementSpeaker(context)
        var firstCalls = 0
        var secondCalls = 0
        val first = { firstCalls += 1 }
        val second = { secondCalls += 1 }
        speaker.addCompletionListener(first)
        speaker.addCompletionListener(second)

        notifyCompletion(speaker)
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)

        speaker.removeCompletionListener(first)
        notifyCompletion(speaker)
        assertEquals(
            "إزالة أحد المستمعين لا تنهي الآخرين", 1, firstCalls
        )
        assertEquals(2, secondCalls)
        speaker.shutdown()
    }

    @Test
    fun `listener exceptions do not break other listeners`() {
        val speaker = AnnouncementSpeaker(context)
        var calls = 0
        speaker.addCompletionListener { error("فشلٌ مقصود في اختبار") }
        speaker.addCompletionListener { calls += 1 }
        notifyCompletion(speaker)
        assertEquals(1, calls)
        speaker.shutdown()
    }

    /** استدعاء الاستدعاء الخاص للاكتمال (لا محرك TTS حقيقي في الاختبار). */
    private fun notifyCompletion(speaker: AnnouncementSpeaker) {
        val method = AnnouncementSpeaker::class.java
            .getDeclaredMethod("notifySpeechComplete")
        method.isAccessible = true
        method.invoke(speaker)
    }
}