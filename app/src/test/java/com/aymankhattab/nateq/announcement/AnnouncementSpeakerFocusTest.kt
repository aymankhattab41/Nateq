package com.aymankhattab.nateq.announcement

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementAppContext
import com.aymankhattab.nateq.core.audio.announcement.AnnouncementSpeaker
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowLooper

/**
 * يغطي قرارات إدارة التركيز الصوتي في [AnnouncementSpeaker]:
 * - إلغاءٌ صامت فوري عند AUDIOFOCUS_REQUEST_FAILED (لا نطق فوق
 *   مكالمة/صوت ناشط).
 * - حارس المسار المؤجل: عند انقضاء مؤقّت الأمان بلا تسليم التركيز لا يُنطق شيء.
 * - مسار ما قبل Android 8 يمرّر المستمع المسجَّل فعلاً عند الإخلاء (لا null —
 *   كان التسريب يترك مراجع المستمعين معلقة في AudioService).
 * الوصول للحقول الخاصة عبر Reflection (محرك tts + الإجراء المنتظر) لأنها
 * منفّذة داخلياً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AnnouncementSpeakerFocusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val appContext: Context get() = context.applicationContext

    private val audioManager: AudioManager
        get() = appContext.getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager

    private val shadowAudio: ShadowAudioManager
        get() = shadowOf(audioManager)

    private val arLocale = Locale.forLanguageTag("ar")

    /** المستودعُ الفعليّ الذي يقرأ منه [AnnouncementSpeaker] إعداداته —
     *  مثيلُ التطبيق المحقون عبر [AnnouncementAppContext]. الكتابةُ عبر
     *  مثيلٍ منفصل لا تظهر له لأن `SnapshotPrefs` لقطةٌ بالذاكرة، فلا بدّ
     *  من الكتابة على هذا المثيل نفسه لقياس السلوك الحقيقي. */
    private val appSettings: SettingsRepository
        get() = (appContext as AnnouncementAppContext).settingsRepository

    @org.junit.Before
    fun setUp() {
        val repo = appSettings
        repo.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            "com.google.android.tts"
        )
        val componentName = android.content.ComponentName(
            "com.google.android.tts",
            "com.google.android.tts.GoogleTtsService"
        )
        val shadowPm = shadowOf(context.packageManager)
        shadowPm.addServiceIfNotPresent(componentName)
        shadowPm.addIntentFilterForService(
            componentName,
            android.content.IntentFilter("android.intent.action.TTS_SERVICE")
        )
        com.aymankhattab.nateq.core.audio.providers.EnginePicker
            .invalidateCache()
    }

    private fun speaker(): AnnouncementSpeaker = AnnouncementSpeaker(context)

    private fun fieldOf(instance: Any, name: String): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance)
    }

    private fun setField(
        instance: Any,
        name: String,
        value: Any?
    ) {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(instance, value)
    }

    private fun ttsIsNull(s: AnnouncementSpeaker): Boolean =
        fieldOf(s, "tts") == null

    private fun pendingActionIsNull(s: AnnouncementSpeaker): Boolean =
        fieldOf(s, "pendingFocusAction") == null

    private fun boolField(
        s: AnnouncementSpeaker,
        name: String
    ): Boolean = fieldOf(s, name) as Boolean

    /**
     * يستدعي [AnnouncementSpeaker.requestAudioFocus] مباشرةً عبر Reflection.
     * منطق Zero-Ducking يضع hasAudioFocus صراحةً بلا حاجة لعبور TTS.
     */
    private fun invokeRequestAudioFocus(
        s: AnnouncementSpeaker
    ): Int {
        val m = s.javaClass.getDeclaredMethod("requestAudioFocus")
        m.isAccessible = true
        return m.invoke(s) as Int
    }

    /** يضبط currentCategory مباشرةً دون المرور بـ speak(). */
    private fun setCategory(
        s: AnnouncementSpeaker,
        category: String?
    ) = setField(s, "currentCategory", category)

    @Test
    fun focusRequestFailed_duringCall_defersAndRetries_thenDropsSilently() {
        val s = speaker()
        audioManager.mode = AudioManager.MODE_IN_CALL
        s.speak("اختبار المكالمة", arLocale, 1f, 1f, 1f)
        // لا تهيئة فورية بعد الرفض: يُعيد جدولة طلب التركيز لاحقاً (لا نطق
        // فوق مشغّلٍ محجوز — المكالمة) بدل النطق فوقه.
        assertTrue("لا محرك يتهيأ قبل إعادة الجدولة", ttsIsNull(s))
        assertTrue("لا إجراء نطق معلّق فورياً", pendingActionIsNull(s))

        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

        // نفاد محاولات إعادة الجدولة: إسقاطٌ صامت حمايةً للمكالمة.
        assertTrue("لا محرك يتهيأ بعد نفاد المحاولات", ttsIsNull(s))
        assertTrue("الإجراء أُلغي بعد النفاد", pendingActionIsNull(s))
        audioManager.mode = AudioManager.MODE_NORMAL
        s.shutdown()
    }

    @Test
    fun normalMode_grantsFocusImmediatelyWithoutDucking() {
        // نختبر requestAudioFocus() مباشرةً (Zero-Ducking) بلا عبور TTS
        // الذي يفشل في Robolectric ويستدعي releaseAudioFocus() تلقائياً.
        val s = speaker()
        audioManager.mode = AudioManager.MODE_NORMAL
        val result = invokeRequestAudioFocus(s)
        // في الوضع الطبيعي: منح فوري وتزامن كامل بلا تأجيل
        assertEquals(
            "نتيجة الطلب GRANTED فوراً",
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            result
        )
        assertTrue(
            "يُمنح التركيز فوراً دون تأجيل",
            boolField(s, "hasAudioFocus")
        )
        assertNull(
            "لا يُرسل طلب تركيز للنظام لمنع خفض صوت الوسائط",
            shadowAudio.getLastAudioFocusRequest()
        )
        assertTrue(
            "لا يوجد إجراء مؤجل لانتظار التركيز",
            pendingActionIsNull(s)
        )
        s.shutdown()
    }

    @Test
    fun callerDuringCall_grantedWhenToggleEnabled() {
        // isAllowedDuringCall() يعود true للـ caller حين المفتاح مفعّل؛
        // نختبر requestAudioFocus() مباشرةً بلا عبور TTS.
        val repo = appSettings
        repo.setCallerAnnouncementDuringCallEnabled(true)
        try {
            val s = speaker()
            audioManager.mode = AudioManager.MODE_IN_CALL
            setCategory(s, "caller")
            val result = invokeRequestAudioFocus(s)
            // إعلانُ المتصل فوق مكالمة (مكالمة انتظار) يُمنح فوراً عند
            // تفعيل مفتاحه — لا يُرفض كبقية الفئات.
            assertEquals(
                "نتيجة الطلب GRANTED للمتصل المسموح",
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                result
            )
            assertTrue(
                "المتصل أثناء المكالمة يُمنح التركيز",
                boolField(s, "hasAudioFocus")
            )
            s.shutdown()
        } finally {
            repo.setCallerAnnouncementDuringCallEnabled(false)
        }
    }

    @Test
    fun callerDuringCall_deniedWhenToggleDisabled() {
        val repo = appSettings
        repo.setCallerAnnouncementDuringCallEnabled(false)
        val s = speaker()
        audioManager.mode = AudioManager.MODE_IN_CALL
        s.speak(
            "مكالمة من أحمد",
            arLocale, 1f, 1f, 1f,
            category = "caller"
        )
        // دون تفعيل المفتاح: رفضٌ صامت (لا نطق فوق المكالمة).
        assertFalse(
            "المتصل بلا مفتاحه يُرفض فوق المكالمة",
            boolField(s, "hasAudioFocus")
        )
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertTrue("لا محرك يتهيأ بعد الرفض", ttsIsNull(s))
        audioManager.mode = AudioManager.MODE_NORMAL
        s.shutdown()
    }

    @Test
    fun timeDuringCall_grantedWhenToggleEnabled() {
        val repo = appSettings
        repo.setAnnounceTimeDuringCalls(true)
        try {
            val s = speaker()
            audioManager.mode = AudioManager.MODE_IN_CALL
            // نضبط الفئة مباشرةً ثم نستدعي requestAudioFocus()
            setCategory(s, "time")
            val result = invokeRequestAudioFocus(s)
            assertEquals(
                "نتيجة الطلب GRANTED للوقت المسموح",
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                result
            )
            assertTrue(
                "الوقت أثناء المكالمة يُمنح عند تفعيل مفتاحه",
                boolField(s, "hasAudioFocus")
            )
            s.shutdown()
        } finally {
            repo.setAnnounceTimeDuringCalls(false)
        }
    }

    @Test
    fun batteryDuringCall_deniedEvenIfFeatureEnabled() {
        val repo = appSettings
        repo.setAllAnnouncementsEnabled(true)
        try {
            val s = speaker()
            audioManager.mode = AudioManager.MODE_IN_CALL
            s.speak(
                "البطارية ممتلئة",
                arLocale, 1f, 1f, 1f,
                category = "battery"
            )
            // البطارية بلا مفتاح «أثناء المكالمة»: صامتةٌ فوق المكالمة دائماً.
            assertFalse(
                "البطارية تُرفض فوق المكالمة",
                boolField(s, "hasAudioFocus")
            )
            s.shutdown()
        } finally {
            repo.setAllAnnouncementsEnabled(false)
        }
    }

    @Test
    @Config(sdk = [24])
    fun preO_abandon_passesRegisteredListener_notNull() {
        // على أندرويد قبل 8.0: الإخلاء بلا مستمع (null) كان يترك تسجيل المستمع
        // معلقاً في AudioService — الآن يُمرَّر المستمع الفعلي
        // فيُسجَّل الإخلاء.
        val s = speaker()
        s.stop()
        assertNotNull(
            "تمرير المستمع عند الإخلاء",
            shadowAudio.getLastAbandonedAudioFocusListener()
        )
    }

    @Test
    fun focusLoss_duringAnnouncement_stopsAndReleasesFocus() {
        val s = speaker()
        s.speak("إعلانٌ أثناء مكالمة", arLocale, 1f, 1f, 1f)
        val listener = fieldOf(
            s, "onAudioFocusChange"
        ) as AudioManager.OnAudioFocusChangeListener
        // بدأت مكالمة (فقد التركيز النهائي) — يُوقف النطق فوراً ويحرر التركيز.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(
            "فقد التركيز يُوقف النطق فوراً",
            boolField(s, "nowSpeaking")
        )
        assertFalse(
            "فقد التركيز يُحرر تملك التركيز",
            boolField(s, "hasAudioFocus")
        )
        s.shutdown()
    }

    @Test
    fun focusLossTransient_duringAnnouncement_stopsAndReleasesFocus() {
        val s = speaker()
        s.speak("إعلانٌ عابر", arLocale, 1f, 1f, 1f)
        val listener = fieldOf(
            s, "onAudioFocusChange"
        ) as AudioManager.OnAudioFocusChangeListener
        // فقدان مؤقت — إيقاف وتحرير التركيز.
        listener.onAudioFocusChange(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        )
        assertFalse(
            "فقدان التركيز المؤقت يُوقف النطق",
            boolField(s, "nowSpeaking")
        )
        assertFalse(
            "فقدان التركيز المؤقت يُحرر التركيز",
            boolField(s, "hasAudioFocus")
        )
        s.shutdown()
    }
    // ── اختبارات إعادة الضبط غير المشروطة ──────────────────────────────

    /**
     * [resetAudioFocusUnconditionally] يستدعي abandon حتى عندما
     * hasAudioFocus=false — أي بدون أي شرط داخلي.
     */
    @Test
    @Config(sdk = [24])
    fun resetUnconditional_whenFocusIsFalse_preO_stillCallsAbandon() {
        val s = speaker()
        // تأكد أن hasAudioFocus = false (الحالة الافتراضية بلا طلب سابق)
        assertFalse(
            "hasAudioFocus افتراضياً false",
            boolField(s, "hasAudioFocus")
        )
        // نستدعي إعادة الضبط اليدوية — يجب أن يُستدعى abandonAudioFocus
        // بصرف النظر عن hasAudioFocus الداخلية.
        s.resetAudioFocusUnconditionally()
        assertNotNull(
            "abandon استُدعي رغم hasAudioFocus=false",
            shadowAudio.getLastAbandonedAudioFocusListener()
        )
        s.shutdown()
    }

    /**
     * [resetAudioFocusUnconditionally] يستدعي abandon أيضاً حين
     * hasAudioFocus=true — يؤكد اللاشرطية في الاتجاهين.
     */
    @Test
    fun resetUnconditional_whenFocusIsGranted_stillCallsAbandon() {
        // نمنح التركيز مباشرةً عبر requestAudioFocus() بلا عبور TTS
        // (الذي يُلغي التركيز في Robolectric بعد فشل onInit).
        val s = speaker()
        audioManager.mode = AudioManager.MODE_NORMAL
        invokeRequestAudioFocus(s)
        assertTrue(
            "hasAudioFocus=true بعد المنح",
            boolField(s, "hasAudioFocus")
        )
        assertNull(
            "لا يُرسل طلب تركيز للنظام لمنع خفض صوت الوسائط",
            shadowAudio.getLastAudioFocusRequest()
        )
        // إعادة الضبط اليدوية — abandon مباشرة.
        s.resetAudioFocusUnconditionally()
        assertFalse(
            "hasAudioFocus=false بعد إعادة الضبط",
            boolField(s, "hasAudioFocus")
        )
        assertTrue(
            "pendingFocusAction أُلغي",
            pendingActionIsNull(s)
        )
        s.shutdown()
    }
}
