package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.util.LanguageCode
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * يغطي تطبيع رقم المتصل الخاص/المجهول: «-1» و«UNKNOWN» ونظائرهما تُستبعد قبل
 * أي بحث في الخريطة المخصصة أو سجلّ الاتصالات/المكالمات — فلا يُنطق اسم جهة
 * اتصالٍ تتصادف أرقامها (كملحق «1» لرموز أمريكا) لمكالمةٍ مجهولةٍ فعلياً.
 * كما يتأكد أن الأرقام الحقيقية تُنتَج بشكلها الرقمي المجرّد فقط للبحث.
 * ويغطي أيضاً منطق سحب إذن READ_PHONE_STATE: الشفاء الذاتي الذي يطفئ تفعيل
 * إعلان المتصل عند سحبه رغم بقاء التفعيل قائماً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CallerAnnouncementReceiverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<Context>()

    private fun normalize(number: String?): String? {
        val method = CallerAnnouncementReceiver::class.java
            .getDeclaredMethod("normalizeCallerNumber", String::class.java)
        method.isAccessible = true
        return method.invoke(CallerAnnouncementReceiver(), number) as String?
    }

    @Test
    fun `unknown marker is rejected`() {
        assertNull(normalize("-1"))
        assertNull(normalize("UNKNOWN"))
        assertNull(normalize("unknown"))
        assertNull(normalize("Unknown Number"))
        assertNull(normalize("0"))
    }

    @Test
    fun `blank number is rejected`() {
        assertNull(normalize(null))
        assertNull(normalize(""))
        assertNull(normalize("   "))
    }

    @Test
    fun `real number yields digits only`() {
        assertEquals("06370912", normalize("06370912"))
        assertEquals("639171234567", normalize("+639171234567"))
        assertEquals("06370912", normalize(" 06 370 91 2 "))
    }

    @Test
    fun `non numeric junk is rejected`() {
        assertNull(normalize("caller-id"))
    }

    @Test
    fun `repeatSchedule honors interval and skips window edge`() {
        assertEquals(
            listOf(2500L, 5000L, 7500L),
            CallerAnnouncementReceiver.repeatSchedule(5, 2500L, 10_000L)
        )
    }

    @Test
    fun `repeatSchedule includes only in-window ticks`() {
        assertEquals(
            listOf(2000L, 4000L, 6000L, 8000L),
            CallerAnnouncementReceiver.repeatSchedule(5, 2000L, 10_000L)
        )
    }

    @Test
    fun `repeatSchedule is empty for single or oversized interval`() {
        assertEquals(
            emptyList<Long>(),
            CallerAnnouncementReceiver.repeatSchedule(1, 1000L, 10_000L)
        )
        assertEquals(
            emptyList<Long>(),
            CallerAnnouncementReceiver.repeatSchedule(5, 10_000L, 10_000L)
        )
    }

    /**
     * حارس: **«عشر مرات» يجب أن تُنطق عشراً في كل فاصلٍ مسموح.**
     *
     * الكسر الذي يحرسه: نافذةُ الجدولة كانت 10 ثوانٍ، فاختيارُ عشرِ
     * تكراراتٍ عند فاصل 3 ثوانٍ كان يُحسب ثلاثَ نبضاتٍ فقط ويُنطق
     * ما دونها — إسقاطٌ صامتٌ لاختيارِ المستخدم. والاختبارُ يبني
     * الجدولَ بنفس نافذةِ الإنتاج ([CALLER_WAKE_LOCK_CAP_MS] بوصفها
     * الحدَّ الأعلى للمحسوب) فيتحقّق أن عددَ الإزاحات = عددُ التكرارات
     * ناقصَ واحد، عند أسوأ فاصل (5 ثوانٍ) وأدناه (3 ثوانٍ).
     */
    @Test
    fun `ten repeats survive the schedule window at worst interval`() {
        val worst = listOf(3_000L, 5_000L)
        worst.forEach { interval ->
            val ticks = CallerAnnouncementReceiver.repeatSchedule(
                10, interval,
                CallerAnnouncementReceiver.CALLER_WAKE_LOCK_CAP_MS
            )
            assertEquals(
                "عشرُ تكراراتٍ عند فاصل ${interval}ms", 9, ticks.size
            )
            // ولا تتجاوز النافذةَ سقفَ قفل الاستيقاظ (وإلا قُطع النطق)
            ticks.forEach { assertTrue(it < 70_000L) }
        }
    }

    /**
     * حارسُ عقد **«لا نطقَ فوق مكالمةٍ جارية»** — الكسرُ الذي يحرسه:
     * حلقةُ التكرار كانت تنفّذ `delay` ثم تنطق بلا أي فحص، فلا يوقفها
     * إلا `OFFHOOK`؛ ومكالماتُ التطبيقات (VoIP) لا يُبَثّ لها
     * `PHONE_STATE` إطلاقاً ولا يُلغى إشعارُها إلا بالحذف — فردُّ
     * المستخدم يُبقي الإشعارَ «جارياً» فيمتدّ اسمه فوق مكالمته حتى آخر
     * نبضةٍ في الدقيقة (عشرُ مرّاتٍ افتراضياً).
     */
    @Test
    fun `repeats stop once a VoIP call is answered`() {
        // الرنينُ جارٍ: التكرارُ مشروع.
        assertTrue(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                notificationStillRinging = true,
                callAnnouncedAnswered = false,
                networkCallAnswered = false
            )
        )
        // **الكسر:** إشعارُ مكالمة التطبيق ما زال حيّاً (لم يُحذف)، لكن
        // الدليلُ الصريح على الردّ («مكالمة جارية») وصل — فالتكرارُ
        // يجب أن يتوقف. هذا ما كان ينطق فوق مكالمة المستخدم.
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                notificationStillRinging = true,
                callAnnouncedAnswered = true,
                networkCallAnswered = false
            )
        )
    }

    /**
     * الحارسُ نفسُه لمكالمة الشبكة: `OFFHOOK` يُعيد قراءته داخل الحلقة
     * لأن الإلغاءَ قد يسبق تحققَ الحلقة فيسباقِ `delay`.
     */
    @Test
    fun `repeats stop once a network call is answered`() {
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                notificationStillRinging = true,
                callAnnouncedAnswered = false,
                networkCallAnswered = true
            )
        )
    }

    /** ولا يتوقف التكرارُ قبل الأوان: «لا أعرف» ليست «انتهى». */
    @Test
    fun `unknown state keeps repeating`() {
        assertTrue(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                notificationStillRinging = true,
                callAnnouncedAnswered = false,
                networkCallAnswered = false
            )
        )
    }

    /**
     * حذفُ إشعار المكالمة (انتهاءُها) يوقف التكرار كذلك — والمُدخلُ
     * الأولُ للحارس هو حالةُ `notificationCallActive`.
     */
    @Test
    fun `dismissed call notification stops repeating`() {
        assertFalse(
            CallerAnnouncementReceiver.shouldContinueRepeating(
                notificationStillRinging = false,
                callAnnouncedAnswered = false,
                networkCallAnswered = false
            )
        )
    }

    /**
     * **الإثباتُ بالإيجاب لا بغياب الدليل:** نصُّ إعلانِ مكالمةٍ **واردة**
     * أو **صادرة** لا يُحسب دليلَ ردٍّ أبداً — وإلا انحدر إعلانُ
     * جوجل ميت الوارد («Ringing tone…») وهو إصلاحٌ سابق (بند 5.6).
     */
    @Test
    fun `incoming and outgoing ringing texts are not answered marks`() {
        val ringing = listOf(
            "Ringing tone…",
            "incoming call",
            "Incoming voice call",
            "مكالمة واردة",
            "جار الاتصال",
            "جارٍ الاتصال"
        )
        ringing.forEach {
            assertFalse(
                "نصٌّ رنٍّ لا يُحسب دليلَ ردٍّ: $it",
                RingCallerIdentity.isAnsweredCallPhrase(it)
            )
        }
    }

    /** ونقيضُها: عباراتُ الجوارحة الصريحة دليلٌ قاطعٌ على الردّ. */
    @Test
    fun `explicit ongoing phrases are answered marks`() {
        val answered = listOf(
            "Ongoing call",
            "ongoing call",
            "Active call",
            "مكالمة جارية",
            "مكالمة نشطة",
            "مكالمة متصلة"
        )
        answered.forEach {
            assertTrue(
                "عبارةُ جوارحةٍ صريحة دليلُ ردٍّ: $it",
                RingCallerIdentity.isAnsweredCallPhrase(it)
            )
        }
    }

    // حارس: الحدّان متّسقان بين المخزن والواجهة والاستهلاك — فأيُّ حدٍّ
    // منفرد بلا حدٍّ في الموضع يصنع خياراً ميتاً في الواجهة
    // والتخزين (قاعدة «الحدّ الواحد في ثلاثة مواضع»).
    @Test
    fun `repeat bounds agree across repository and consumer`() {
        assertEquals(1, SettingsRepository.CALLER_REPEAT_MIN)
        assertEquals(10, SettingsRepository.CALLER_REPEAT_MAX)
        // والفاصل: الأقصرُ 3 فيولّد آخرَ إزاحةٍ عند 45 ثانية، أقلّ من
        // السقف 60 الذي تحسبه نافذةُ الجدولة — فلا اقتطاعَ مع العشر.
        val lastTick = CallerAnnouncementReceiver.repeatSchedule(
            SettingsRepository.CALLER_REPEAT_MAX,
            SettingsRepository.CALLER_INTERVAL_MAX * 1000L,
            60_000L
        ).last()
        assertEquals(45_000L, lastTick)
    }

    private fun buildPhrase(
        number: String?,
        contactName: String?,
        readingMode: Int = 1
    ): String {
        val method = CallerAnnouncementReceiver::class.java
            .getDeclaredMethod(
                "buildDefaultCallerPhrase",
                Context::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
        method.isAccessible = true
        return method.invoke(
            CallerAnnouncementReceiver(),
            context, number, contactName, readingMode
        ) as String
    }

    @Test
    fun `blank number without call log permission yields no speech at all`() {
        val app = ApplicationProvider
            .getApplicationContext<android.app.Application>()
        shadowOf(app).denyPermissions(
            android.Manifest.permission.READ_CALL_LOG
        )
        // **العقد الحاكم بعد تغيير المستخدم:** بلا هوية لا نطقَ البتّة.
        // العبارةُ العامة «اتصال وارد» حُذفت نهائياً، فلم يبقَ نصٌّ
        // يُقال بلا اسمٍ ولا رقم — ومعها حُذف التلميحُ عن السجل (كان
        // يقرؤه المستخدم كأنه معلومة عن المتصل وهي ليست كذلك).
        // البديلُ نغمةُ التنبيه + اهتزاز قبل الوصول إلى هنا.
        assertEquals(
            "بلا هوية: لا نصّ ولا تلميح",
            "",
            buildPhrase(number = null, contactName = null)
        )
    }

    @Test
    fun `blank number with call log granted also yields no speech`() {
        val app = ApplicationProvider
            .getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_CALL_LOG
        )
        // الإذن ممنوح والرقم ما زال فارغاً (حجبٌ حقيقي): النتيجةُ
        // نفسها — الصمت، لا عبارةَ عامة.
        assertEquals(
            "",
            buildPhrase(number = null, contactName = null)
        )
    }

    private fun match(key: String, to: String?): String? =
        CallerAnnouncementReceiver().matchCustomName(
            mapOf(key to "أحمد"), to
        )

    @Test
    fun `formatCallerNumberForSpeech speaks digits one by one`() {
        // بند 3.2/3.1: رقم المتصل المجهول يُنطق رقماً رقماً (عربياً أو
        // بالإنجليزية) ويُعاد فارغاً عند غياب الأرقام.
        assertEquals(
            " صِفْرْ ستة ثلاثة سبعة صِفْرْ تسعة واحد اثنان",
            formatCallerNumberForSpeech("06370912", isArabic = true)
        )
        assertEquals(
            " plus six three nine",
            formatCallerNumberForSpeech("+639", isArabic = false)
        )
        assertEquals(
            " زائد اثنان صِفْرْ واحد واحد",
            formatCallerNumberForSpeech("+2011", isArabic = true)
        )
        assertEquals("", formatCallerNumberForSpeech("غير محدد", true))
    }

    @Test
    fun `formatCallerNumberForSpeech respects reading mode`() {
        // نطق رقم المتصل في أزواج (mode 2)
        assertEquals(
            " زائد عشرون, أحد عشر",
            formatCallerNumberForSpeech("+2011", isArabic = true, mode = 2)
        )
        // نطق رقم المتصل في مجموعات ثلاثية (mode 3)
        assertEquals(
            " plus two, zero one one",
            formatCallerNumberForSpeech("+2011", isArabic = false, mode = 3)
        )
    }

    @Test
    fun `formatCallerNumberForSpeech with diverse formats`() {
        val expectedLocalAr = " صِفْرْ واحد صِفْرْ واحد اثنان ثلاثة " +
            "أربعة خمسة ستة سبعة ثمانية"
        val expectedIntlAr = " زائد اثنان صِفْرْ واحد صِفْرْ واحد " +
            "اثنان ثلاثة أربعة خمسة ستة سبعة ثمانية"

        // أرقام محلية بصيغ متنوعة تنتج نفس النص المنطوق دائماً
        val localVariants = listOf(
            "01012345678",
            "010-1234-5678",
            "010 1234 5678",
            "(010) 1234-5678",
            "010,1234,5678",
            "010.1234.5678",
            "٠١٠١٢٣٤٥٦٧٨"
        )
        for (num in localVariants) {
            assertEquals(
                "نطق متطابق للرقم المحلي: $num",
                expectedLocalAr,
                formatCallerNumberForSpeech(num, isArabic = true)
            )
        }

        // أرقام دولية بـ +20 بصيغ متنوعة تنتج نفس النص المنطوق ببادئة زائد
        val intlVariants = listOf(
            "+201012345678",
            "+20 10 1234 5678",
            "+20-10-1234-5678",
            "+20 (10) 1234-5678",
            "+20,10,1234,5678",
            "+20.10.1234.5678",
            "+٢٠١٠١٢٣٤٥٦٧٨"
        )
        for (num in intlVariants) {
            assertEquals(
                "نطق متطابق للرقم الدولي: $num",
                expectedIntlAr,
                formatCallerNumberForSpeech(num, isArabic = true)
            )
        }

        // نمط التجميع الثنائي (mode 2) للأرقام الدولية
        val expectedPairsAr = " زائد عشرون, عشرة, اثنا عشر, " +
            "أربعة وثلاثون, ستة وخمسون, ثمانية وسبعون"
        assertEquals(
            expectedPairsAr,
            formatCallerNumberForSpeech(
                "+201012345678", isArabic = true, mode = 2
            )
        )
        assertEquals(
            " plus twenty, ten, twelve, thirty four, fifty six, seventy eight",
            formatCallerNumberForSpeech(
                "+201012345678", isArabic = false, mode = 2
            )
        )

        // التحقق من أن كل الأنماط 1..4 تنتج نصوصاً غير فارغة
        for (num in localVariants + intlVariants) {
            for (m in 1..4) {
                val ar = formatCallerNumberForSpeech(
                    num, isArabic = true, mode = m
                )
                assertTrue("AR mode $m non-empty for $num", ar.isNotBlank())
                val en = formatCallerNumberForSpeech(
                    num, isArabic = false, mode = m
                )
                assertTrue("EN mode $m non-empty for $num", en.isNotBlank())
            }
        }
    }

    @Test
    fun `custom name matches exact digits`() {
        assertEquals("أحمد", match("0637091234", "0637091234"))
    }

    @Test
    fun `custom name ignores incoming formatting`() {
        assertEquals(
            "أحمد",
            match("+966 50 123 4567", " (050) 123-4567 ")
        )
    }

    @Test
    fun `custom name matches formatted variant through PhoneNumberUtils`() {
        assertEquals(
            "أحمد",
            match("+966501234567", "966501234567")
        )
    }

    @Test
    fun `custom name matches last eight digits when prefix differs`() {
        assertEquals(
            "أحمد",
            match("00966501234567", "+966501234567")
        )
        assertEquals(
            "أحمد",
            match("966501234567", "0501234567")
        )
    }

    @Test
    fun `custom name matches local number whose digits equal intl tail`() {
        // شكل محليٍّ مقابل الدولي حيث الأقصرُ ذيلُ الأطول: يُقبل عبر
        // PhoneNumberUtils مع الحارس — كان تُفقد هذه الحالة حين يقل طولُ
        // المحلي عن عتبة النافذة الثماني (مثل نواة من 7 خانات ورمز بلد).
        assertEquals(
            "أحمد",
            match("+9665012347", "5012347")
        )
        assertEquals(
            "أحمد",
            match("966501234567", "501234567")
        )
        assertEquals(
            "أحمد",
            match("+966 50 123-4567", "501234567")
        )
    }

    @Test
    fun `custom name rejects divergent intl tails`() {
        // الأقصر ليس ذيلَ الأطول — رغم تطابق آخر 7 خاناتٍ معتبَرٍ لدى
        // PhoneNumberUtils، الحارس يرفضه فلا يتسرب تقاربُ فئة محلية.
        assertNull(match("966501234567", "501987654"))
        assertNull(match("+9665055 11 22", "52112233"))
    }

    @Test
    fun `custom name rejects short or divergent numbers`() {
        assertNull(match("0501234567", "0511234567"))
        assertNull(match("5551234", "5559999"))
        assertNull(match("0501234567", "-1"))
        assertNull(match("0501234567", "UNKNOWN"))
    }

    @Test
    fun `permission revoked disables enabled and syncs`() {
        val repo = SettingsRepository(context)
        repo.setCallerAnnouncementEnabled(true)
        CallerAnnouncementReceiver()
            .disableAfterPermissionRevoked(repo, context)
        assertFalse(
            "سحب الإذن يطفئ التفعيل القائم",
            repo.isCallerAnnouncementEnabled()
        )
    }

    @Test
    fun `permission revoked leaves disabled untouched`() {
        val repo = SettingsRepository(context)
        repo.setCallerAnnouncementEnabled(false)
        CallerAnnouncementReceiver()
            .disableAfterPermissionRevoked(repo, context)
        assertFalse(repo.isCallerAnnouncementEnabled())
    }

    @Test
    fun `hasCallerPermission reflects granted state`() {
        val receiver = CallerAnnouncementReceiver()
        val app = ApplicationProvider
            .getApplicationContext<android.app.Application>()
        shadowOf(app).denyPermissions(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG
        )
        assertFalse(
            "بلا أذونات نعتبر الإذن غائباً",
            receiver.hasCallerPermission(context)
        )
        shadowOf(app)
            .grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        assertFalse(
            "على أندرويد 12+ لا يكفي READ_PHONE_STATE وحده —" +
                " فبدون READ_CALL_LOG لا يصل رقم المتصل",
            receiver.hasCallerPermission(context)
        )
        shadowOf(app)
            .grantPermissions(android.Manifest.permission.READ_CALL_LOG)
        assertTrue(
            "بمنح الإذنين معاً يُنطق الاسم فعلياً",
            receiver.hasCallerPermission(context)
        )
    }

    @Test
    fun `callerSpeechLanguage follows the name not the template`() {
        // اسم عربي → عربي
        assertEquals(
            LanguageCode.AR.tag,
            callerSpeechLanguage("أحمد", "0501234567")
        )
        // اسم لاتيني → إنجليزي (كان مَسْتَنَداً للنص الكامل عربياً)
        assertEquals(
            LanguageCode.EN.tag,
            callerSpeechLanguage("Ahmed Smith", "0501234567")
        )
        // رقم بلا اسم (أو بلا حروف أصلاً) → عربي كعبارة عامة
        assertEquals(
            LanguageCode.AR.tag,
            callerSpeechLanguage(null, "0501234567")
        )
        assertEquals(
            LanguageCode.AR.tag,
            callerSpeechLanguage("", "0501234567")
        )
        assertEquals(
            LanguageCode.AR.tag,
            callerSpeechLanguage("٠٥٠١٢٣٤٥٦٧", null)
        )
    }

    @Test
    fun `during call announcement defaults off and round trips`() {
        val repo = SettingsRepository(context)
        assertFalse(repo.isCallerAnnouncementDuringCallEnabled())
        repo.setCallerAnnouncementDuringCallEnabled(true)
        assertTrue(repo.isCallerAnnouncementDuringCallEnabled())
        repo.setCallerAnnouncementDuringCallEnabled(false)
        assertFalse(repo.isCallerAnnouncementDuringCallEnabled())
    }

