package com.aymankhattab.nateq.core.audio.announcement

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.SpeechChunker
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * يغطي إصلاحات المتحدث المشترك:
 * - معرّف نطق فريد لكل جزء حتى داخل نفس المللي ثانية (بند [3]) — تكرار
 *   الزمن وحده كان يفلتر أجزاءً من الإعلان بفعل onDone مبكر.
 * - قائمة مستمعي الاكتمال (بند [8]): تُستدعى كلها عند الاكتمال، والإزالة
 *   لا تمسّ غيرها، وخطأ أحد المستمعين لا يُسقط البقية.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AnnouncementSpeakerTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun `utterance ids stay unique within the same millisecond`() {
        val ids = (1..1000).map { AnnouncementSpeaker.nextUtteranceId() }
        assertEquals("معرّفات النطق فريدة دوماً", 1000, ids.toSet().size)
    }

    @Test
    fun `emoji stripping removes common emoji but not cjk extensions`() {
        // ينظّف الإيموجي الشائع (U+1F600) قبل النطق الخارجي (يُستبدل
        // بمسافة واحدة عن الركض)...
        val emoji = "\uD83D\uDE00"
        assertEquals(" ", AnnouncementSpeaker.stripEmojis(emoji))
        // امتداد CJK-B (U+20000) خارج بلوكات الإيموجي — لا يُبتلع، لأن
        // تنظيفه بنقاط كود مقيدة (كان يُبتلع سابقاً عبر نمط surrogate).
        val cjkB = String(Character.toChars(0x20000))
        assertEquals(cjkB, AnnouncementSpeaker.stripEmojis(cjkB))
    }

    @Test
    fun `emoji stripping keeps supplementary non emoji codepoints`() {
        // مسطح الأحرف القديمة (Old Italic، U+10300) في المستوي التكميلي
        // الأول — خارج نطاقات الإيموجي، لا يُمسّ به.
        val oldItalic = String(Character.toChars(0x10300))
        assertEquals(oldItalic, AnnouncementSpeaker.stripEmojis(oldItalic))
        // امتداد الرموز التكميلية (U+2A6D6) — كذلك غير إيموجي.
        val extSymbols = String(Character.toChars(0x2A6D6))
        assertEquals(extSymbols, AnnouncementSpeaker.stripEmojis(extSymbols))
        // نص عادي يبقى دون تغيير.
        assertEquals(
            "مرحبا بعالم كامل",
            AnnouncementSpeaker.stripEmojis("مرحبا بعالم كامل")
        )
    }

    @Test
    fun `emoji stripping collapses an emoji run into one space`() {
        // ركض إيموجي متواصل (U+1F600 U+1F600) يتحول لمسافة واحدة بدل
        // تكرار المسافات، وZWJ/FE0F تعديلات تُبتلع مع الركض صامتةً.
        val twinEmoji = "\uD83D\uDE00\uD83D\uDE00"
        assertEquals(" ", AnnouncementSpeaker.stripEmojis(twinEmoji))
        val heartSeq = "\u2764\uFE0F\u200D"
        assertEquals(" ", AnnouncementSpeaker.stripEmojis(heartSeq))
        // ركض في منتصف النص يتحول لمسافة واحدة لا غير (~نفس سلوك Regex+).
        assertEquals(
            "أنا أحبك",
            AnnouncementSpeaker.stripEmojis("أنا\uD83D\uDE00\uD83D\uDE00أحبك")
        )
    }

    @Test
    fun `prewarm schedules text processor warm up on creation`() {
        val speaker = AnnouncementSpeaker.getInstance(context)
        try {
            assertTrue(
                "التحميل المسبق يُطلق عند إنشاء المتحدث المشترك" +
                    " (بند الأوامر د.1)",
                speaker.isPrewarmStarted()
            )
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `warm text processor builds processing path eagerly`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            assertTrue(
                "مسار المعالجة يُبنى تزامنياً عند الطلب",
                speaker.warmTextProcessor()
            )
        } finally {
            speaker.shutdown()
        }
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
    fun `boosted rate is clamped above the engine ceiling`() {
        // المضاعف العام يُضرب قبل clamping: 1.4× سرعة 1.6 ⇒ 2.24 (داخل)،
        // و1.5× سرعة 2.0 ⇒ 3.0 يُقصّ على السقف الآمن 2.5.
        assertEquals(2.24f, AnnouncementSpeaker.clampedSpeechRate(1.6f * 1.4f))
        assertEquals(2.5f, AnnouncementSpeaker.clampedSpeechRate(2.0f * 1.5f))
        // والأدنى لا ينزل تحت 0.25 مهما صغر.
        assertEquals(0.25f, AnnouncementSpeaker.clampedSpeechRate(0.2f * 1.0f))
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
    fun `country voice wins over the first voice when locale has region`() {
        // ترجيح مطابقة رمز البلد: لِـ"en-GB" يُختار "en-gb" وليس
        // أولَ صوتٍ "en-US" — كان «أول صوت» يَجعل تبديل اللغة/المنطقة
        // بلا أثرٍ على الصوت الفعلي.
        val voices = listOf(
            voiceWith("en-us", "en", "US"),
            voiceWith("en-gb", "en", "GB"),
            voiceWith("en-au", "en", "AU")
        )
        assertEquals(
            "en-gb",
            AnnouncementSpeaker.voiceFor(
                voices, null,
                Locale.forLanguageTag("en-GB")
            )?.name
        )
    }

    @Test
    fun `unknown explicit id falls back to the language voice`() {
        // معرّف صريح لا يطابق اسم أي صوت (صوتٌ من محركٍ سابق) لا يُسقط
        // النطق: يتنزل لأفضل صوتٍ لسانُه لسانُ الوحدة — بترجيح البلد —
        // بدل الإبقاء الثابت على صوتٍ محدَّد لا يتغير.
        val voices = listOf(
            voiceWith("en-us", "en", "US"),
            voiceWith("en-gb", "en", "GB")
        )
        assertEquals(
            "en-gb",
            AnnouncementSpeaker.voiceFor(
                voices, "xx-old-engine-voice",
                Locale.forLanguageTag("en-GB")
            )?.name
        )
    }

    @Test
    fun `no language voice yields null to keep setLanguage fallback`() {
        assertNull(
            AnnouncementSpeaker.voiceFor(
                listOf(voice("ar-eg", "ar")), null,
                Locale.forLanguageTag("fr-FR")
            )
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

    @Test
    fun `fallback voice stays on the unit language after refusal`() {
        // عند رفض الصوت المفضّل تُجرّب الأصوات الباقية من لسان الوحدة
        // (ظاهرة Vocalizer) فلا يُرضخ للـ setLanguage قبل جَهدٍ أوسع.
        val voices = listOf(
            voiceWith("ar-sa", "ar", "SA"),
            voiceWith("ar-eg", "ar", "EG"),
            voice("en-us", "en")
        )
        assertEquals(
            "ar-eg",
            AnnouncementSpeaker.fallbackVoiceFor(
                voices,
                Locale.forLanguageTag("ar-SA"),
                "ar-sa"
            )?.name
        )
        assertNull(
            AnnouncementSpeaker.fallbackVoiceFor(
                voices,
                Locale.forLanguageTag("fr-FR"),
                "fr-fr"
            )
        )
    }

    @Test
    fun `fallback never offers a foreign language voice`() {
        // لا يُقدَّم صوتٌ أجنبيٌّ للوحدة مهما رُفض: إن لم يتبقَّ صوتٌ
        // لسانُه لسانُ الوحدة تُترك المحاولة للـ setLanguage.
        val voices = listOf(
            voiceWith("en-gb", "en", "GB"),
            voiceWith("ar-sa", "ar", "SA"),
            voiceWith("ar-eg", "ar", "EG")
        )
        assertEquals(
            "ar-eg",
            AnnouncementSpeaker.fallbackVoiceFor(
                voices,
                Locale.forLanguageTag("ar"),
                "ar-sa"
            )?.name
        )
        assertNull(
            AnnouncementSpeaker.fallbackVoiceFor(
                voices,
                Locale.forLanguageTag("fr"),
                "fr-fr"
            )
        )
    }

    @Test
    fun `announcement text is converted through TextProcessor like the reader`(
    ) {
        // بند الأوامر 1: نصوص الإعلانات تمر عبر TextProcessor (أرقام/أوقات/
        // عملات) قبل إرسالها للمحرك — فتُنطق «ألف وخمسمائة» لا «1500»
        // و«الواحدة وعشر دقائق» لا «1:10».
        val speaker = AnnouncementSpeaker(context)
        val build = AnnouncementSpeaker::class.java
            .getDeclaredMethod(
                "buildSpeakUnits",
                String::class.java, Locale::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                EmojiSpeechConfig::class.java,
                List::class.java
            )
        build.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val built = build.invoke(
            speaker,
            "وصلك 1500 دولار الساعة 1:10 PM",
            Locale.forLanguageTag("ar"),
            1.0f, 1.0f, 1.0f,
            null, null
        ) as List<*>
        val spoken = buildString {
            built.forEach { unit ->
                val textField = requireNotNull(unit).javaClass
                    .getDeclaredField("text").also { it.isAccessible = true }
                append(textField.get(unit) as String).append(' ')
            }
        }
        assertTrue("المبلغ يُنطق بالكلمات", spoken.contains("ألف وخمسمائة"))
        assertTrue("الوقت يُنطق بالصيغة العربية", spoken.contains("الواحدة"))
        assertTrue("دقائق الوقت مذكورة", spoken.contains("عشر دقائق"))
        assertFalse("لا يبقى رقم خام في الإعلان", spoken.contains("1500"))
        assertFalse(
            "لا يُرسل توقيت رقمي خام للمحرك", spoken.contains("1:10")
        )
        speaker.shutdown()
    }

    @Test
    fun `numbers category voice and bars apply to pure numeric unit only`() {
        // فئة الأرقام: صوتٌ مخصص (ذكوري) مختلف عن الصوت العام — يُلزم الرقم
        // البحت في النص المختلط لا يُلامس المقطع العربي المجاور (المسار
        // الموازي المطلوب للإعلانات بنفس استعلامات TimeAnnouncementManager).
        val repo = SettingsRepository.create(context)
        repo.setNumberReadingLanguage("en")
        repo.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS, "male-numbers"
        )
        repo.setSpeechRateForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS, 1.4f
        )
        repo.setPitchForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS, 0.8f
        )
        repo.setVolumeForCategory(
            SettingsRepository.VOICE_CATEGORY_NUMBERS, 0.7f
        )
        val speaker = AnnouncementSpeaker(context)
        val built = buildUnitsFor(speaker, "الرصيد 1500")
        assertEquals("مقطع عربي + مقطع رقمي إنجليزي", 2, built.size)
        val arabicUnit = built[0]
        assertNotEquals(
            "المقطع العربي يبقى على الصوت العام",
            "male-numbers", unitField(arabicUnit, "voiceId")
        )
        assertEquals(1.0f, unitField(arabicUnit, "rate") as Float, 0.01f)
        val numberUnit = built[1]
        assertEquals("male-numbers", unitField(numberUnit, "voiceId"))
        assertEquals(1.4f, unitField(numberUnit, "rate") as Float, 0.01f)
        assertEquals(0.8f, unitField(numberUnit, "pitch") as Float, 0.01f)
        assertEquals(0.7f, unitField(numberUnit, "volume") as Float, 0.01f)
        speaker.shutdown()
    }

    @Test
    fun `numeric unit falls back to general rates when category unset`() {
        // بلا صوتٍ محفوظ لفئة الأرقام: يبقى سلوك الوحدة الرقمية كما كان
        // (صوت اللغة العام وأشرطة الإعلان 1.0) — لا كسر للتجربة الافتراضية.
        val repo = SettingsRepository.create(context)
        repo.setNumberReadingLanguage("en")
        val speaker = AnnouncementSpeaker(context)
        val built = buildUnitsFor(speaker, "الرصيد 1500")
        val numberUnit = built[1]
        assertNull(
            "بلا صوت فئة لا يُلزم الرقم صوتاً خاصاً",
            unitField(numberUnit, "voiceId")
        )
        assertEquals(1.0f, unitField(numberUnit, "rate") as Float, 0.01f)
        assertEquals(1.0f, unitField(numberUnit, "volume") as Float, 0.01f)
        speaker.shutdown()
    }

    /** يبني وحدات النطق لنصٍ ما عبر [buildSpeakUnits] (بلا محرك TTS حقيقي). */
    private fun buildUnitsFor(
        speaker: AnnouncementSpeaker, text: String
    ): List<*> {
        val build = AnnouncementSpeaker::class.java
            .getDeclaredMethod(
                "buildSpeakUnits",
                String::class.java, Locale::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                EmojiSpeechConfig::class.java,
                List::class.java
            )
        build.isAccessible = true
        return build.invoke(
            speaker,
            text, Locale.forLanguageTag("ar"),
            1.0f, 1.0f, 1.0f,
            null, null
        ) as List<*>
    }

    /** قراءة حقل من وحدة نطق عبر الانعكاس (بلا محرك في الاختبار). */
    private fun unitField(unit: Any?, name: String): Any? =
        requireNotNull(unit).javaClass.getDeclaredField(name)
            .also { it.isAccessible = true }
            .get(unit)

    @Test
    fun `unsupported setLanguage logs a warning and is not silently assumed`() {
        // بند الأوامر 2: عائد setLanguage كان مُهملاً — محركٌ بلا صوتٍ
        // إنجليزي يُرجع LANG_NOT_SUPPORTED فيُسجَّل تحذير بدل افتراض نجاح
        // صامت يُبقي المحرك على لغته السابقة (نفس نمط SystemVoiceProvider).
        registerFakeEngine("com.fake.announcementEngine")
        val speaker = AnnouncementSpeaker(context)
        val fake = UnsupportedEnAnnouncementEngine(
            context, { }, "com.fake.announcementEngine"
        )
        val ttsField = AnnouncementSpeaker::class.java
            .getDeclaredField("tts")
        ttsField.isAccessible = true
        ttsField.set(speaker, fake)

        ShadowLog.clear()
        val doSpeak = AnnouncementSpeaker::class.java
            .getDeclaredMethod(
                "doSpeak",
                String::class.java, Locale::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        doSpeak.isAccessible = true
        doSpeak.invoke(
            speaker, "Hello", Locale.forLanguageTag("en"),
            1.0f, 1.0f, 1.0f,
            null, TextToSpeech.QUEUE_FLUSH, 1
        )

        val logs = ShadowLog.getLogs()
        assertTrue(
            "تُسجَّل اللغة غير المدعومة كتحذير لا نجاح صامت",
            logs.any { log ->
                "NATEQ_TTS" == log.tag &&
                    log.msg?.contains("result=") == true
            }
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

    /** صوت اختباري بلغة وبلد (لمحاكاة أسمائها الفعلية في getVoices). */
    private fun voiceWith(
        name: String, language: String, country: String
    ): Voice = Voice(
        name,
        Locale.forLanguageTag("$language-$country"),
        Voice.QUALITY_HIGH, 0, false, emptySet()
    )

    /** محرك وهمي لا يدعم اللغة الإنجليزية (نمط SystemVoiceProvider). */
    private class UnsupportedEnAnnouncementEngine(
        context: Context,
        listener: TextToSpeech.OnInitListener,
        engine: String
    ) : TextToSpeech(context, listener, engine) {
        override fun setLanguage(locale: Locale?): Int {
            // بلا أصوات، فلا يجد المحركُ صوتاً للوحدة الإنجليزية — التمييز
            // الوحيد هو setLanguage؛ نعلن عدم الدعم لإثبات تحليل العائد.
            return if (locale != null &&
                locale.language.equals("en", ignoreCase = true)
            ) {
                TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
    }

    private fun registerFakeEngine(pkg: String) {
        val app = RuntimeEnvironment.getApplication()
        val component = ComponentName(pkg, "com.fake.TtsService")
        val shadowPm = shadowOf(app.packageManager)
        shadowPm.addServiceIfNotPresent(component)
        shadowPm.addIntentFilterForService(
            component,
            IntentFilter("android.intent.action.TTS_SERVICE")
        )
    }

@Test
    fun `announcement engine falls back to the configured language engine`() {
        // غياب المحرك الصريح للفئة لا يترك المتحدث على محرك النظام:
        // يُقرأ محركُ اللغة المضبوط في الإعدادات (محرك النطق) فيُربط
        // الإعلان به فعلياً — تقديم شكوى «أصوات محددة لا تتغير».
        // (دالة نقية: المعامل الثاني هو قيمة محرك اللغة المقروءة.)
        assertNull(resolveAnnouncementEngine(null, null))
        assertEquals(
            "org.arabic.speech",
            resolveAnnouncementEngine(null, "org.arabic.speech")
        )
        assertEquals(
            "org.arabic.speech",
            resolveAnnouncementEngine("", "org.arabic.speech")
        )
        // الصريح (فئة خاصة) يتقدَّم على محرك اللغة المضبوط.
        assertEquals(
            "org.engine.specific",
            resolveAnnouncementEngine(
                "org.engine.specific", "org.alternate.speech"
            )
        )
    }

    @Test
    fun `events track uses media stream by default to protect other apps`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val attrs = speechAudioAttributes(speaker, null)
            assertEquals(
                "مسار الأحداث موحد على USAGE_MEDIA",
                android.media.AudioAttributes.USAGE_MEDIA,
                attrs.usage
            )
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `events track stays on media stream even when switch is off`() {
        val audioManager = context.getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager
        val shadowAudio = shadowOf(audioManager)

        val speaker = AnnouncementSpeaker(context)
        try {
            val attrs = speechAudioAttributes(speaker, null)
            assertEquals(
                "تعطيل المفتاح لا يُسقط النطق للإتاحة — يبقى MEDIA",
                android.media.AudioAttributes.USAGE_MEDIA,
                attrs.usage
            )

            speaker.speak(
                "حدث تزامني", Locale.forLanguageTag("ar"), 1f, 1f, 1f,
                category = SettingsRepository.VOICE_CATEGORY_TIME
            )
            assertNull(
                "مسار الأحداث لا يطلب تركيزاً صوتياً لمنع خفض صوت الوسائط",
                shadowAudio.getLastAudioFocusRequest()
            )
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `requestAudioFocus grants without ducking and protects calls`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val reqMethod = AnnouncementSpeaker::class.java
                .getDeclaredMethod("requestAudioFocus")
            reqMethod.isAccessible = true

            // في الحالة العادية خارج المكالمات: يُمنح فوراً دون طلب تركيز
            val result = reqMethod.invoke(speaker) as Int
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result)

            val audioManager = context.getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager
            val shadowAudio = shadowOf(audioManager)
            assertNull(
                "لا يُرسل طلب تركيز للنظام لمنع الـ Ducking",
                shadowAudio.getLastAudioFocusRequest()
            )

            // أثناء المكالمات الهاتفية: يُرفض التركيز حمايةً للمكالمة
            audioManager.mode = AudioManager.MODE_IN_CALL
            val callResult = reqMethod.invoke(speaker) as Int
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_FAILED, callResult)
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `releaseAudioFocus clears state smoothly without lingering requests`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val reqMethod = AnnouncementSpeaker::class.java
                .getDeclaredMethod("requestAudioFocus")
            reqMethod.isAccessible = true
            val releaseMethod = AnnouncementSpeaker::class.java
                .getDeclaredMethod("releaseAudioFocus")
            releaseMethod.isAccessible = true

            reqMethod.invoke(speaker)
            releaseMethod.invoke(speaker)

            val hasFocusField = AnnouncementSpeaker::class.java
                .getDeclaredField("hasAudioFocus")
            hasFocusField.isAccessible = true
            assertFalse(hasFocusField.get(speaker) as Boolean)
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `safeEngineForAnnouncement uses explicit external engine`() {
        val result = safeEngineForAnnouncement(
            context,
            "com.samsung.SMT"
        )
        assertEquals("com.samsung.SMT", result)
    }

    @Test
    fun `safeEngineForAnnouncement avoids self package when requested`() {
        val result = safeEngineForAnnouncement(
            context,
            context.packageName,
            defaultSynthProvider = { "com.google.android.tts" },
            installedEnginesProvider = { listOf("com.google.android.tts") }
        )
        assertEquals("com.google.android.tts", result)
    }

    @Test
    fun `safeEngineForAnnouncement avoids self when system default is self`() {
        val result = safeEngineForAnnouncement(
            context,
            null,
            defaultSynthProvider = { context.packageName },
            installedEnginesProvider = {
                listOf(context.packageName, "com.samsung.SMT")
            }
        )
        assertEquals("com.samsung.SMT", result)
    }

    @Test
    fun `safeEngineForAnnouncement uses system default when external`() {
        val result = safeEngineForAnnouncement(
            context,
            null,
            defaultSynthProvider = { "com.google.android.tts" },
            installedEnginesProvider = { listOf("com.google.android.tts") }
        )
        assertEquals("com.google.android.tts", result)
    }

    @Test
    fun `voiceFor matches iso3 language and country codes from vocalizer`() {
        val vocalizerVoices = listOf(
            Voice(
                "vocalizer-laila",
                Locale("ara", "SAU"),
                Voice.QUALITY_HIGH, 0, false, emptySet()
            ),
            Voice(
                "vocalizer-maged",
                Locale("ara", "EGY"),
                Voice.QUALITY_HIGH, 0, false, emptySet()
            ),
            Voice(
                "vocalizer-tom",
                Locale("eng", "USA"),
                Voice.QUALITY_HIGH, 0, false, emptySet()
            )
        )
        val arMatch = AnnouncementSpeaker.voiceFor(
            vocalizerVoices, null, Locale("ar")
        )
        assertNotNull(arMatch)
        assertEquals("vocalizer-laila", arMatch?.name)

        val egMatch = AnnouncementSpeaker.voiceFor(
            vocalizerVoices, null, Locale("ar", "EG")
        )
        assertEquals("vocalizer-maged", egMatch?.name)

        val enMatch = AnnouncementSpeaker.voiceFor(
            vocalizerVoices, null, Locale("en")
        )
        assertEquals("vocalizer-tom", enMatch?.name)
    }

    @Test
    fun `fallbackVoiceFor matches iso3 language codes from vocalizer`() {
        val vocalizerVoices = listOf(
            Voice(
                "vocalizer-laila",
                Locale("ara", "SAU"),
                Voice.QUALITY_HIGH, 0, false, emptySet()
            ),
            Voice(
                "vocalizer-maged",
                Locale("ara", "EGY"),
                Voice.QUALITY_HIGH, 0, false, emptySet()
            )
        )
        val fallback = AnnouncementSpeaker.fallbackVoiceFor(
            vocalizerVoices,
            Locale("ar"),
            "vocalizer-laila"
        )
        assertEquals("vocalizer-maged", fallback?.name)
    }

    @Test
    fun `resolveFallbackLocale prefers matching voice locale or region`() {
        val vocalizerVoices = listOf(
            Voice(
                "vocalizer-laila",
                Locale("ara", "SAU"),
                Voice.QUALITY_HIGH, 0, false, emptySet()
            )
        )
        val voiceFallback = AnnouncementSpeaker.resolveFallbackLocale(
            Locale("ar"),
            vocalizerVoices
        )
        assertEquals("ara", voiceFallback?.language)

        val defaultFallback = AnnouncementSpeaker.resolveFallbackLocale(
            Locale("ar"),
            emptyList()
        )
        assertEquals("ar", defaultFallback?.language)
        assertEquals("SA", defaultFallback?.country)
    }

    @Test
    fun `isResumableCategory identifies notifications and sms only`() {
        assertTrue(
            AnnouncementSpeaker.isResumableCategory(
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
        )
        assertTrue(
            AnnouncementSpeaker.isResumableCategory(
                SettingsRepository.ANNOUNCE_CATEGORY_SMS
            )
        )
        assertFalse(
            AnnouncementSpeaker.isResumableCategory(
                SettingsRepository.VOICE_CATEGORY_BATTERY
            )
        )
        assertFalse(
            AnnouncementSpeaker.isResumableCategory(
                SettingsRepository.VOICE_CATEGORY_TIME
            )
        )
        assertFalse(AnnouncementSpeaker.isResumableCategory(null))
    }

    @Test
    fun `splitIntoSentences breaks long notifications at punctuation`() {
        val shortText = "رسالة قصيرة"
        assertEquals(
            listOf(shortText),
            AnnouncementSpeaker.splitIntoSentences(shortText)
        )

        val longText =
            "السلام عليكم ورحمة الله وبركاته. أردت إخبارك بأن " +
            "موعد الاجتماع غداً الساعة العاشرة صباحاً، برجاء الحضور."
        val sentences = AnnouncementSpeaker.splitIntoSentences(longText)
        assertTrue(sentences.size >= 2)
        assertEquals("السلام عليكم ورحمة الله وبركاته.", sentences[0])
    }

    @Test
    fun `shouldPreemptCurrentSpeech triggers when event preempts message`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val nowSpeakingField = AnnouncementSpeaker::class.java
                .getDeclaredField("nowSpeaking")
            nowSpeakingField.isAccessible = true
            val currentCategoryField = AnnouncementSpeaker::class.java
                .getDeclaredField("currentCategory")
            currentCategoryField.isAccessible = true

            // ليس هناك نطق جارٍ
            nowSpeakingField.set(speaker, false)
            currentCategoryField.set(
                speaker,
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            )
            assertFalse(
                speaker.shouldPreemptCurrentSpeech(
                    SettingsRepository.VOICE_CATEGORY_BATTERY
                )
            )

            // نطق جارٍ لإشعار، وحدث بطارية قادم
            nowSpeakingField.set(speaker, true)
            assertTrue(
                speaker.shouldPreemptCurrentSpeech(
                    SettingsRepository.VOICE_CATEGORY_BATTERY
                )
            )

            // نطق جارٍ لإشعار، وإشعار آخر قادم: لا يقاطع
            assertFalse(
                speaker.shouldPreemptCurrentSpeech(
                    SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
                )
            )

            // نطق جارٍ لبطارية، وإعلان وقت قادم: لا يقاطع
            currentCategoryField.set(
                speaker,
                SettingsRepository.VOICE_CATEGORY_BATTERY
            )
            assertFalse(
                speaker.shouldPreemptCurrentSpeech(
                    SettingsRepository.VOICE_CATEGORY_TIME
                )
            )
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `announcement boosts stream volume and restores after`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) {
                return
            }
            val original = am.getStreamVolume(AudioManager.STREAM_MUSIC)

            // الرفع على قناة الوسائط لكل نطق غير فئة المتصل.
            speaker.boostStreamVolume(SettingsRepository.VOICE_CATEGORY_TIME)
            assertEquals(
                "حجم قناة النطق يُرفع للقمة أثناء النطق",
                max,
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
            )

            // رفع ثانٍ (عند فئة أخرى/نطق متداخل) لا يفسد التتبّع.
            speaker.boostStreamVolume(SettingsRepository.VOICE_CATEGORY_TIME)

            // الاستعادة تُعيد المستوى الأصلي تماماً.
            speaker.restoreBoostedStreamVolume()
            assertEquals(
                "مستوى القناة يُستعاد بعد اكتمال النطق",
                original,
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
            )
        } finally {
            speaker.restoreBoostedStreamVolume()
            speaker.shutdown()
        }
    }

    /** انعكاس سمات النطق لفئةٍ بعينها — أي تغيّر في توقيعها الخاص
     *  يُفشل البناء هنا بدل أن يسقط بصمت. */
    private fun speechAudioAttributes(
        speaker: AnnouncementSpeaker,
        category: String?
    ): android.media.AudioAttributes {
        val method = AnnouncementSpeaker::class.java
            .getDeclaredMethod(
                "speechAudioAttributes",
                String::class.java
            )
        method.isAccessible = true
        return method.invoke(speaker, category)
            as android.media.AudioAttributes
    }

    // ===== حارس نطق المتصل: لا يمرّ على قناة تخفضها المكالمة الجارية =====

    /**
     * يحرس الكسر: **نطق المتصل لا يجوز أن يمرّ على `STREAM_MUSIC`** لأن
     * النظام يخفضه تلقائياً أثناء مكالمةٍ جارية (Voice-call Ducking)،
     * فيصوت منخفضاً — وهو ما ظهر على Pixel (أندرويد 17) بينما يعلو
     * نطقُ البطارية والساعة اللذين لا يُخفضهما النظام.
     *
     * **الفارق بالجهاز** لا بالإعداد: معامل الصوت 1.0 في الحالتين، فرفعُ
     * شريط المستوى لا يغيّر شيئاً، والسببُ المسارُ لا القيمة.
     */
    @Test
    fun `caller announcement avoids media stream that active call ducks`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val callerAttrs = speechAudioAttributes(
                speaker,
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
            )
            assertNotEquals(
                "نطق المتصل على USAGE_MEDIA = النظام يخفضه أثناء المكالمة",
                android.media.AudioAttributes.USAGE_MEDIA,
                callerAttrs.usage
            )
            assertEquals(
                "نطق المتصل ينتقل إلى مسار الإشعار",
                android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT,
                callerAttrs.usage
            )

            // الفئتان العربيتان والإنجليزيتان من المتصل أيضاً — واحدة
            // مخترَقة تعني أن إعلاناً منهم يبقى على المسار المخفَّض.
            listOf(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_AR,
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER_EN
            ).forEach { category ->
                assertEquals(
                    "كل فئات المتصل الثلاث على مسار الإشعار — $category",
                    android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT,
                    speechAudioAttributes(speaker, category).usage
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    /** حارسُ المقابَل: الفئاتُ غير المتصل تُبقى على مسار الوسائط، وإلا
     *  سرّب تغييرُ مسار المتصل الصوتَ إلى البطارية والساعة والرسائل. */
    @Test
    fun `non caller categories stay on media stream`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            listOf(
                SettingsRepository.VOICE_CATEGORY_TIME,
                SettingsRepository.VOICE_CATEGORY_BATTERY,
                SettingsRepository.ANNOUNCE_CATEGORY_SMS,
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS
            ).forEach { category ->
                assertEquals(
                    "فئة غير المتصل تبقى على مسار الوسائط — $category",
                    android.media.AudioAttributes.USAGE_MEDIA,
                    speechAudioAttributes(speaker, category).usage
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    /** حارسُ الرفع: المتصل يُرفع على قناة **الإشعار** (لا الوسائط)،
     *  فإلا رُفعت قناتهُ التي يُنطق عليها لصوتٌ منخفض — وهو العَرَض
     *  الأصلي وإن اختلف سببُه. */
    @Test
    fun `caller boost raises notification stream not media`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager
            val musicMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val musicOriginal = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val notifyMax =
                am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            if (musicMax <= 0 || notifyMax <= 0) {
                return
            }
            // نبدأ من مستوى منخفض لنتأكد أن الرفع فعلاً رفعٌ لا لا شيء.
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 1, 0)

            speaker.boostStreamVolume(
                SettingsRepository.ANNOUNCE_CATEGORY_CALLER
            )

            assertEquals(
                "قناة الإشعار (القناة التي يُنطق عليها المتصل) تُرفع للقمة",
                notifyMax,
                am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            )
            assertEquals(
                "قناة الوسائط لا تُمسّ في رفع المتصل — إقرار التتبع",
                musicOriginal,
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
            )

            speaker.restoreBoostedStreamVolume()
        } finally {
            speaker.restoreBoostedStreamVolume()
            speaker.shutdown()
        }
    }

    @Test
    fun `every category including numbers and default boosts stream volume`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) {
                return
            }
            val original = am.getStreamVolume(AudioManager.STREAM_MUSIC)

            // كل الفئات (أرقام/افتراضي/ساعة/رسائل/بطارية/إشعارات/باطلة)
            // ترفع القناة للقمة — لا استثناءات تحتاج صيانة عند الإضافة.
            listOf(
                SettingsRepository.ANNOUNCE_CATEGORY_SMS,
                SettingsRepository.VOICE_CATEGORY_BATTERY,
                SettingsRepository.VOICE_CATEGORY_NOTIFICATIONS,
                SettingsRepository.VOICE_CATEGORY_NUMBERS,
                SettingsRepository.VOICE_CATEGORY_DEFAULT,
                SettingsRepository.VOICE_CATEGORY_TIME,
                null
            ).forEach { category ->
                speaker.restoreBoostedStreamVolume()
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    original.coerceAtMost(max - 1).coerceAtLeast(0),
                    0
                )
                speaker.boostStreamVolume(category)
                assertEquals(
                    "النطق (category=$category) يرفع القناة للقمة",
                    max,
                    am.getStreamVolume(AudioManager.STREAM_MUSIC)
                )
            }
            speaker.restoreBoostedStreamVolume()
            assertEquals(
                "مستوى القناة يُستعاد بعد اكتمال النطق",
                original,
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
            )
        } finally {
            speaker.restoreBoostedStreamVolume()
            speaker.shutdown()
        }
    }

    @Test
    fun `long announcement text is split into engine safe units`() {
        // شبكةُ أمانِ مسار الإعلانات: إعلانٌ طويلٌ يُقسَّم إلى وحدات
        // ≤ SpeechChunker.MAX_CHARS، وكلُّ وحدةٍ تُنطق مستقلّةً في
        // طابور المتحدّث ففشلُ واحدةٍ لا يُسقط ما بعدها. (لم يعد هذا
        // حلَّ انقطاع النص الطويل — أُصلح سببُه في SynthesisBudget
        // وأُزيل التقسيمُ من مسار القارئ.)
        val speaker = AnnouncementSpeaker(context)
        try {
            val units = buildLanguageUnits(speaker, longArabicText(900))
            assertTrue("وحدات نطق وُلّدت", units.isNotEmpty())
            val textField = units.first().javaClass
                .getDeclaredField("text").also { it.isAccessible = true }
            units.forEach { unit ->
                val text = requireNotNull(textField.get(unit) as? String)
                assertTrue(
                    "وحدة تتجاوز حدّ المحرّك (${text.length} حرف): $text",
                    text.length <= SpeechChunker.MAX_CHARS
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    // ============ خفض الرنين أثناء إعلان المتصل ============

    /**
     * يهيّئ قناة الرنين بمستوىٍ معلوم (ويعيده)، ويتخطّى الحالة إن
     * لم تكن القناة قابلةً للقياس في Robolectric.
     */
    private fun withRingVolume(
        speaker: AnnouncementSpeaker,
        level: Int,
        block: (AudioManager, Int) -> Unit
    ) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val original = am.getStreamVolume(AudioManager.STREAM_RING)
        try {
            if (max <= 1) return
            am.setStreamVolume(AudioManager.STREAM_RING, level, 0)
            if (am.getStreamVolume(AudioManager.STREAM_RING) != level) return
            block(am, level)
        } finally {
            speaker.restoreDuckedRingVolume()
            am.setStreamVolume(AudioManager.STREAM_RING, original, 0)
        }
    }

    private fun setCallerDucking(enabled: Boolean, percent: Int) {
        val prefs = context
            .getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("caller_ring_ducking_enabled", enabled)
            .putInt("caller_ring_duck_percent", percent)
            .commit()
    }

    @Test
    fun `ringtone is lowered only for the caller category`() {
        val speaker = AnnouncementSpeaker(context)
        setCallerDucking(true, 40)
        try {
            withRingVolume(speaker, 5) { am, level ->
                val expected = RingtoneDuckMath.duckedLevel(level, 7, 40)
                speaker.duckRingVolumeIfCallerCategory(
                    SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                )
                assertEquals(
                    "فئة المتصل تخفض الرنين إلى النسبة المطلوبة",
                    expected,
                    am.getStreamVolume(AudioManager.STREAM_RING)
                )
                speaker.restoreDuckedRingVolume()
                assertEquals(
                    "الاستعادة تُعيد المستوى الأصلي",
                    level,
                    am.getStreamVolume(AudioManager.STREAM_RING)
                )

                // فئة غير المتصل (ساعة/بطارية/رسالة): الرنين لا يُمَسّ.
                speaker.duckRingVolumeIfCallerCategory(
                    SettingsRepository.VOICE_CATEGORY_TIME
                )
                assertEquals(
                    "الساعة لا تخفض الرنين",
                    level,
                    am.getStreamVolume(AudioManager.STREAM_RING)
                )
                speaker.duckRingVolumeIfCallerCategory(null)
                assertEquals(
                    "ولا فئةٌ باطنة",
                    level,
                    am.getStreamVolume(AudioManager.STREAM_RING)
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `ringtone stays untouched while the box is off`() {
        val speaker = AnnouncementSpeaker(context)
        setCallerDucking(false, 40)
        try {
            withRingVolume(speaker, 5) { am, level ->
                speaker.duckRingVolumeIfCallerCategory(
                    SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                )
                assertEquals(
                    "بلا مربّع لا نمسّ الرنين البتّة",
                    level,
                    am.getStreamVolume(AudioManager.STREAM_RING)
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `a ringtone changed by the user is not overwritten`() {
        val speaker = AnnouncementSpeaker(context)
        setCallerDucking(true, 40)
        try {
            withRingVolume(speaker, 6) { am, level ->
                speaker.duckRingVolumeIfCallerCategory(
                    SettingsRepository.ANNOUNCE_CATEGORY_CALLER
                )
                // المستخدم رفع الرنين بنفسه أثناء النطق.
                am.setStreamVolume(AudioManager.STREAM_RING, level, 0)
                speaker.restoreDuckedRingVolume()
                assertEquals(
                    "اختيار المستخدم أثناء النطق لا يُطمس",
                    level,
                    am.getStreamVolume(AudioManager.STREAM_RING)
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    @Test
    fun `restoring without a duck is a no op`() {
        val speaker = AnnouncementSpeaker(context)
        try {
            withRingVolume(speaker, 4) { am, level ->
                speaker.restoreDuckedRingVolume()
                speaker.restoreDuckedRingVolume()
                assertEquals(
                    "بلا خفضٍ قائم لا استعادةَ بلا سبب",
                    level,
                    am.getStreamVolume(AudioManager.STREAM_RING)
                )
            }
        } finally {
            speaker.shutdown()
        }
    }

    /** نصٌّ عربيٌّ طويلٌ بلا علامات جملٍ (أسوأ حالةٍ للمحرّك). */
    private fun longArabicText(chars: Int): String =
        "قراءة ".repeat((chars / 6) + 1).take(chars)

    /** استدعاء addLanguageUnits خاصةً (نفس نمط الانعكاس في الاختبارات). */
    @Suppress("UNCHECKED_CAST")
    private fun buildLanguageUnits(
        speaker: AnnouncementSpeaker,
        text: String
    ): List<Any> {
        val method = AnnouncementSpeaker::class.java
            .getDeclaredMethod(
                "addLanguageUnits",
                MutableList::class.java,
                String::class.java,
                Locale::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
        method.isAccessible = true
        val out = mutableListOf<Any>()
        method.invoke(
            speaker, out, text, Locale.forLanguageTag("ar"),
            1.0f, 1.0f, 1.0f
        )
        return out
    }
}