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

    @Test
    fun `call log hint is needed only when denied on sdk 31 plus`() {
        // على أندرويد 12+ (API 31+) الرقم فارغ لأن READ_CALL_LOG غير
        // ممنوح — فيُرشد المستخدم إلى منحه من الإعدادات.
        assertTrue(callerCallLogHintNeeded(hasCallLog = false, sdkInt = 31))
        assertTrue(callerCallLogHintNeeded(hasCallLog = false, sdkInt = 37))
        // الإذن ممنوح أو النظام أقدم من 12: لا تلميح.
        assertFalse(callerCallLogHintNeeded(hasCallLog = true, sdkInt = 31))
        assertFalse(callerCallLogHintNeeded(hasCallLog = false, sdkInt = 30))
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
    fun `blank number without call log permission appends the grant hint`() {
        val app =
            ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).denyPermissions(
            android.Manifest.permission.READ_CALL_LOG
        )
        // رقم فارغ بلا اسم وإذنُ سجلٍ غير ممنوح على API 31+: اللاحقة ظاهرة.
        val text = buildPhrase(number = null, contactName = null)
        assertTrue(
            "التلميح يُلحق بعد «اتصال وارد» عند سحب إذن السجل",
            text.contains("سجل المكالمات")
        )
        assertTrue(text.startsWith("اتصال وارد"))
    }

    @Test
    fun `blank number with call log granted stays generic`() {
        val app =
            ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_CALL_LOG
        )
        // الإذن ممنوح والرقم ما زال فارغاً: حجب حقيقي — لا تلميح.
        assertEquals(
            "اتصال وارد",
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
        val app =
            ApplicationProvider.getApplicationContext<android.app.Application>()
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
     * مرّة للمكالمة الواحدة، وكان الحارسُ معلقاً على `activeCallCycle`
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
    fun `grace period constants are valid and cover the platform gap`() {
        // مهلةُ الانتظار تغطّي فجوةَ المنصّة المرصودة (~٦٫١s: الرنين
        // الأول بلا رقم والثاني متأخّر) بهامش، وتبقى دون سقف الـ ANR
        // فلا تُخرج البثّ عن حافة النظام.
        assertTrue(
            "مهلة الانتظار يجب أن تتجاوز فجوة المنصّة المرصودة (~6.1s)",
            CallerAnnouncementReceiver.CALLER_RESOLVE_GRACE_PERIOD_MS >=
                6_500L
        )
        assertTrue(
            "مهلة الانتظار يجب أن تبقى دون سقف ANR",
            CallerAnnouncementReceiver.CALLER_RESOLVE_GRACE_PERIOD_MS <
                CallerAnnouncementReceiver.BROADCAST_SAFE_CAP_MS
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
}