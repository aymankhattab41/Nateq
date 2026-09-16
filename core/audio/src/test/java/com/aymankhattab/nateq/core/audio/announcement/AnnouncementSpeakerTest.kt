package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `a flush invalidates every previously active utterance id`() {
        val speaker = AnnouncementSpeaker(context)
        speaker.trackUtterance("nateq_old_1")
        speaker.trackUtterance("nateq_old_2")
        assertTrue(speaker.isActiveUtterance("nateq_old_1"))
        speaker.invalidateActiveUtterances()
        assertFalse(speaker.isActiveUtterance("nateq_old_1"))
        assertFalse(speaker.isActiveUtterance("nateq_old_2"))
        assertFalse(speaker.isActiveUtterance("nateq_never_queued"))
        speaker.shutdown()
    }

    @Test
    fun `stopping invalidates the in-flight utterance id`() {
        val speaker = AnnouncementSpeaker(context)
        speaker.trackUtterance("nateq_inflight")
        speaker.stop()
        assertFalse(speaker.isActiveUtterance("nateq_inflight"))
        speaker.shutdown()
    }

    @Test
    fun `speech rate is clamped to the safe engine range`() {
        assertEquals(0.25f, AnnouncementSpeaker.clampedSpeechRate(0.05f))
        assertEquals(1.0f, AnnouncementSpeaker.clampedSpeechRate(1.0f))
        assertEquals(2.5f, AnnouncementSpeaker.clampedSpeechRate(4f))
    }

    @Test
    fun `mixed Arabic English segments on the same voice are merged into one`() {
        val speaker = AnnouncementSpeaker(context)
        val speakUnitClass = AnnouncementSpeaker::class.java
            .declaredClasses.single { it.simpleName == "SpeakUnit" }
        val ctor = speakUnitClass.declaredConstructors.single()
        ctor.isAccessible = true
        // el locale ici diffère (ar puis en) mais le voiceId est identique
        // (null = voix système unique) => le d⏳lement doit les fusionner en
        // un seul N: un seul speak() continu, sans coupure audible (بند ب 10.3).
        val arabic = ctor.newInstance(
            "السلام عليكم", Locale.forLanguageTag("ar"), 1.0f,
            1.0f, 1.0f, null
        )
        val english = ctor.newInstance(
            "John", Locale.forLanguageTag("en"), 1.0f,
            1.0f, 1.0f, null
        )
        val units = mutableListOf(arabic, english)
        val merge = AnnouncementSpeaker::class.java
            .getDeclaredMethod("mergeAdjacentSameVoice", List::class.java)
        merge.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val merged = merge.invoke(speaker, units) as List<*>

        assertEquals(
            "مقطعان مختلفا اللغة على الصوت الواحد يدمجان في وحدة واحدة",
            1, merged.size
        )
        // النص المدمج يجمع الجملة بمسافة واحدة - نطق متصل بلا فراغ ثانية
        val mergedText = merged[0].javaClass
            .getDeclaredField("text").also { it.isAccessible = true }
            .get(merged[0])
        assertEquals("السلام عليكم John", mergedText)
        speaker.shutdown()
    }

    @Test
    fun `distinct custom voices for each language remain separate`() {
        // بند 17: صوت عربي مخصص + صوت إنجليزي مخصص = مقاطع منفصلة دائماً
        // لأن الدمج يشترط تساوي voiceId. الحفاظ على هذا يضمن أن لكل لغة
        // صوتها حتى في النص المختلط.
        val speaker = AnnouncementSpeaker(context)
        val speakUnitClass = AnnouncementSpeaker::class.java
            .declaredClasses.single { it.simpleName == "SpeakUnit" }
        val ctor = speakUnitClass.declaredConstructors.single()
        ctor.isAccessible = true
        val arabic = ctor.newInstance(
            "السلام عليكم", Locale.forLanguageTag("ar"), 1.0f,
            1.0f, 1.0f, "ar-voice"
        )
        val english = ctor.newInstance(
            "John", Locale.forLanguageTag("en"), 1.0f,
            1.0f, 1.0f, "en-voice"
        )
        val units = mutableListOf(arabic, english)
        val merge = AnnouncementSpeaker::class.java
            .getDeclaredMethod("mergeAdjacentSameVoice", List::class.java)
        merge.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val merged = merge.invoke(speaker, units) as List<*>

        // صوتان مختلفان مخصصان للغتين = لا دمج: كل مقطع يبقى منفصلاً
        assertEquals(
            "صوتان مخصصان مختلفان لا يُدمجان أبداً (بند 17)",
            2, merged.size
        )
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