@Test
fun `waiting call ring is detected over an active call`() {
    // مكالمة انتظار: رنين بعد OFFHOOK مباشرة، أو رنّات متكررة للرنين
    // نفسه مع استمرار علم المكالمة النشطة.
    assertTrue(
        CallerAnnouncementReceiver.isWaitingCall(
            TelephonyManager.EXTRA_STATE_OFFHOOK, true
        )
    )
    assertTrue(
        CallerAnnouncementReceiver.isWaitingCall(
            TelephonyManager.EXTRA_STATE_RINGING, true
        )
    )
}

@Test
fun `waiting call speaks only when during call toggle enabled`() {
    // رنين الانتظار (خلف مكالمة نشطة) يُنطق عند تفعيل «نطق اسم المتصل
    // أثناء المكالمة» فقط — وقد عُدِّل مسار التكرار ليطبّق جدول الإعدادات
    // نفسه على مكالمة الانتظار بدل تخفيفها لنطقٍ واحد.
    val repo = SettingsRepository(context)
    repo.setCallerAnnouncementEnabled(true)
    repo.setAllAnnouncementsEnabled(true)
    repo.setCallerAnnouncementDuringCallEnabled(false)
    try {
        val receiver = CallerAnnouncementReceiver()
        assertFalse(
            "انتظار بلا مفتاحه: لا يُنطق",
            receiver.announceIncomingCall(
                context = context,
                settings = repo,
                incomingNumber = "0501234567",
                previousState = TelephonyManager.EXTRA_STATE_OFFHOOK,
                callActive = true
            )
        )
        repo.setCallerAnnouncementDuringCallEnabled(true)
        ShadowTextToSpeech.reset()
        assertTrue(
            "انتظار بمفتاحه: يُنطق",
            receiver.announceIncomingCall(
                context = context,
                settings = repo,
                incomingNumber = "0501234567",
                previousState = TelephonyManager.EXTRA_STATE_OFFHOOK,
                callActive = true
            )
        )
    } finally {
        repo.setCallerAnnouncementDuringCallEnabled(false)
        repo.setAllAnnouncementsEnabled(false)
    }
}

    @Test
    fun `plain idle ring is not a waiting call`() {
        assertFalse(
            CallerAnnouncementReceiver.isWaitingCall(
                TelephonyManager.EXTRA_STATE_IDLE, false
            )
        )
        assertFalse(
            CallerAnnouncementReceiver.isWaitingCall(
                TelephonyManager.EXTRA_STATE_RINGING, false
            )
        )
    }

    @Test
    fun `call reaches speech engine even after prewarm invocation`() {
        val app = ApplicationProvider
            .getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CALL_LOG
        )
        val settings = SettingsRepository(context)
        settings.setCallerAnnouncementEnabled(true)
        settings.setAllAnnouncementsEnabled(true)

        ShadowTextToSpeech.reset()

        val speaker = AnnouncementSpeaker.getInstance(context)
        // محاكاة تدفئة مسبقة متزامنة/سابقة لمحرك النطق (بند التسريع)
        speaker.prewarm()

        var completionNotified = false
        val listener = { completionNotified = true }
        speaker.addCompletionListener(listener)

        val receiver = CallerAnnouncementReceiver()

        try {
            val announced = receiver.announceIncomingCall(
                context = context,
                settings = settings,
                incomingNumber = "0501234567"
            )
            assertTrue("بث المكالمة الواردة يعالج النطق بنجاح", announced)
            var iterations = 0
            while (!completionNotified &&
                ShadowTextToSpeech.getLastTextToSpeechInstance() == null &&
                iterations < 40
            ) {
                ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
                Thread.sleep(25)
                iterations++
            }
            ShadowLooper.idleMainLooper()

            val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
            val spoken = tts?.let { shadowOf(it).lastSpokenText }
            assertTrue(
                "النطق يصل لمحرك TTS حتى عند وجود تدفئة مسبقة سابقة للمكالمة",
                completionNotified || !spoken.isNullOrBlank() || tts != null
            )
        } finally {
            speaker.removeCompletionListener(listener)
            speaker.shutdown()
        }
    }

    @Test
    fun `resolveLatestCallFromLog returns null when permission denied`() {
        val receiver = CallerAnnouncementReceiver()
        val result = receiver.resolveLatestCallFromLog(
            context, hasReadCallLog = false
        )
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `resetRingingSession clears all ringing state`() {
        CallerAnnouncementReceiver.lastResolvedNumber = "01012345678"
        CallerAnnouncementReceiver.lastResolvedName = "أحمد"
        CallerAnnouncementReceiver.ringingStartTime = 12345L
        CallerAnnouncementReceiver.ringingAnnounced = true
        CallerAnnouncementReceiver.announcedNumber = "01012345678"

        CallerAnnouncementReceiver.resetRingingSession()

        assertNull(CallerAnnouncementReceiver.lastResolvedNumber)
        assertNull(CallerAnnouncementReceiver.lastResolvedName)
        assertEquals(0L, CallerAnnouncementReceiver.ringingStartTime)
        assertFalse(CallerAnnouncementReceiver.ringingAnnounced)
        // **حارسُ التكرار يعتمد حالةَ الجلسة، فتصفيرُها إجباريٌّ وإلا
        // صمت إعلانُ المكالمة التالية إلى الأبد.**
        assertNull(CallerAnnouncementReceiver.announcedNumber)
    }

    /**
     * حارسُ منع تكرار إعلان المتصل.
     *
     * **العَرَض المُبلّغ:** في بعض الهواتف يُنطق «اتصال وارد» ثلاث مرّات
     * ثم رابعاً بالاسم — لأن الجهاز يرسل `PHONE_STATE/RINGING` أكثر من
     * مرّة للمكالمة الواحددة، وكان الحارسُ معلقاً على `activeCallCycle`
     * (عمرُ كوروثين ينتهي فور `speak()`) فيموت بعد أول إعلان. والحارسُ
     * الجديد على حالةِ الجلسة الدائمة فيصمد بعد موت الكوروثين.
     */
    @Test
    fun `duplicate ring with same number is suppressed after announce`() {
        assertTrue(
            "بثّ RINGING مكرر لنفس الرقم يجب أن يُكبَت",
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = true,
                announcedNumber = "01012345678",
                incomingNumber = "01012345678"
            )
        )
    }

    @Test
    fun `duplicate ring without number is suppressed after announce`() {
        // بعض الأجهزة تحجب الرقم في البثّ الثاني: غيابُ الرقم ليس
        // دليلاً على مكالمة جديدة.
        assertTrue(
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = true,
                announcedNumber = "01012345678",
                incomingNumber = null
            )
        )
    }

    @Test
    fun `first ring is never suppressed`() {
        // **الأهم:** لا يُكبَتُ أول إعلانٍ أبداً.
        assertFalse(
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = false,
                announcedNumber = null,
                incomingNumber = "01012345678"
            )
        )
    }

    @Test
    fun `a different number is treated as a new call`() {
        // مكالمةٌ جديدةٌ من رقمٍ آخر يجب أن تُعلَن ولو كان قد سبق
        // إعلانٌ في الجلسة قبل إنهائها.
        assertFalse(
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = true,
                announcedNumber = "01012345678",
                incomingNumber = "01087654321"
            )
        )
    }

    @Test
    fun `a late numbered ring after a numberless announce is suppressed`() {
        // **هذا هو العيب المُصلَح، وحالةُ الجهاز المرصودة بالضبط.**
        // المنصّة ترسل RINGING مرّتين: الأولى بلا رقم (فيُعلن «اتصال
        // وارد» و`announcedNumber` يبقى `null`)، والثانية بعد ~٦ ثوانٍ
        // ومعها الرقم. كان `"01287308580" == null` يقيَّم خطأً فيفتح
        // دورةً ثانية كاملة، فينطق ٥ بلا اسم ثم ٥ بالاسم — ضعفَ
        // التكرار المضبوط. إعلانٌ بلا رقم = «الرقم لم يُعرف بعد»،
        // لا «هذا رقمٌ آخر»، فبثٌّ لاحق يحمل رقماً لا يعيد التكرار.
        assertTrue(
            "بثٌّ متأخّر يحمل رقماً بعد إعلانٍ بلا رقم يجب أن يُكبَت",
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = true,
                announcedNumber = null,
                incomingNumber = "01287308580"
            )
        )
    }

    @Test
    fun `a blank announced number is treated as unknown`() {
        // الفراغُ في `announcedNumber` يعني الرقمَ غيرَ المحفوظ، لا
        // رقماً فارغاً يُقارَن.
        assertTrue(
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = true,
                announcedNumber = "  ",
                incomingNumber = "01287308580"
            )
        )
    }

    @Test
    fun `the wake lock covers the grace period and the speech`() {
        // قفل الاستيقاظ كان يُكتسب **بعد** حلقة الانتظار، فمع مهلةٍ
        // سبعَ ثوانٍ كان الانتظار يجري بلا استيقاظ فيتأخّر `delay()`
        // مع شاشةٍ مطفأة. سقفُ القفل يجب أن يتجاوز الانتظارَ والنطقَ معاً.
        val grace = CallerAnnouncementReceiver
            .CALLER_RESOLVE_GRACE_PERIOD_MS
        val lockCap = CallerAnnouncementReceiver
            .CALLER_WAKE_LOCK_CAP_MS
        assertTrue(
            "سقف قفل الاستيقاظ يجب أن يتجاوز مهلة الانتظار",
            lockCap > grace
        )
        assertTrue(
            "سقف قفل الاستيقاظ يجب أن يتجاوز مهلة الانتظار + نطق الجملة",
            lockCap >= grace + TimeAlarmReceiver.SHORT_WAKE_LOCK_MS
        )
    }

    @Test
    fun `the anr safe cap stays under the system broadcast limit`() {
        // **حارس عقدِ لا يُرفع:** إنهاءُ goAsync متأخّراً عن حافة النظام
        // يُجمد العملية (Process Cgroup Freezer) ويقطع الصوت بلا خطأ.
        // فتمديدُ مهلة انتظار الرقم إلى سبع ثوانٍ يجب ألّا يمسّ هذا السقف
        // أبداً — يُرفع قفلُ الاستيقاظ فقط.
        val cap = CallerAnnouncementReceiver.BROADCAST_SAFE_CAP_MS
        assertTrue(
            "سقف ANR يجب أن يبقى دون مهلة بثّ النظام (~10s)",
            cap < 10_000L
        )
        assertTrue(
            "سقف ANR يجب ألّا ينزل تحت 9 ثوانٍ فيُقصّر نافذة النطق",
            cap >= 9_000L
        )
    }

    @Test
    fun `duplicate ring carrying the same number joins the pending cycle`() {
        // **هذا هو العيب المُصلَح:** كان انضمامُ البث المكرّر مشروطاً
        // بغياب الرقم، فبثٌّ يحمل الرقم نفسه كان يُلغي الدورةَ الجارية
        // ويفتح أخرى — والنطقُ القديم داخل speak() قد لا يُلغى فينطق
        // مرّتين. الآن يتطابق الرقمان فينضمّ ولا يُستبدَل.
        assertTrue(
            CallerAnnouncementReceiver.shouldJoinPendingCycle(
                alreadyAnnounced = false,
                cycleActive = true,
                pendingNumber = "01012345678",
                incomingNumber = "01012345678"
            )
        )
    }

    @Test
    fun `duplicate ring with no number still joins the pending cycle`() {
        assertTrue(
            CallerAnnouncementReceiver.shouldJoinPendingCycle(
                alreadyAnnounced = false,
                cycleActive = true,
                pendingNumber = "01012345678",
                incomingNumber = null
            )
        )
    }

    @Test
    fun `pending cycle with no number absorbs a later numbered ring`() {
        // الرقم، فيجب ألّا تُلغى الدورةُ بسبب مجيئه متأخّراً.
        assertTrue(
            CallerAnnouncementReceiver.shouldJoinPendingCycle(
                alreadyAnnounced = false,
                cycleActive = true,
                pendingNumber = null,
                incomingNumber = "01012345678"
            )
        )
    }

    @Test
    fun `a different number replaces the pending cycle`() {
        // رقمان مختلفان غير فارغين = مكالمتان حقيقيتان.
        assertFalse(
            CallerAnnouncementReceiver.shouldJoinPendingCycle(
                alreadyAnnounced = false,
                cycleActive = true,
                pendingNumber = "01012345678",
                incomingNumber = "01087654321"
            )
        )
    }

    @Test
    fun `a ring never joins after the announcement was made`() {
        // بعد أول إعلان يتكفّل به الحارس الأعلى لا هذا.
        assertFalse(
            CallerAnnouncementReceiver.shouldJoinPendingCycle(
                alreadyAnnounced = true,
                cycleActive = true,
                pendingNumber = "01012345678",
                incomingNumber = "01012345678"
            )
        )
    }

    @Test
    fun `no pending cycle means nothing to join`() {
        assertFalse(
            CallerAnnouncementReceiver.shouldJoinPendingCycle(
                alreadyAnnounced = false,
                cycleActive = false,
                pendingNumber = "01012345678",
                incomingNumber = "01012345678"
            )
        )
    }

    @Test
    fun `announced number is captured once and survives name resolution`() {
        // **بصمةُ العطل المُبلّغ:** «اتصال وارد» ثلاثاً ثم الاسم في
        // الرابعة. التكرارُ المبرمج يولّد ثلاثاً، والرابعة دورةٌ
        // جديدةٌ أعادها بثّ مكرر — واسمُها ظهر متأخراً.
        CallerAnnouncementReceiver.resetRingingSession()
        CallerAnnouncementReceiver.ringingAnnounced = true
        if (CallerAnnouncementReceiver.announcedNumber == null) {
            CallerAnnouncementReceiver.announcedNumber = "01012345678"
        }
        // إعادةُ حلّ الاسم في نبضة تكرارٍ لاحقة لا يجوز أن تُغيّر
        // رقمَ ما أُعلن (وإلا عاد الحارسُ إلى نقطة الصفر).
        CallerAnnouncementReceiver.lastResolvedName = "أحمد"
        assertEquals(
            "01012345678",
            CallerAnnouncementReceiver.announcedNumber
        )
        assertTrue(
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = CallerAnnouncementReceiver.ringingAnnounced,
                announcedNumber = CallerAnnouncementReceiver.announcedNumber,
                incomingNumber = "01012345678"
            )
        )
        CallerAnnouncementReceiver.resetRingingSession()
    }

    @Test
    fun `instant identity grace is short enough to feel immediate`() {
        // **عقد المستخدم:** لا صمت طويل قبل أول كلمة. مهلة الهوية
        // الفورية قصيرةٌ عمداً (١٫٥s) لأنها تنتظر مصادرَ فورية (إشعار
        // الهاتف وخدمة الفرز)، لا فجوةَ الشبكة التي تُغطّى بمهلة
        // الهوية المتأخرة.
        assertTrue(
            "مهلة الهوية الفورية يجب ألا تتجاوز ثانيتين" +
                " (صمتٌ طويل يُفقد المكالمة)",
            CallerAnnouncementReceiver.CALLER_RESOLVE_GRACE_PERIOD_MS <=
                2_000L
        )
        assertTrue(
            CallerAnnouncementReceiver.CALLER_LOG_POLL_INTERVAL_MS in
                100L..500L
        )
        assertTrue(
            CallerAnnouncementReceiver.CALLER_RESOLVE_GRACE_PERIOD_MS >
                CallerAnnouncementReceiver.CALLER_LOG_POLL_INTERVAL_MS
        )
    }

    /**
     * حارسُ **تأخير النطق** (شُكوى: «يتأخر لما بينطق»):
     * فاصلُ فحص الذاكرة المجانية يجب أن يكون **أدقّ بفارقٍ كبير** من
     * فاصل سجلّ المكالمات الغالي — وإلا لبقي النطقُ ينتظر 300ms وهو
     * يحمل الاسمَ في [RingCallerIdentity]. فالمقارنةُ حارسةٌ Niemand
     * يستطيع تسريعَ المسار بلا أن تكسرها.
     */
    @Test
    fun `identity poll is far faster than the expensive call log poll`() {
        val identity =
            CallerAnnouncementReceiver.CALLER_IDENTITY_POLL_INTERVAL_MS
        val log = CallerAnnouncementReceiver.CALLER_LOG_POLL_INTERVAL_MS
        assertTrue(
            "فحص الذاكرة يجب أن يكون أسرع من سجلّ المكالمات",
            identity < log
        )
        assertTrue(
            "وفاصلُ الذاكرة صغيرٌ بما يكفي لئلا يُحَسّ الانتظار",
            identity in 10L..100L
        )
        assertTrue(
            "واستمرارُ الفاصلين داخل مهلة السماح فلا يتجاوزها الانتظار",
            CallerAnnouncementReceiver.CALLER_RESOLVE_GRACE_PERIOD_MS >
                identity
        )
    }

    /**
     * العقدُ الأهمّ في إصلاح التأخير: **الهويةُ المُعبَّأةُ سلفاً في
     * الذاكرة تُلتقط بلا أيّ نوم**. لولا ذلك لدفعنا 300ms ثابتةً على
     * كل مكالمة VoIP لأن الحلقة كانت تنام قبل أوّل فحص.
     */
    @Test
    fun `an identity already in shared memory resolves without waiting`() {
        // نحاكي الحلقةَ بعد إصلاحها: الفحصُ يسبق النوم، فالهويةُ الجاهزة
        // تُكتشف في الدورةِ الأولى وكلفةُ انتظارٍ صفر.
        RingCallerIdentity.publish("+15551234567", "أحمد")
        val startedAt = System.currentTimeMillis()
        var resolved: String? = null
        var waited = 0L
        val grace = CallerAnnouncementReceiver.CALLER_RESOLVE_GRACE_PERIOD_MS
        var nextLogPollAt = 0L
        val hasCallLog = false
        while (waited < grace) {
            val merged = RingCallerIdentity.snapshot()
            if (!merged.second.isNullOrBlank()) {
                resolved = merged.second
                break
            }
            if (hasCallLog &&
                System.currentTimeMillis() >= nextLogPollAt
            ) {
                nextLogPollAt = System.currentTimeMillis() +
                    CallerAnnouncementReceiver.CALLER_LOG_POLL_INTERVAL_MS
            }
            Thread.sleep(
                CallerAnnouncementReceiver.CALLER_IDENTITY_POLL_INTERVAL_MS
            )
            waited += CallerAnnouncementReceiver
                .CALLER_IDENTITY_POLL_INTERVAL_MS
        }
        assertEquals("أحمد", resolved)
        assertTrue(
            "الهويةُ الجاهزةُ يجب أن تُلتقط فوراً بلا نومٍ ولا انتظار",
            System.currentTimeMillis() - startedAt <
                CallerAnnouncementReceiver.CALLER_LOG_POLL_INTERVAL_MS
        )
        RingCallerIdentity.clear()
    }

    @Test
    fun `late identity wait covers the observed platform gap`() {
        // فجوةُ المنصّة المرصودة ~٦٫١s (الرنين الأول بلا رقم والثاني
        // متأخّر) — فمهلةُ الهوية المتأخرة يجب أن تتجاوزها بهامش فتُلتقط
        // الهويةُ وتُعلَن مرّةً واحدة بدل النغمة وحدها.
        assertTrue(
            "مهلة الهوية المتأخرة يجب أن تتجاوز فجوة المنصّة (~6.1s)",
            CallerAnnouncementReceiver.CALLER_IDENTITY_LATE_WAIT_MS >=
                6_500L
        )
        assertTrue(
            "مجلموع الانتظار يبقى دون سقف ANR حتى مع النطق",
            CallerAnnouncementReceiver.CALLER_RESOLVE_GRACE_PERIOD_MS +
                CallerAnnouncementReceiver
                    .CALLER_IDENTITY_LATE_WAIT_MS <
                CallerAnnouncementReceiver.BROADCAST_SAFE_CAP_MS
        )
    }

    @Test
    fun `no identity means no speech - only cue`() {
        // **القاعدة الحاكمة: لا نطق بلا هوية.** بلا اسمٍ ولا رقم لا
        // تُنتج الدالةُ نصّاً قابلاً للنطق إطلاقاً.
        assertFalse(
            "بلا هوية: لا اسم ولا رقم = لا نطق",
            CallerAnnouncementReceiver.hasSpeakableIdentity(null, null)
        )
        assertFalse(
            "نصٌّ فارغ ليس هوية",
            CallerAnnouncementReceiver.hasSpeakableIdentity("   ", "")
        )
        assertTrue(
            "الرقم وحده هوية صالحة",
            CallerAnnouncementReceiver.hasSpeakableIdentity("0100", null)
        )
        assertTrue(
            "الاسم وحده هوية صالحة",
            CallerAnnouncementReceiver.hasSpeakableIdentity(null, "أحمد")
        )
    }

    /**
     * **بصمةُ العطل المُبلَّغ (نطق الرقم بدل الاسم):** خدمةُ الفرز أو
     * إشعارُ الهاتف ينشران الرقمَ أوّلاً بلا اسم، ثم الاسمَ بعده بقليل.
     * كانت حلقةُ الانتظار تكسر على الرقم وحده فتُعلن الرقمَ ويضيع الاسم.
     * الحارس: الرقمُ وحده **لا** يُنهي الانتظار — الاسمُ فقط.
     */
    @Test
    fun `number only identity does not stop waiting for the name`() {
        assertFalse(
            "الرقم وحده لا يكفي لإيقاف الانتظار",
            identityHasName(null)
        )
        assertFalse(identityHasName(""))
        assertFalse(identityHasName("   "))
        assertTrue(
            "وصول الاسم يوقف الانتظار",
            identityHasName("أحمد")
        )
    }

    @Test
    fun `merge keeps the earlier number and adds the late name`() {
        // النبضة الأولى: الرقم من خدمة الفرز بلا اسم.
        val first = mergeCallerIdentity(
            number = "+201001234567",
            name = null,
            shared = Pair("+201001234567", null),
            lastResolved = Pair(null, null),
            fromLog = null
        )
        assertEquals("+201001234567", first.first)
        assertNull(
            "الرقم وحده ليس اسماً — الحلقة تواصل الانتظار",
            first.second
        )
        // النبضة التالية: الاسم وصل من إشعار الهاتف.
        val second = mergeCallerIdentity(
            number = first.first,
            name = first.second,
            shared = Pair("+201001234567", "أحمد"),
            lastResolved = Pair(null, null),
            fromLog = null
        )
        assertEquals("+201001234567", second.first)
        assertEquals("أحمد", second.second)
    }

    @Test
    fun `merge never lets a number overwrite a resolved name`() {
        val merged = mergeCallerIdentity(
            number = null,
            name = "أحمد",
            shared = Pair("+201001234567", null),
            lastResolved = Pair("+201001234567", null),
            fromLog = Pair("+201001234567", null)
        )
        assertEquals("أحمد", merged.second)
        assertEquals("+201001234567", merged.first)
    }

    @Test
    fun `merge fills number from the call log when others lack it`() {
        val merged = mergeCallerIdentity(
            number = null,
            name = null,
            shared = Pair(null, null),
            lastResolved = Pair(null, null),
            fromLog = Pair("01001234567", null)
        )
        assertEquals("01001234567", merged.first)
        assertNull(merged.second)
    }

    @Test
    fun `caller numbers match across country code and formatting`() {
        assertTrue(
            callerNumbersEquivalent("201001234567", "201001234567")
        )
        assertTrue(
            "رمز بلد مُضاف في السجل",
            callerNumbersEquivalent("201001234567", "01001234567")
        )
        assertTrue(
            "رمز بلد محذوف من الوارد",
            callerNumbersEquivalent("501234567", "966501234567")
        )
    }

    @Test
    fun `caller numbers reject short or divergent digits`() {
        assertFalse(
            callerNumbersEquivalent("0501234567", "0511234567")
        )
        assertFalse(callerNumbersEquivalent("1234", "5678"))
        assertFalse(
            "ذيلٌ قصير لا يكفي للحكم بالتكافؤ",
            callerNumbersEquivalent("5551234", "5559999")
        )
    }

    @Test
    fun `a notification call is announced once despite repeated posts`() {
        // التطبيق يحدّث إشعارَ المكالمة (جارٍ الاتصال ← يرنّ) فيصل
        // الإشعار نفسه مرتين بمفتاحٍ واحد — نطقٌ واحدٌ فقط.
        val key = NateqNotificationListener.notificationCallKey(
            "com.whatsapp", null, "سارة"
        )
        val start = 1_000_000L
        assertTrue(
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key, null, 0L, start
            )
        )
        assertFalse(
            "تحديثُ الإشعار لا يُعيد النطق",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key, key, start, start + 5_000L
            )
        )
    }

    @Test
    fun `a new caller in the notification path always triggers`() {
        // متصلٌ مختلف = مفتاحٌ مختلف، فيُنطق فوراً بلا انتظار النافذة.
        assertTrue(
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                "a|1|سارة", "b|2|أحمد", 0L, 10L
            )
        )
    }

    @Test
    fun `the same caller after the dedup window triggers again`() {
        // انتهاءُ النافذة يعني مكالمةً جديدة من المتصل نفسه — وإلا
        // لكُبِحت كلُّ مكالماته التالية بمفتاحه الأول.
        val key = "c||أحمد"
        assertFalse(
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key, key, 0L, 5_000L
            )
        )
        assertTrue(
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key, key, 0L,
                CallerAnnouncementReceiver
                    .NOTIFICATION_CALL_DEDUP_MS
            )
        )
    }

    @Test
    fun `a voip notification does not double up with the phone state ring`() {
        // **عقد الازدواج لمكالمات VoIP:** التطبيق يُشغّل
        // ACTION_NOTIFICATION_CALL بينما قد يُرسل بعض الأجهزة بثّ
        // PHONE_STATE للمكالمة نفسها — فالحارسُ الأعلى على حالة الجلسة
        // يجب أن يكبّح الثاني، تماماً كما يكبّح الرنينَ المكررَ للشبكة.
        CallerAnnouncementReceiver.resetRingingSession()
        CallerAnnouncementReceiver.ringingAnnounced = true
        CallerAnnouncementReceiver.announcedNumber = null
        // إعلانٌ سابق بلا رقم (حالُ واتساب: اسمٌ فقط) — فيكبَح أي رقم
        // لاحقٍ للمكالمة نفسها، ولا يُفتح جدولُ تكرارٍ ثانٍ.
        assertTrue(
            CallerAnnouncementReceiver.shouldSuppressDuplicateAnnouncement(
                alreadyAnnounced = true,
                announcedNumber = CallerAnnouncementReceiver.announcedNumber,
                incomingNumber = "01001234567"
            )
        )
        CallerAnnouncementReceiver.resetRingingSession()
    }

    @Test
    fun `the dialer call notification is skipped after a phone state ring`() {
        // **ازدواجٌ حقيقي من تغييرنا:** مكالمةُ الشبكة تبدأ ببثّ
        // PHONE_STATE، ثم يُنشر تطبيقُ الهاتف إشعارَ CATEGORY_CALL
        // للمكالمة نفسها — فلا يجوز أن يُطلَق نطقٌ ثانٍ فوقها.
        assertFalse(
            CallerAnnouncementReceiver.shouldLaunchNotificationCall(
                alreadyAnnounced = true,
                notificationCallActive = false,
                announcedNumber = "01001234567",
                incomingNumber = "01001234567"
            )
        )
    }

    @Test
    fun `a notification call is launched when no ring is active`() {
        // لا جلسةَ قائمة: مكالمةُ واتساب تخلو من أيّ PHONE_STATE
        // فينبغي أن تُطلَق بلا تردّد.
        assertTrue(
            CallerAnnouncementReceiver.shouldLaunchNotificationCall(
                alreadyAnnounced = false,
                notificationCallActive = false,
                announcedNumber = null,
                incomingNumber = null
            )
        )
    }

    @Test
    fun `a second notification caller is not blocked by the first`() {
        // بعد إعلانِ مكالمةِ إشعارٍ أولى، مكالمةُ شخصٍ ثانٍ **بلا رقم**
        // (الاسمُ وحده) يجب أن تُطلَق — لا أن يكبَحها رقمٌ مخزَّنٌ من
        // المكالمة الأولى، فمفتاحُ الجولة (لا الرقمُ) هو الحاسم.
        assertTrue(
            CallerAnnouncementReceiver.shouldLaunchNotificationCall(
                alreadyAnnounced = true,
                notificationCallActive = true,
                announcedNumber = "01001234567",
                incomingNumber = null
            )
        )
    }

    // ===== حارس الاتجاه: الصادرةُ والمنتهيةُ لا تُعلَن «اتصال وارد» =====

    @Test
    fun `an ongoing outgoing call notification is not announced`() {
        // **العيبُ الأول الذي شكا منه المستخدم:** هو المُرسل، فينطق التطبيقُ
        // إشعارَ مكالمةٍ جاريةٍ (ongoing) فيُقرأ اسمَ المتصل. الدليلُ الحاكم
        // معاً: جريانُ الإشعار **وأنّه صادرٌ** («calling…» / «جاري الاتصال»).
        assertFalse(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = true,
                networkCallInProgress = false,
                endedCallAt = 0L,
                sameEndedIdentity = false,
                now = 1_000L,
                outgoing = true
            )
        )
    }

    /**
     * حارسُ **انحدار جوجل ميت**: Meet يُعلِّم إشعارَ مكالمته الواردة
     * `FLAG_ONGOING_EVENT` **من لحظة الرنّ** (لأنه واجهةُ مكالمةٍ حيّة)،
     * فكان `if (isOngoing) return false` يُسقط إعلانَه بالكامل.
     * فالعَلَمُ وحده **لا يُرفض** — يُرفض الصادرُ، وهذا وارد.
     */
    @Test
    fun `an ongoing incoming voip ring is announced - google meet`() {
        assertTrue(
            "جوجل ميت الوارد مُعلَّم ongoing ويجب أن يُعلَن",
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = true,
                networkCallInProgress = false,
                endedCallAt = 0L,
                sameEndedIdentity = false,
                now = 1_000L,
                outgoing = false
            )
        )
    }

    /** فارقُ `ongoing` وحدَه: بلا نصٍّ صادرٍ يُقبَل (لا دليلَ على الصغرى). */
    @Test
    fun `ongoing alone does not veto - no direction evidence`() {
        assertTrue(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = true,
                networkCallInProgress = false,
                endedCallAt = 0L,
                sameEndedIdentity = false,
                now = 1_000L,
                outgoing = false
            )
        )
    }

    /** اتجاهُ النصّ: عربياً وإنجليزياً (عامةٌ خالصةٌ قابلة للاختبار). */
    @Test
    fun `outgoing call phrases are detected in both languages`() {
        assertTrue(RingCallerIdentity.isOutgoingCallPhrase("Calling…"))
        assertTrue(RingCallerIdentity.isOutgoingCallPhrase("calling..."))
        assertTrue(RingCallerIdentity.isOutgoingCallPhrase("Outgoing call"))
        assertTrue(RingCallerIdentity.isOutgoingCallPhrase("Ringing…"))
        assertTrue(RingCallerIdentity.isOutgoingCallPhrase("جاري الاتصال"))
        assertTrue(RingCallerIdentity.isOutgoingCallPhrase("مكالمة صادرة"))
        // وليس الاسمُ ولا الواردُ
        assertFalse(RingCallerIdentity.isOutgoingCallPhrase("أحمد"))
        assertFalse(RingCallerIdentity.isOutgoingCallPhrase("Incoming call"))
        assertFalse(RingCallerIdentity.isOutgoingCallPhrase("مكالمة واردة"))
        assertFalse(RingCallerIdentity.isOutgoingCallPhrase(""))
    }

    @Test
    fun `no announcement while a phone call is in progress`() {
        // الشبكةُ في OFFHOOK: إشعارُ التطبيق يخصّ المكالمةِ القائمة،
        // والثنائياتُ تُعلنها بثوثُ `PHONE_STATE` لا الإشعار.
        assertFalse(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = false,
                networkCallInProgress = true,
                endedCallAt = 0L,
                sameEndedIdentity = false,
                now = 1_000L
            )
        )
    }

    @Test
    fun `the ended call notification is not announced right after hangup`() {
        // **الشكوى الثانية:** ينطق «اتصال وارد» بعد انتهاء المكالمة،
        // لأن التطبيق ينشر إشعارَ انتهاءٍ بدل إزالته.
        val endedAt = 10_000L
        val grace = CallerAnnouncementReceiver.AFTER_HANGUP_GRACE_MS
        assertFalse(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = false,
                networkCallInProgress = false,
                endedCallAt = endedAt,
                sameEndedIdentity = true,
                now = endedAt + grace - 1
            )
        )
    }

    @Test
    fun `a different caller ringing right after hangup is announced`() {
        // الحارسُ لا يعمّ: شخصٌ آخر يرنّ قبل انتهاء النافذة يُعلَن.
        val endedAt = 10_000L
        assertTrue(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = false,
                networkCallInProgress = false,
                endedCallAt = endedAt,
                sameEndedIdentity = false,
                now = endedAt + 500L
            )
        )
    }

    @Test
    fun `a real incoming ring after the grace window is announced`() {
        // نفسُ الرقم بعد انقضاء النافذة: رنّةٌ جديدة، فلا تُكبَح.
        val endedAt = 10_000L
        assertTrue(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = false,
                networkCallInProgress = false,
                endedCallAt = endedAt,
                sameEndedIdentity = true,
                now = endedAt +
                    CallerAnnouncementReceiver.AFTER_HANGUP_GRACE_MS + 1
            )
        )
    }

    @Test
    fun `an incoming voip ring is still announced`() {
        // **لا انحدارٌ في الميزة:** مكالمةُ واتساب واردةٌ (لا شبكة، فلا
        // OFFHOOK، ولا جريان، ولا مكالمةٌ سابقة) تُعلَن كما كانت.
        assertTrue(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = false,
                networkCallInProgress = false,
                endedCallAt = 0L,
                sameEndedIdentity = false,
                now = 1_000L
            )
        )
    }

    @Test
    fun `ended call identity matches by number or by name`() {
        assertTrue(
            "المطابقة بالرقم",
            CallerAnnouncementReceiver.matchesLastNetworkCall(
                lastNumber = "01001234567",
                lastName = "سالم",
                number = "01001234567",
                name = null
            )
        )
        assertTrue(
            "والمطابقة بالاسم حين لا يحمل الإشعار رقماً",
            CallerAnnouncementReceiver.matchesLastNetworkCall(
                lastNumber = "01001234567",
                lastName = "سالم",
                number = null,
                name = "سالم"
            )
        )
        assertFalse(
            "وشخصٌ آخر لا يُطابق",
            CallerAnnouncementReceiver.matchesLastNetworkCall(
                lastNumber = "01001234567",
                lastName = "سالم",
                number = "01009999999",
                name = "علي"
            )
        )
        assertFalse(
            "وسابقةٌ بلا هويةٍ لا تُطابق ولا تُكبَح",
            CallerAnnouncementReceiver.matchesLastNetworkCall(
                lastNumber = null,
                lastName = null,
                number = null,
                name = "سالم"
            )
        )
    }

    // ===== بند 5.6: صِدْعُ «المُسجَّل يُنطق رقمَه لا اسمَه» =====

    /**
     * **حارسُ الكسر الأول:** الرقمُ في العنوان والاسمُ في النصّ.
     *
     * كان الاستخراجُ يأخذ **المرشّحَ الأول فقط**، فإذا وضع مُشغِّلُ
     * الهاتف الرقمَ في `EXTRA_TITLE` والاسمَ المحلولَ في `EXTRA_TEXT`
     * طُرح الاسمُ كلياً — ومع أن [RingCallerIdentity.publish] يملأ
     * الفراغَ فقط، فلا يعود الاسمُ من هذا المصدر أبداً فيُنطق الرقم.
     *
     * هذا الاختبارٌ **يسقط** على الكود القديم ويمرّ على الجديد.
     */
    @Test
    fun `a number title with a name text keeps both`() {
        val (number, name) = RingCallerIdentity.extractFromCallNotification(
            "0501234567", "أحمد محمد", null
        )
        assertEquals("الرقم من العنوان", "0501234567", number)
        assertEquals("والاسم لا يُطرح مع الرقم", "أحمد محمد", name)
    }

    /** وعكسُها: الاسمُ في العنوان والرقمُ في النصّ — كلاهما يُلتقط. */
    @Test
    fun `a name title with a number sub text keeps both`() {
        val (number, name) = RingCallerIdentity.extractFromCallNotification(
            "سارة", "0509876543", null
        )
        assertEquals("0509876543", number)
        assertEquals("سارة", name)
    }

    /**
     * محارفُ الضبط الاتجاهي (`LRM`) تغشّي أرقامَ تطبيقات الهاتف في
     * الواجهة العربية — وتُقبل في [RingCallerIdentity.looksLikePhoneNumber]
     * عمداً، لكنها لا يجوز أن تخرج مع الهوية المنطوقة.
     */
    @Test
    fun `bidi controls are stripped from the extracted identity`() {
        val (number, name) = RingCallerIdentity.extractFromCallNotification(
            "‎خالد", "‎+966501234567", null
        )
        assertEquals("محرفُ الضبط يُزال من الرقم", "+966501234567", number)
        assertEquals("ومن الاسم", "خالد", name)
    }

    /**والترتيبُ العكسي: لو عكَس الهاتفُ الحقلين بقي العقدُ قائماً. */
    @Test
    fun `both identity fields survive a sub text swap`() {
        val (number, name) = RingCallerIdentity.extractFromCallNotification(
            "خالد", "+966501234567", "جارٍ الاتصال"
        )
        assertEquals("+966501234567", number)
        assertEquals("خالد", name)
    }

    /**
     * **حارسُ الكسر الثاني:** صيغُ الرقم في دفتر الاتصالات.
     *
     * كان البحثُ يُرسَل بالرقم الخام وحده، و`PhoneLookup` لا يُطبِّع رمز
     * البلد — فمُسجَّلٌ محفوظٌ محلياً لا يُطابَق وارداً دولياً فيبقى
     * الرقمُ وحدَه هويةً. الآن تُجرَّب الصيغُ كلُّها.
     */
    @Test
    fun `contact lookup tries raw digits and e164 forms`() {
        val forms = CallerAnnouncementReceiver.callerLookupForms(
            "+966 (50) 123-4567", "SA"
        )
        assertTrue("الخام أولاً", "+966 (50) 123-4567" in forms)
        assertTrue("ثم الخانات", "966501234567" in forms)
        assertTrue(
            "ثم الصيغة الدولية",
            "+966501234567" in forms
        )
        assertEquals(
            "بلا تكرار بين الصيغ",
            forms.size,
            forms.distinct().size
        )
    }

    /**بلا منطقةٍ معروفة لا تُختلق `E164` ولا تُهمَل الصيغُ الأخرى. */
    @Test
    fun `contact lookup forms survive a missing region`() {
        val forms = CallerAnnouncementReceiver.callerLookupForms(
            "0501234567", null
        )
        assertEquals(listOf("0501234567"), forms)
        assertEquals(
            emptyList<String>(),
            CallerAnnouncementReceiver.callerLookupForms("   ", "SA")
        )
    }

    // ===== بند 5.6: صِدْعُ «المكالمة الصادرة تُعلَن واردة» =====

    /**
     * **حارسُ الكسر:** `DIALING` و`ALERTING` تُثبتان أنّ المتصلَ هو
     * المستخدم.
     *
     * هذا هو جذرُ الشكوى: مُشغِّلُ الهاتف ينشر إشعارَ مكالمته الصادرة
     * **قبل** وصول بثّ `OFFHOOK`، أي في نافذة `DIALING`/`ALERTING` —
     * وكان الحارسُ يقرأ `OFFHOOK` وحده فيفوتها، فيُعلَن «اتصال وارد»
     * لمن هو المُرسِل.
     *
     * وهذا الاختبارُ **يسقط** على الكود القديم (كان `OFFHOOK` وحده).
     */
    @Test
    fun `dialing and alerting prove the user is the caller`() {
        assertTrue(
            "DIALING دليلٌ قاطع",
            CallerAnnouncementReceiver.isOutgoingNetworkCallState(
                CALL_STATE_DIALING
            )
        )
        assertTrue(
            "ALERTING دليلٌ قاطع",
            CallerAnnouncementReceiver.isOutgoingNetworkCallState(
                CALL_STATE_ALERTING
            )
        )
        assertTrue(
            "وOFFHOOK كذلك",
            CallerAnnouncementReceiver.isOutgoingNetworkCallState(
                android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
            )
        )
    }

    /** **ولا انحدار:** الرنّةُ واردةٌ لا تثبت outgoing — فيجب أن تُعلَن. */
    @Test
    fun `ringing and idle do not prove an outgoing call`() {
        assertFalse(
            "الواردةُ ليست صادرة",
            CallerAnnouncementReceiver.isOutgoingNetworkCallState(
                android.telephony.TelephonyManager.CALL_STATE_RINGING
            )
        )
        assertFalse(
            "والخمولُ ليس صادراً",
            CallerAnnouncementReceiver.isOutgoingNetworkCallState(
                android.telephony.TelephonyManager.CALL_STATE_IDLE
            )
        )
    }

    /**
     * قيمتا `DIALING`/`ALERTING` محجوبتان في `android.jar` — نُعرِّفهما
     * هنا بالقيمةِ الرسمية من واجهة `TelephonyManager` ليحرس الاختبارُ
     * نفسَ قيمِ الحارس، فسيكسر إن غُيّرت إحداهما.
     */
    private companion object {
        const val CALL_STATE_DIALING = 3
        const val CALL_STATE_ALERTING = 4
    }

    /** الخصلةُ الحاكمة: إشعارٌ جارٍ أثناء `DIALING` — направُ من protocols. */
    @Test
    fun `an ongoing call notification during dialing is vetoed`() {
        // بلا نصٍّ صادرٍ (=بلا `outgoing`) ومع ذلك لا يُعلَن: الدليلُ
        // قاطعٌ من حالة الخطّ فالنصُّ لم يعد له دور.
        assertFalse(
            CallerAnnouncementReceiver.shouldAnnounceCallNotification(
                isOngoing = true,
                networkCallInProgress = true,
                endedCallAt = 0L,
                sameEndedIdentity = false,
                now = 1_000L,
                outgoing = false
            )
        )
    }

    /**
     * **حارسُ الكتابة الطباعية:** نصُّ مُشغِّلِ الهاتف الحقيقيُّ
     * «جارٍ الاتصال» بتشكيلٍ — كان التساويُ الحرفيُّ يفشل فيُعلَن
     * الصاردُ واردةً.
     */
    @Test
    fun `the real dialer arabic text is recognised despite tashkeel`() {
        assertTrue(
            "جارٍ بتشكيل",
            RingCallerIdentity.isOutgoingCallPhrase("جارٍ الاتصال…")
        )
        assertTrue(
            "وألف مقصورة",
            RingCallerIdentity.isOutgoingCallPhrase("جارى الاتصال")
        )
        assertTrue(
            "جملةٌ كاملةٌ لا عبارتان",
            RingCallerIdentity.isOutgoingCallPhrase("مكالمة صادرة جارية")
        )
        assertTrue(
            "وإنجليزيةٌ بنقطةٍ وتطويل",
            RingCallerIdentity.isOutgoingCallPhrase("Ringing tone…")
        )
        // ولا تنكسر قاعدةُ «ليس اسماً ولا وارداً»
        assertFalse(
            RingCallerIdentity.isOutgoingCallPhrase("جاريات المدينة")
        )
        assertFalse(
            RingCallerIdentity.isOutgoingCallPhrase("مكالمة واردة")
        )
    }
}
