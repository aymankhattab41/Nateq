package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.speech.tts.Voice
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
    fun `lone English word stays separate from Arabic to keep English voice`() {
        // بند 18: كلمة إنجليزية مفردة (اسم تطبيق/اسم خاص) تلي مقطعاً عربياً
        // تبقى وحدة مستقلة بصوت الإنجليزية ولا تُدمج في العربية (بند 18).
        val speaker = AnnouncementSpeaker(context)
        val speakUnitClass = AnnouncementSpeaker::class.java
            .declaredClasses.single { it.simpleName == "SpeakUnit" }
        val ctor = speakUnitClass.declaredConstructors.single()
        ctor.isAccessible = true
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
            "كلمة إنجليزية مفردة لا تُدمج مع العربية (بند 18)",
            2, merged.size
        )
        val firstText = requireNotNull(merged[0]).javaClass
            .getDeclaredField("text").also { it.isAccessible = true }
            .get(merged[0])
        assertEquals("السلام عليكم", firstText)
        val secondText = requireNotNull(merged[1]).javaClass
            .getDeclaredField("text").also { it.isAccessible = true }
            .get(merged[1])
        assertEquals("John", secondText)
        speaker.shutdown()
    }

    @Test
    fun `multi-word English phrase merges with Arabic on same voice`() {
        // بند 17: عبارة إنجليزية متعددة الكلمات على الصوت الواحد تُدمج
        // مع العربية (بلا سكتات) لأن المحرّك يوزّع اللغات تلقائياً.
        val speaker = AnnouncementSpeaker(context)
        val speakUnitClass = AnnouncementSpeaker::class.java
            .declaredClasses.single { it.simpleName == "SpeakUnit" }
        val ctor = speakUnitClass.declaredConstructors.single()
        ctor.isAccessible = true
        val arabic = ctor.newInstance(
            "مرحبا", Locale.forLanguageTag("ar"), 1.0f,
            1.0f, 1.0f, null
        )
        val english = ctor.newInstance(
            "Hello World", Locale.forLanguageTag("en"), 1.0f,
            1.0f, 1.0f, null
        )
        val units = mutableListOf(arabic, english)
        val merge = AnnouncementSpeaker::class.java
            .getDeclaredMethod("mergeAdjacentSameVoice", List::class.java)
        merge.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val merged = merge.invoke(speaker, units) as List<*>

        assertEquals(
            "عبارة إنجليزية متعددة الكلمات تُدمج مع العربية على الصوت الواحد",
            1, merged.size
        )
        val mergedText = requireNotNull(merged[0]).javaClass
            .getDeclaredField("text").also { it.isAccessible = true }
            .get(merged[0])
        assertEquals("مرحبا Hello World", mergedText)
        speaker.shutdown()
    }

    @Test
    fun `adjacent units merge regardless of rate pitch volume differences`() {
        // بند 17/18: وحدتان على نفس الصوت بمعدلات مختلفة تُدمجان
        // (إذا كانتا من خارج نطاق الكلمة الإنجليزية المفردة) لعدم
        // إنتاج سكتات.
        val speaker = AnnouncementSpeaker(context)
        val speakUnitClass = AnnouncementSpeaker::class.java
            .declaredClasses.single { it.simpleName == "SpeakUnit" }
        val ctor = speakUnitClass.declaredConstructors.single()
        ctor.isAccessible = true
        val fast = ctor.newInstance(
            "أهلاً", Locale.forLanguageTag("ar"), 1.2f,
            1.1f, 0.9f, null
        )
        val slow = ctor.newInstance(
            "وعيداً", Locale.forLanguageTag("ar"), 0.8f,
            0.9f, 1.1f, null
        )
        val units = mutableListOf(fast, slow)
        val merge = AnnouncementSpeaker::class.java
            .getDeclaredMethod("mergeAdjacentSameVoice", List::class.java)
        merge.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val merged = merge.invoke(speaker, units) as List<*>

        assertEquals(
            "وحدتان عربيتان على نفس الصوت بمعدلات مختلفة تُدمجان",
            1, merged.size
        )
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

    @Test
    fun `explicit voice id wins over the unit language voice`() {
        // بند 18 (تكملة): المعرّف الصريح (صوت المستخدم المخصص للغة)
        // يُفضَّل دوماً عند طلبه بالاسم مهما كانت لغة الوحدة.
        val voices = listOf(
            voice("en-us", "en"),
            voice("ar-eg", "ar"),
            voice("en-gb", "en")
        )
        assertEquals(
            "en-gb",
            AnnouncementSpeaker.voiceFor(
                voices, "en-gb",
                Locale.forLanguageTag("en")
            )?.name
        )
        assertEquals(
            "ar-eg",
            AnnouncementSpeaker.voiceFor(
                voices, "ar-eg",
                Locale.forLanguageTag("en")
            )?.name
        )
    }

    @Test
    fun `unit language picks a matching voice when no explicit id`() {
        // بند 18 (تكملة): بلا معرّف صريح تختار الوحدة الإنجليزية أول صوتٍ
        // لسانُه "en" فيضمن تبديل لغة نطق المحرك فعلياً — لا يعتمد على
        // setLanguage وحده الذي قد يُبقي بعض المحركات صوتَه العربي.
        val voices = listOf(
            voice("ar-eg", "ar"),
            voice("en-us", "en"),
            voice("en-gb", "en")
        )
        assertEquals(
            "en-us",
            AnnouncementSpeaker.voiceFor(
                voices, null,
                Locale.forLanguageTag("en")
            )?.name
        )
    }

    @Test
    fun `no matching voice falls back to null for setLanguage`() {
        // بند 18 (تكملة): إن لم يقدّم المحرك صوتاً للسان الوحدة (محرك خارجي
        // بلا صوت لتلك اللغة) يرجع null ويبقى setLanguage(locale) سقوطاً
        // آمناً — كما كان السلوك سابقاً على محركاتٍ لا تملك صوتاً إنجليزياً.
        val voices = listOf(voice("ar-eg", "ar"))
        assertEquals(
            null,
            AnnouncementSpeaker.voiceFor(
                voices, null,
                Locale.forLanguageTag("en")
            )
        )
    }

    @Test
    fun `empty or null voices list returns null`() {
        assertEquals(
            null,
            AnnouncementSpeaker.voiceFor(
                null, null,
                Locale.forLanguageTag("en")
            )
        )
        assertEquals(
            null,
            AnnouncementSpeaker.voiceFor(
                emptyList(), null,
                Locale.forLanguageTag("en")
            )
        )
    }

    /** استدعاء الاستدعاء الخاص للاكتمال (لا محرك TTS حقيقي في الاختبار). */
    private fun notifyCompletion(speaker: AnnouncementSpeaker) {
        val method = AnnouncementSpeaker::class.java
            .getDeclaredMethod("notifySpeechComplete")
        method.isAccessible = true
        method.invoke(speaker)
    }

    /** بناء صوت اختباري بلسانٍ معيّن (مثيل حقيقي سائر داخل Robolectric). */
    private fun voice(name: String, language: String): Voice =
        Voice(
            name,
            Locale.forLanguageTag(language),
            Voice.QUALITY_HIGH,
            0,
            false,
            emptySet()
        )
}