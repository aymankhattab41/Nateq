package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.common.TimeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.NumberSpeech
import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** اختبار فلتر بث البطارية الدائم (بند 16.1): يُعالج البث الدم مرة
 *  واحدة لكل نسبة منعطف، ويُفلتر التكرار في الذاكرة قبل أي
 *  عملية لاتزامنية أو قراءة قرص. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class BatteryAnnouncementReceiverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun batteryIntent(level: Int, scale: Int = 100): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
        }

    @Before
    fun setUp() {
        BatteryAnnouncementReceiver.resetLevelFilterForTesting()
        // إزالة أي مشترٍ وهمي للمؤثرات تسرّبَ من اختبار سابق (كائن مصاحب
        // ثابت يعيش ويكون بين الاختبارات في نفس عملية JVM).
        AudioCuePlayer.replaceSharedForTesting(null)
        // عزل نغمة خطأ «لا محرك» عن اختبارات المؤثرات: في JVM الاختباري
        // يفشل ربط TTS فتُشغَّل نغمة NO_ENGINE، وهي ليست مؤثر البطارية
        // موضع الفحص هنا. ختمُ التخفيف يمنع تكرارها أثناء هذه الفئة.
        AnnouncementSpeaker.lastNoEngineCueTime =
            System.currentTimeMillis()
        // مسح ختوم نافذة منع التكرار بين الاختبارات حتى لا تتسرب.
        context.getSharedPreferences(
            "nateq_battery_state", Context.MODE_PRIVATE
        )
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        BatteryAnnouncementReceiver.resetLevelFilterForTesting()
        AudioCuePlayer.replaceSharedForTesting(null)
    }

    @Test
    fun `first broadcast is new`() {
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(87)))
    }

    @Test
    fun `same percent is filtered`() {
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(87)))
        assertFalse(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(87)))
        assertFalse(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(87)))
    }

    @Test
    fun `new percent passes`() {
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(87)))
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(86)))
    }

    @Test
    fun `returning to a previous percent passes again`() {
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(87)))
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(86)))
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(87)))
    }

    @Test
    fun `invalid level data never blocks processing`() {
        assertTrue(
            BatteryAnnouncementReceiver.isNewLevel(batteryIntent(-1, 100))
        )
        assertTrue(
            BatteryAnnouncementReceiver.isNewLevel(batteryIntent(-1, -1))
        )
    }

    @Test
    fun `percent uses same rounding as handler`() {
        // (level*100)/scale — نفس حسابات معالجة الإعلان: 49.9% تُقرَّب إلى 49
        // فتُفلتر بعد معالجة 49%، وليست نسبة جديدة.
        assertTrue(
            BatteryAnnouncementReceiver.isNewLevel(batteryIntent(49, 100))
        )
        assertFalse(
            BatteryAnnouncementReceiver.isNewLevel(batteryIntent(499, 1000))
        )
    }

    private fun batteryIntentWith(
        level: Int,
        status: Int,
        plugged: Int
    ): Intent = batteryIntent(level).apply {
        putExtra(BatteryManager.EXTRA_STATUS, status)
        putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
    }

    @Test
    fun `full charge passes when status changes at stable percent`() {
        // بند 2: عند ثبات 100% متصلة، تناول الحالة FULL بعد CHARGING يمرّ
        // من الفلتر ليتفقد المعالج شرط اكتمال الشحن — بدل تجميده على
        // أول 100% (كان الفلتر النسبي يمنع الفحص نهائياً).
        val charging = batteryIntentWith(
            100,
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_PLUGGED_AC
        )
        val full = batteryIntentWith(
            100,
            BatteryManager.BATTERY_STATUS_FULL,
            BatteryManager.BATTERY_PLUGGED_AC
        )
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(charging))
        assertFalse(
            "نفس الحالة نفسها مُفلترة",
            BatteryAnnouncementReceiver.isNewLevel(charging)
        )
        assertTrue(
            "تغيّر حالة الشحن يمرّ رغم ثبات النسبة",
            BatteryAnnouncementReceiver.isNewLevel(full)
        )
    }

    @Test
    fun `plugged to unplugged at same percent passes`() {
        val plugged = batteryIntentWith(
            90,
            BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_PLUGGED_AC
        )
        val unplugged = batteryIntentWith(
            90,
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            0
        )
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(plugged))
        assertFalse(BatteryAnnouncementReceiver.isNewLevel(plugged))
        assertTrue(
            "انفصال الشاحن يمرّ رغم ثبات النسبة",
            BatteryAnnouncementReceiver.isNewLevel(unplugged)
        )
    }

    @Test
    fun `replug at full percent after unplug passes again`() {
        // دورة كاملة عند ثبات 100%: توصيل FULL يُعلن الاكتمال، ثم يُفصل
        // الشحن فيتبدّل مفتاحُ الفلتر (DISCHARGING|0)، ثم إعادة التوصيل
        // بنفس 100 FULL — يمرّ الفلترَ ثانيةً فيُعلن، فلا يُهدر إعلانُ
        // الاكتمال بعد كل دورة توصيلٍ كاملة.
        val plugged = batteryIntentWith(
            100,
            BatteryManager.BATTERY_STATUS_FULL,
            BatteryManager.BATTERY_PLUGGED_AC
        )
        val unplugged = batteryIntentWith(
            100,
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            0
        )
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(plugged))
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(unplugged))
        assertTrue(
            "إعادة التوصيل بعد فصلٍ تعيد العبور رغم ثبات 100",
            BatteryAnnouncementReceiver.isNewLevel(plugged)
        )
    }

    @Test
    fun `missing status extras still filter duplicate raw broadcasts`() {
        // بث خام بلا حقول حالة/توصيل (كما كان سابقاً): يتكرر بنفس المفتاح
        // فيُفلتر — لا تراجع في الحماية من عشرات البثات المتماثلة.
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(50)))
        assertFalse(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(50)))
    }

    @Test
    fun `speak applies the battery voice before announcing`() {
        // بند [1]: صوتُ البطارية كان يُقرأ ولا يُطبَّق فيعلق نطقُ البطارية
        // على صوت فئةٍ سابقة (متصل/إشعار/رسالة) — الآن speak() يُعيد ضبط
        // المتحدث المشترك بالصوت المفضَّل أولاً (نفس نمط المتصل/الرسائل).
        val receiver = BatteryAnnouncementReceiver()
        val method = BatteryAnnouncementReceiver::class.java
            .getDeclaredMethod(
                "speak", Context::class.java,
                SettingsRepository::class.java, String::class.java,
                Locale::class.java, String::class.java,
                CueType::class.java
            )
        method.isAccessible = true
        val batteryVoice = "ar-EG"
        method.invoke(
            receiver,
            context,
            SettingsRepository(context),
            "نصُّ الاختبار",
            Locale.ENGLISH,
            batteryVoice,
            null
        )
        val shared = AnnouncementSpeaker.getInstance(context)
        val voiceField = AnnouncementSpeaker::class.java
            .getDeclaredField("voiceId")
        voiceField.isAccessible = true
        assertEquals(
            "يُطبَّق صوت البطارية قبل النطق",
            batteryVoice, voiceField.get(shared)
        )
        shared.shutdown()
    }

    @Test
    fun `battery announcement uses unified category voice and settings`() {
        val settings = SettingsRepository(context)
        val cat = SettingsRepository.VOICE_CATEGORY_BATTERY
        val targetVoice = "ar-unified-battery"
        settings.setPreferredVoiceIdForCategory(cat, targetVoice)
        settings.setSpeechRateForCategory(cat, 1.4f)
        settings.setPitchForCategory(cat, 1.3f)
        settings.setVolumeForCategory(cat, 0.9f)
        settings.setEngineForCategory(cat, "com.test.tts")

        val receiver = BatteryAnnouncementReceiver()
        val method = BatteryAnnouncementReceiver::class.java
            .getDeclaredMethod(
                "speak", Context::class.java,
                SettingsRepository::class.java, String::class.java,
                Locale::class.java, String::class.java,
                CueType::class.java
            )
        method.isAccessible = true
        val voiceId = settings.getPreferredVoiceIdForCategory(cat)
        method.invoke(
            receiver,
            context,
            settings,
            "البطارية 20 بالمئة",
            Locale.ENGLISH,
            voiceId,
            null
        )
        val shared = AnnouncementSpeaker.getInstance(context)
        val voiceField = AnnouncementSpeaker::class.java
            .getDeclaredField("voiceId")
        voiceField.isAccessible = true
        assertEquals(
            "صوت البطارية من الفئة الموحّدة يُطبَّق",
            targetVoice, voiceField.get(shared)
        )
        shared.shutdown()
    }


    // ===== مسار المؤثرات الصوتية (بند 16.2) =====

    /** عينة PCM متوقعة لنوع مؤثر — التخليق حتمي (طول = مدة × معدل العينات). */
    private fun expectedCueSamples(cueType: CueType): Int =
        CueSynth.durationMs(AudioCue(cueType)) * CueSynth.SAMPLE_RATE / 1000

    /** استدعاء speak الخاصة بالانعكاس (نفس نمط الاختبار أعلاه). */
    private fun invokeBatterySpeak(
        settings: SettingsRepository,
        cueType: CueType?
    ) {
        val receiver = BatteryAnnouncementReceiver()
        val method = BatteryAnnouncementReceiver::class.java
            .getDeclaredMethod(
                "speak", Context::class.java,
                SettingsRepository::class.java, String::class.java,
                Locale::class.java, String::class.java,
                CueType::class.java
            )
        method.isAccessible = true
        method.invoke(
            receiver,
            context,
            settings,
            "نصُّ الاختبار",
            Locale.ENGLISH,
            "ar-EG",
            cueType
        )
    }

    private fun currentVoiceId(): Any? {
        val voiceField = AnnouncementSpeaker::class.java
            .getDeclaredField("voiceId")
        voiceField.isAccessible = true
        return voiceField.get(AnnouncementSpeaker.getInstance(context))
    }

    @Test
    fun `cue only mode plays the custom cue at its volume without speech`() {
        val settings = SettingsRepository(context)
        settings.setBatterySoundCueMode(2)
        settings.setBatteryCueVolume(0.35f)
        val sink = FakeCueSink()
        AudioCuePlayer.replaceSharedForTesting(
            AudioCuePlayer.forTesting(sink, CueSynth)
        )
        try {
            // صوتُ المتحدث المشترك الثابت — الوضع «مؤثر فقط» لا يلمسه.
            val voiceBefore = currentVoiceId()
            invokeBatterySpeak(settings, CueType.BATTERY_CHARGING)
            ShadowLooper.idleMainLooper()
            assertEquals(
                "يُشغَّل مؤثر التوصيل بنوعه الصحيح",
                expectedCueSamples(CueType.BATTERY_CHARGING),
                sink.played?.size
            )
            assertEquals(
                "بحجم المؤثر المخصص",
                0.35f, sink.volume, 0.0f
            )
            assertEquals(
                "الوضع «مؤثر فقط» لا يُنطق ولا يُغيّر صوتَ المتحدث",
                voiceBefore, currentVoiceId()
            )
        } finally {
            AudioCuePlayer.replaceSharedForTesting(null)
        }
    }

    @Test
    fun `cue before speech mode applies the voice and plays the cue first`() {
        val settings = SettingsRepository(context)
        settings.setBatterySoundCueMode(0)
        settings.setBatteryCueVolume(0.6f)
        val sink = FakeCueSink()
        AudioCuePlayer.replaceSharedForTesting(
            AudioCuePlayer.forTesting(sink, CueSynth)
        )
        try {
            invokeBatterySpeak(settings, CueType.BATTERY_DISCONNECTED)
            ShadowLooper.idleMainLooper()
            assertEquals(
                expectedCueSamples(CueType.BATTERY_DISCONNECTED),
                sink.played?.size
            )
            assertEquals(
                "المؤثر بمستوى صوته المخصص",
                0.6f, sink.volume, 0.0f
            )
            assertEquals(
                "المؤثر يسبق النطق لكن الصوت يُطبَّق",
                "ar-EG", currentVoiceId()
            )
            AnnouncementSpeaker.getInstance(context).shutdown()
        } finally {
            AudioCuePlayer.replaceSharedForTesting(null)
        }
    }

    @Test
    fun `narration only mode skips the cue but applies the voice`() {
        val settings = SettingsRepository(context)
        settings.setBatterySoundCueMode(1)
        settings.setBatteryCueVolume(0.9f)
        val sink = FakeCueSink()
        AudioCuePlayer.replaceSharedForTesting(
            AudioCuePlayer.forTesting(sink, CueSynth)
        )
        try {
            invokeBatterySpeak(settings, CueType.BATTERY_LOW)
            ShadowLooper.idleMainLooper()
            assertNull("النطق فقط — لا مؤثر يُشغَّل", sink.played)
            assertEquals("ar-EG", currentVoiceId())
            AnnouncementSpeaker.getInstance(context).shutdown()
        } finally {
            AudioCuePlayer.replaceSharedForTesting(null)
        }
    }

    // ===== نافذة منع تكرار الإعلان (5 دقائق) =====

    // لحظة أساسية ثابتة خارج كل النوافذ — الحتمية عبر ساعة افتراضية.
    private val baseNow = 1_700_000_000_000L

    @Test
    fun `first announcement is allowed`() {
        // بلا ختم سابق يُعدّ مستوى 50 "منطوقاً مؤخراً": مع الانعكاس المنطقي
        // القديم كانت هذه الحالة تُسكت الإعلان دائماً (لا ينطق أبداً أول مرة).
        val clock = FakeClock(baseNow)
        assertFalse(
            BatteryAnnouncementReceiver(clock).announcedRecently(context, "%50")
        )
    }

    @Test
    fun `immediately after mark is suppressed`() {
        val clock = FakeClock(baseNow)
        val receiver = BatteryAnnouncementReceiver(clock)
        receiver.markAnnounced(context, "%50")
        assertTrue(receiver.announcedRecently(context, "%50"))
    }

    @Test
    fun `after five minutes the same level is allowed again`() {
        // ساعة افتراضية مضبوطة: الختم يُكتب عند baseNow ثم تتحكم الاختبار بلحظة
        // الاستعلام — بعد دقيقة داخل النافذة، وبعد تجاوز الخمس دقائق خارجها.
        val clock = FakeClock(baseNow)
        val receiver = BatteryAnnouncementReceiver(clock)
        receiver.markAnnounced(context, "%50")
        clock.setTo(baseNow + 60_000L)
        assertTrue(receiver.announcedRecently(context, "%50"))
        clock.setTo(baseNow + 5 * 60 * 1000L + 5_000L)
        assertFalse(receiver.announcedRecently(context, "%50"))
    }

    @Test
    fun `old stamp from a previous boot never suppresses`() {
        // محاكاة ختمٍ كُتب قبل إعادة تشغيل الهاتف (قبل 10 ساعات): الجدار الزمني
        // يعبر إعادة الإقلاع فيُسمح بالنطق الآن بدل التجمد حتى تنقضي المدة.
        val clock = FakeClock(baseNow)
        val oldStamp = baseNow - 10 * 60 * 60 * 1000L
        context.getSharedPreferences(
            "nateq_battery_state", Context.MODE_PRIVATE
        )
            .edit().putLong("battery_last_announced_%50", oldStamp).commit()
        assertFalse(
            BatteryAnnouncementReceiver(clock).announcedRecently(context, "%50")
        )
    }

    @Test
    fun `power events within debounce window are deduplicated`() {
        BatteryAnnouncementReceiver.resetLevelFilterForTesting()
        val clock = FakeClock(baseNow)
        val receiver = BatteryAnnouncementReceiver(clock)
        val intent = Intent(Intent.ACTION_POWER_CONNECTED)
        receiver.onReceive(context, intent)
        clock.setTo(baseNow + 500L)
        receiver.onReceive(context, intent)
        clock.setTo(baseNow + 2000L)
        receiver.onReceive(context, intent)
    }

    // ══════════ نسبة البطارية في إعلانَي توصيل/فصل الشاحن ══════════

    private fun withPercent(
        text: String,
        intent: Intent?,
        arabic: Boolean
    ): String {
        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        // دالةٌ امتداديةٌ عضوٌة: المستقبِل الأول هو كائن المستقبل.
        return with(receiver) { text.withBatteryPercent(intent, arabic) }
    }

    @Test
    fun `charger text carries the battery percentage in arabic`() {
        val template = context.getString(R.string.battery_connected)
        val out = withPercent(template, batteryIntent(63), arabic = true)
        assertFalse(
            "لم تُملأ نسبة البطارية في نص إعلان التوصيل",
            out.contains("{percent}")
        )
        assertTrue(
            "النسبة لفظيةٌ عربية لا رقمٌ خام: $out",
            out.contains(NumberSpeech.toArabicWords(63))
        )
    }

    @Test
    fun `charger text carries the battery percentage in english`() {
        val template = context.getString(R.string.battery_disconnected)
        val out = withPercent(template, batteryIntent(87), arabic = false)
        assertFalse(
            "لم تُملأ نسبة البطارية في نص إعلان الفصل",
            out.contains("{percent}")
        )
        assertTrue(
            "النسبة لفظيةٌ إنجليزية: $out",
            out.contains(NumberSpeech.toEnglishWords(87))
        )
    }

    @Test
    fun `charger text keeps template when battery level is unavailable`() {
        val template = context.getString(R.string.battery_connected)
        assertEquals(
            "بثٌّ بلا نسبة صالحة يُبقي النص كما هو",
            template, withPercent(template, null, arabic = true)
        )
        assertEquals(
            "نسبةٌ فاسدة (بلا مقياس) تُبقي النص كما هو",
            template,
            withPercent(
                template,
                Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    putExtra(BatteryManager.EXTRA_LEVEL, 50)
                    putExtra(BatteryManager.EXTRA_SCALE, 0)
                },
                arabic = true
            )
        )
    }

    @Test
    fun `text without placeholder is untouched`() {
        assertEquals(
            "نصٌّ بلا بديلٍ لا يُمسّ",
            "البطارية ممتلئة",
            withPercent("البطارية ممتلئة", batteryIntent(100), arabic = true)
        )
    }

    @Test
    fun `battery disabled does not speak saved default levels`() {
        val settings = SettingsRepository(context)
        settings.setBatteryAnnouncementEnabled(false)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))

        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        val spoke20 = receiver.handle(
            context, batteryIntent(20), Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "لا يُنطق مستوى 20 عند تعطيل مفتاح البطارية ولو حُفظ المستوى",
            spoke20
        )

        val spoke15 = receiver.handle(
            context, batteryIntent(15), Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "لا يُنطق مستوى 15 عند تعطيل مفتاح البطارية ولو حُفظ المستوى",
            spoke15
        )
    }

    @Test
    fun `battery disabled still allows full charge announcement`() {
        val settings = SettingsRepository(context)
        settings.setBatteryAnnouncementEnabled(false)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))
        settings.setChargingCompleteAnnouncementEnabled(true)

        val intent = batteryIntent(100).apply {
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        val spoke = receiver.handle(
            context, intent, Intent.ACTION_BATTERY_CHANGED
        )
        assertTrue(
            "اكتمال الشحن ميزة مستقلة وتُنطق حتى مع تعطيل مفتاح المستويات",
            spoke
        )
    }

    @Test
    fun `disabling battery preserves user saved levels`() {
        val settings = SettingsRepository(context)
        settings.setBatteryAnnouncementLevels(setOf("80", "50", "20"))
        settings.setBatteryAnnouncementEnabled(false)

        assertEquals(
            "تعطيل المفتاح لا يفرغ مستويات المستخدم المحفوظة",
            setOf("80", "50", "20"),
            settings.getBatteryAnnouncementLevels()
        )

        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        assertFalse(
            receiver.handle(
                context, batteryIntent(80), Intent.ACTION_BATTERY_CHANGED
            )
        )
        assertFalse(
            receiver.handle(
                context, batteryIntent(50), Intent.ACTION_BATTERY_CHANGED
            )
        )
        assertFalse(
            receiver.handle(
                context, batteryIntent(20), Intent.ACTION_BATTERY_CHANGED
            )
        )

        settings.setBatteryAnnouncementEnabled(true)
        assertTrue(
            "إعادة التفعيل تستعيد نطق المستويات المحفوظة فوراً",
            receiver.handle(
                context, batteryIntent(80), Intent.ACTION_BATTERY_CHANGED
            )
        )
    }

    @Test
    fun `levels silent when disabled and service kept for other categories`() {
        val settings = SettingsRepository(context)
        settings.setBatteryAnnouncementEnabled(false)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))
        settings.setCallerAnnouncementEnabled(true)
        settings.setSmsReadingMode("all")

        // تحقق أن الخدمة تستوجب البقاء بسبب فئات أخرى
        val needsFg =
            AnnouncementSchedulerService.needsForegroundService(settings)
        assertTrue(
            "الخدمة تبقى قائمة بسبب إعلانات المتصل والرسائل",
            needsFg
        )

        // مستقبل البطارية المُسجل بالخدمة لا ينطق المستويات وهي مطفأة
        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        val spoke20 = receiver.handle(
            context, batteryIntent(20), Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "لا يُنطق 20% عند بقاء الخدمة للمتصل/الرسائل والمفتاح مطفأ",
            spoke20
        )
        val spoke15 = receiver.handle(
            context, batteryIntent(15), Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "لا يُنطق 15% عند بقاء الخدمة للمتصل/الرسائل والمفتاح مطفأ",
            spoke15
        )
    }

    @Test
    fun `full charge alone requires service and speaks 100 percent`() {
        val settings = SettingsRepository(context)
        settings.setBatteryAnnouncementEnabled(false)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))
        settings.setCallerAnnouncementEnabled(false)
        settings.setSmsReadingMode("off")
        settings.setNotificationReadingEnabled(false)
        settings.setChargingCompleteAnnouncementEnabled(true)

        // التحقق أن ميزة اكتمال الشحن وحدها تستوجب بقاء الخدمة
        // لسماع بث BATTERY_CHANGED الدائم
        val needsFg =
            AnnouncementSchedulerService.needsForegroundService(settings)
        assertTrue(
            "ميزة اكتمال الشحن وحدها تستوجب الخدمة الأمامية",
            needsFg
        )

        // اكتمال الشحن يُنطق بنجاح والمستويات صامتة
        val intent100 = batteryIntent(100).apply {
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        val spoke100 = receiver.handle(
            context, intent100, Intent.ACTION_BATTERY_CHANGED
        )
        assertTrue(
            "اكتمال الشحن يُنطق وحده عند 100% ومتصل بالشاحن",
            spoke100
        )

        // التحقق من منع التكرار خلال نافذة المنع
        val spokeDuplicate = receiver.handle(
            context, intent100, Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "منع تكرار إعلان اكتمال الشحن عند تكرار البث بنفس الحالة",
            spokeDuplicate
        )

        // التحقق أن مستويات البطارية تظل صامتة
        val spoke20 = receiver.handle(
            context, batteryIntent(20), Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "مستويات البطارية صامتة رغم تفعيل اكتمال الشحن",
            spoke20
        )
    }

    @Test
    fun `full charge works when other services are also running`() {
        val settings = SettingsRepository(context)
        settings.setBatteryAnnouncementEnabled(false)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))
        settings.setCallerAnnouncementEnabled(true)
        settings.setSmsReadingMode("all")
        settings.setChargingCompleteAnnouncementEnabled(true)

        val needsFg =
            AnnouncementSchedulerService.needsForegroundService(settings)
        assertTrue(
            "الخدمة قائمة بسبب المتصل والرسائل واكتمال الشحن",
            needsFg
        )

        val intent100 = batteryIntent(100).apply {
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_FULL
            )
        }
        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        val spoke100 = receiver.handle(
            context, intent100, Intent.ACTION_BATTERY_CHANGED
        )
        assertTrue(
            "اكتمال الشحن يُنطق مع وجود خدمات أخرى قائمة",
            spoke100
        )
    }

    @Test
    fun `full charge disabled does not require service if all off`() {
        val settings = SettingsRepository(context)
        settings.setBatteryAnnouncementEnabled(false)
        settings.setCallerAnnouncementEnabled(false)
        settings.setSmsReadingMode("off")
        settings.setNotificationReadingEnabled(false)
        settings.setChargingCompleteAnnouncementEnabled(false)

        val needsFg =
            AnnouncementSchedulerService.needsForegroundService(settings)
        assertFalse(
            "لا حاجة للخدمة عند إطفاء كل الإعلانات بما فيها اكتمال الشحن",
            needsFg
        )

        val intent100 = batteryIntent(100).apply {
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))
        val spoke100 = receiver.handle(
            context, intent100, Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "لا يُنطق اكتمال الشحن عند تعطيل ميزته",
            spoke100
        )
    }

    private fun setTestSpeakerVoice(v: String?) {
        val voiceField = AnnouncementSpeaker::class.java
            .getDeclaredField("voiceId")
        voiceField.isAccessible = true
        voiceField.set(AnnouncementSpeaker.getInstance(context), v)
    }

    private fun clearDebounceState() {
        context.getSharedPreferences(
            "nateq_battery_state", Context.MODE_PRIVATE
        ).edit().clear().commit()
        BatteryAnnouncementReceiver.resetLevelFilterForTesting()
    }

    @Test
    fun `cue only mode across levels 20 15 10 100 and charger events`() {
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        settings.setBatteryAnnouncementEnabled(true)
        settings.setBatteryAnnouncementLevels(
            setOf("20", "15", "10", "100", "50")
        )
        settings.setChargingDisconnectAnnouncementEnabled(true)
        settings.setChargingCompleteAnnouncementEnabled(true)
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY, "voice-target"
        )
        settings.setBatterySoundCueMode(2)
        settings.setBatteryCueVolume(0.45f)

        val sink = FakeCueSink()
        AudioCuePlayer.replaceSharedForTesting(
            AudioCuePlayer.forTesting(sink, CueSynth)
        )
        val clock = FakeClock(baseNow)
        val receiver = BatteryAnnouncementReceiver(clock)

        try {
            val levelsToTest = listOf(
                20 to CueType.BATTERY_LOW,
                15 to CueType.BATTERY_LOW,
                10 to CueType.BATTERY_LOW,
                100 to CueType.BATTERY_FULL
            )
            for ((lvl, expectedCue) in levelsToTest) {
                clearDebounceState()
                sink.reset()
                setTestSpeakerVoice("sentinel-untouched")
                var cueDoneInvoked = false
                val intent = batteryIntent(lvl)
                val handled = receiver.handle(
                    context, intent, Intent.ACTION_BATTERY_CHANGED,
                    onCueDone = { cueDoneInvoked = true }
                )
                ShadowLooper.idleMainLooper()
                assertTrue("معالجة إعلان المستوى $lvl%", handled)
                assertTrue("استدعاء onCueDone للمستوى $lvl%", cueDoneInvoked)
                assertEquals(
                    "المؤثر الصوتي الصحيح لمستوى $lvl%",
                    expectedCueSamples(expectedCue),
                    sink.played?.size
                )
                assertEquals(0.45f, sink.volume, 0.0f)
                assertEquals(
                    "مؤثر فقط لا ينطق كلاماً للمستوى $lvl%",
                    "sentinel-untouched", currentVoiceId()
                )
            }

            // توصيل الشاحن (مؤثر فقط)
            clearDebounceState()
            sink.reset()
            setTestSpeakerVoice("sentinel-untouched")
            var connectDone = false
            val handledConnect = receiver.handle(
                context, batteryIntent(50), Intent.ACTION_POWER_CONNECTED,
                onCueDone = { connectDone = true }
            )
            ShadowLooper.idleMainLooper()
            assertTrue(handledConnect)
            assertTrue(connectDone)
            assertEquals(
                expectedCueSamples(CueType.BATTERY_CHARGING),
                sink.played?.size
            )
            assertEquals(0.45f, sink.volume, 0.0f)
            assertEquals(
                "مؤثر فقط لا ينطق عند توصيل الشاحن",
                "sentinel-untouched", currentVoiceId()
            )

            // فصل الشاحن (مؤثر فقط)
            clearDebounceState()
            sink.reset()
            setTestSpeakerVoice("sentinel-untouched")
            var disconnectDone = false
            val handledDisconnect = receiver.handle(
                context, batteryIntent(50), Intent.ACTION_POWER_DISCONNECTED,
                onCueDone = { disconnectDone = true }
            )
            ShadowLooper.idleMainLooper()
            assertTrue(handledDisconnect)
            assertTrue(disconnectDone)
            assertEquals(
                expectedCueSamples(CueType.BATTERY_DISCONNECTED),
                sink.played?.size
            )
            assertEquals(0.45f, sink.volume, 0.0f)
            assertEquals(
                "مؤثر فقط لا ينطق عند فصل الشاحن",
                "sentinel-untouched", currentVoiceId()
            )

            // مستوى بدون مؤثر مخصص (50%): سلوك صامت موثق دون السقوط للكلام
            clearDebounceState()
            sink.reset()
            setTestSpeakerVoice("sentinel-untouched")
            var done50 = false
            val handled50 = receiver.handle(
                context, batteryIntent(50), Intent.ACTION_BATTERY_CHANGED,
                onCueDone = { done50 = true }
            )
            ShadowLooper.idleMainLooper()
            assertTrue(handled50)
            assertTrue(done50)
            assertNull("لا مؤثر صوتي لمستوى 50%", sink.played)
            assertEquals(
                "مؤثر فقط لمستوى بلا مؤثر لا يسقط إلى الكلام",
                "sentinel-untouched", currentVoiceId()
            )
        } finally {
            AudioCuePlayer.replaceSharedForTesting(null)
            AnnouncementSpeaker.getInstance(context).shutdown()
        }
    }

    @Test
    fun `narration only mode across levels 20 15 10 100 and charger events`() {
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        settings.setBatteryAnnouncementEnabled(true)
        settings.setBatteryAnnouncementLevels(
            setOf("20", "15", "10", "100")
        )
        settings.setChargingDisconnectAnnouncementEnabled(true)
        settings.setChargingCompleteAnnouncementEnabled(true)
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY, "voice-target"
        )
        settings.setBatterySoundCueMode(1)
        settings.setBatteryCueVolume(0.7f)

        val sink = FakeCueSink()
        AudioCuePlayer.replaceSharedForTesting(
            AudioCuePlayer.forTesting(sink, CueSynth)
        )
        val clock = FakeClock(baseNow)
        val receiver = BatteryAnnouncementReceiver(clock)

        try {
            for (lvl in listOf(20, 15, 10, 100)) {
                clearDebounceState()
                sink.reset()
                setTestSpeakerVoice("sentinel-untouched")
                val handled = receiver.handle(
                    context, batteryIntent(lvl), Intent.ACTION_BATTERY_CHANGED
                )
                ShadowLooper.idleMainLooper()
                assertTrue(handled)
                assertNull("نطق فقط: لا مؤثر صوتي لمستوى $lvl%", sink.played)
                assertEquals(
                    "نطق فقط: تطبيق الصوت لمستوى $lvl%",
                    "voice-target", currentVoiceId()
                )
            }

            // توصيل الشاحن
            clearDebounceState()
            sink.reset()
            setTestSpeakerVoice("sentinel-untouched")
            val handledConnect = receiver.handle(
                context, batteryIntent(50), Intent.ACTION_POWER_CONNECTED
            )
            ShadowLooper.idleMainLooper()
            assertTrue(handledConnect)
            assertNull("نطق فقط: لا مؤثر عند التوصيل", sink.played)
            assertEquals("voice-target", currentVoiceId())

            // فصل الشاحن
            clearDebounceState()
            sink.reset()
            setTestSpeakerVoice("sentinel-untouched")
            val handledDisconnect = receiver.handle(
                context, batteryIntent(50), Intent.ACTION_POWER_DISCONNECTED
            )
            ShadowLooper.idleMainLooper()
            assertTrue(handledDisconnect)
            assertNull("نطق فقط: لا مؤثر عند الفصل", sink.played)
            assertEquals("voice-target", currentVoiceId())
        } finally {
            AudioCuePlayer.replaceSharedForTesting(null)
            AnnouncementSpeaker.getInstance(context).shutdown()
        }
    }

    @Test
    fun `cue and narration mode across levels 20 15 10 100 and charger`() {
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        settings.setBatteryAnnouncementEnabled(true)
        settings.setBatteryAnnouncementLevels(
            setOf("20", "15", "10", "100")
        )
        settings.setChargingDisconnectAnnouncementEnabled(true)
        settings.setChargingCompleteAnnouncementEnabled(true)
        settings.setPreferredVoiceIdForCategory(
            SettingsRepository.VOICE_CATEGORY_BATTERY, "voice-target"
        )
        settings.setBatterySoundCueMode(0)
        settings.setBatteryCueVolume(0.8f)

        val sink = FakeCueSink()
        AudioCuePlayer.replaceSharedForTesting(
            AudioCuePlayer.forTesting(sink, CueSynth)
        )
        val clock = FakeClock(baseNow)
        val receiver = BatteryAnnouncementReceiver(clock)

        try {
            val levelsToTest = listOf(
                20 to CueType.BATTERY_LOW,
                15 to CueType.BATTERY_LOW,
                10 to CueType.BATTERY_LOW,
                100 to CueType.BATTERY_FULL
            )
            for ((lvl, expectedCue) in levelsToTest) {
                clearDebounceState()
                sink.reset()
                setTestSpeakerVoice("sentinel-untouched")
                val handled = receiver.handle(
                    context, batteryIntent(lvl), Intent.ACTION_BATTERY_CHANGED
                )
                ShadowLooper.idleMainLooper()
                assertTrue(handled)
                assertEquals(
                    "نطق ومؤثر: تشغيل المؤثر لمستوى $lvl%",
                    expectedCueSamples(expectedCue),
                    sink.played?.size
                )
                assertEquals(
                    "نطق ومؤثر: تطبيق صوت النطق لمستوى $lvl%",
                    "voice-target", currentVoiceId()
                )
            }

            // توصيل الشاحن
            clearDebounceState()
            sink.reset()
            setTestSpeakerVoice("sentinel-untouched")
            val handledConnect = receiver.handle(
                context, batteryIntent(50), Intent.ACTION_POWER_CONNECTED
            )
            ShadowLooper.idleMainLooper()
            assertTrue(handledConnect)
            assertEquals(
                expectedCueSamples(CueType.BATTERY_CHARGING),
                sink.played?.size
            )
            assertEquals("voice-target", currentVoiceId())

            // فصل الشاحن
            clearDebounceState()
            sink.reset()
            setTestSpeakerVoice("sentinel-untouched")
            val handledDisconnect = receiver.handle(
                context, batteryIntent(50), Intent.ACTION_POWER_DISCONNECTED
            )
            ShadowLooper.idleMainLooper()
            assertTrue(handledDisconnect)
            assertEquals(
                expectedCueSamples(CueType.BATTERY_DISCONNECTED),
                sink.played?.size
            )
            assertEquals("voice-target", currentVoiceId())
        } finally {
            AudioCuePlayer.replaceSharedForTesting(null)
            AnnouncementSpeaker.getInstance(context).shutdown()
        }
    }

    @Test
    fun `charging complete disabled does not silence charging connect`() {
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        settings.setBatteryAnnouncementEnabled(true)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))
        // تعطيل اكتمال الشحن (100%) مع إبقاء إعلان توصيل الشاحن مفعلاً
        settings.setChargingCompleteAnnouncementEnabled(false)
        settings.setChargingConnectAnnouncementEnabled(true)
        settings.setChargingDisconnectAnnouncementEnabled(true)

        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))

        // إعلان توصيل الشاحن يعمل ولا يُسكت
        clearDebounceState()
        val spokeConnect = receiver.handle(
            context, batteryIntent(50), Intent.ACTION_POWER_CONNECTED
        )
        assertTrue(
            "تعطيل اكتمال 100% لا يسكت إعلان توصيل الشاحن",
            spokeConnect
        )

        // إعلان اكتمال الشحن (100%) مسكت
        clearDebounceState()
        val intent100 = batteryIntent(100).apply {
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        val spoke100 = receiver.handle(
            context, intent100, Intent.ACTION_BATTERY_CHANGED
        )
        assertFalse(
            "اكتمال الشحن مسكت لتعطيل ميزته",
            spoke100
        )
    }

    @Test
    fun `charging connect disabled does not silence charging complete`() {
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        settings.setBatteryAnnouncementEnabled(true)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))
        // تعطيل إعلان توصيل الشاحن مع إبقاء إعلان اكتمال الشحن (100%) مفعلاً
        settings.setChargingConnectAnnouncementEnabled(false)
        settings.setChargingCompleteAnnouncementEnabled(true)
        settings.setChargingDisconnectAnnouncementEnabled(true)

        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))

        // إعلان توصيل الشاحن مسكت
        clearDebounceState()
        val spokeConnect = receiver.handle(
            context, batteryIntent(50), Intent.ACTION_POWER_CONNECTED
        )
        assertFalse(
            "إعلان توصيل الشاحن مسكت لتعطيل ميزته",
            spokeConnect
        )

        // إعلان اكتمال الشحن (100%) يعمل
        clearDebounceState()
        val intent100 = batteryIntent(100).apply {
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        val spoke100 = receiver.handle(
            context, intent100, Intent.ACTION_BATTERY_CHANGED
        )
        assertTrue(
            "اكتمال 100% يعمل مستقلاً عن مفتاح التوصيل",
            spoke100
        )
    }

    @Test
    fun `charging disconnect is completely independent`() {
        val settings = SettingsRepository(context)
        settings.setAllAnnouncementsEnabled(true)
        settings.setBatteryAnnouncementEnabled(true)
        settings.setBatteryAnnouncementLevels(setOf("20", "15"))
        // تعطيل التوصيل والاكتمال مع تفعيل الفصل فقط
        settings.setChargingConnectAnnouncementEnabled(false)
        settings.setChargingCompleteAnnouncementEnabled(false)
        settings.setChargingDisconnectAnnouncementEnabled(true)

        val receiver = BatteryAnnouncementReceiver(FakeClock(baseNow))

        clearDebounceState()
        val spokeDisconnect = receiver.handle(
            context, batteryIntent(50), Intent.ACTION_POWER_DISCONNECTED
        )
        assertTrue(
            "إعلان فصل الشاحن يعمل مستقلاً عن التوصيل والاكتمال",
            spokeDisconnect
        )

        clearDebounceState()
        val spokeConnect = receiver.handle(
            context, batteryIntent(50), Intent.ACTION_POWER_CONNECTED
        )
        assertFalse("إعلان التوصيل معطل", spokeConnect)

        // العكس: تعطيل الفصل فقط مع تفعيل التوصيل والاكتمال
        settings.setChargingDisconnectAnnouncementEnabled(false)
        settings.setChargingConnectAnnouncementEnabled(true)
        settings.setChargingCompleteAnnouncementEnabled(true)

        clearDebounceState()
        val spokeDisconnect2 = receiver.handle(
            context, batteryIntent(50), Intent.ACTION_POWER_DISCONNECTED
        )
        assertFalse(
            "إعلان فصل الشاحن مسكت لتعطيل ميزته",
            spokeDisconnect2
        )

        clearDebounceState()
        val spokeConnect2 = receiver.handle(
            context, batteryIntent(50), Intent.ACTION_POWER_CONNECTED
        )
        assertTrue("إعلان التوصيل يعمل عند تفعيله", spokeConnect2)
    }

    @Test
    fun `charging connect migration preserves previous choice`() {
        val prefs = context.getSharedPreferences(
            "nateq_settings", Context.MODE_PRIVATE
        )
        prefs.edit().clear().commit()
        val repo = SettingsRepository(context)

        // 1. عند غياب كلا المفتاحين: الافتراضي مفعل
        assertTrue(repo.isChargingConnectAnnouncementEnabled())

        // 2. ترحيل السلوك السابق: إن كان اكتمال الشحن معطلاً قديماً
        // وغاب مفتاح التوصيل المستقل يُحفظ السلوك السابق المعطل
        prefs.edit()
            .putBoolean("charging_complete_announcement_enabled", false)
            .remove("charging_connect_announcement_enabled")
            .commit()
        assertFalse(repo.isChargingConnectAnnouncementEnabled())

        // 3. التعيين المستقل يحرر المفتاح من التبعية السابقة
        repo.setChargingConnectAnnouncementEnabled(true)
        assertTrue(repo.isChargingConnectAnnouncementEnabled())
        assertFalse(repo.isChargingCompleteAnnouncementEnabled())

        // 4. تغيير مفتاح الاكتمال بعد ذلك لا يؤثر على التوصيل
        repo.setChargingCompleteAnnouncementEnabled(true)
        repo.setChargingConnectAnnouncementEnabled(false)
        assertFalse(repo.isChargingConnectAnnouncementEnabled())
        assertTrue(repo.isChargingCompleteAnnouncementEnabled())
    }
}

/** ساعة افتراضية قابلة للضبط — تحكّم كامل بالخط الزمني في الاختبارات. */
private class FakeClock(private var millis: Long) : TimeProvider {

    override fun currentTimeMillis(): Long = millis

    override fun now(): Calendar =
        Calendar.getInstance().apply { timeInMillis = millis }

    fun setTo(millis: Long) {
        this.millis = millis
    }
}

/** سلك وهمي للمؤثرات يلتقط الموجة والحجم بلا أي صوت حقيقي. */
private class FakeCueSink : CueSink {

    var played: ShortArray? = null
    var volume: Float = 0f

    fun reset() {
        played = null
        volume = 0f
    }

    @Suppress("UNUSED_PARAMETER")
    override fun play(
        pcm: ShortArray,
        sampleRate: Int,
        volume: Float,
        cueKey: String,
        onDone: (Boolean) -> Unit
    ) {
        played = pcm
        this.volume = volume
        onDone(true)
    }

    override fun stop() {}

    override fun release() {}
}