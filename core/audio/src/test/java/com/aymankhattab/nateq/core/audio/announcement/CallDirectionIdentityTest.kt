package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.audio.announcement.CallerScreeningService.Companion.DIRECTION_INCOMING
import com.aymankhattab.nateq.core.audio.announcement.CallerScreeningService.Companion.DIRECTION_OUTGOING
import com.aymankhattab.nateq.core.audio.announcement.CallerScreeningService.Companion.DIRECTION_UNKNOWN
import com.aymankhattab.nateq.core.audio.announcement.RingCallerIdentity.IDENTITY_TTL_MS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * حارسا الأمرين الثالث والسادس — عقدان متجاوران يجمعهما الملفُ الواحد
 * لأن كليهما السؤالُ نفسُه: **هل يحقُّ النشر؟**
 *
 *  1. **اتجاهُ المكالمة في خدمة الفرز** ([CallerScreeningService]):
 *     كان الرقمُ يُنشر بلا شرطٍ على الاتجاه، فنشر رقمَ مكالمةٍ
 *     **صادرة** قبل الرنّة، فصار المتصلُ نفسُه مُعلَناً كمتصلٍ وارد.
 *  2. **عمرُ الهوية في [RingCallerIdentity]**: كانت بلا طابعٍ زمنيٍّ
 *     أبداً، فتبقى هويةُ مكالمةٍ سابقةٍ مقروءةً في رنينٍ لاحقٍ إن لم
 *     يمرَّ `clear()` عليها.
 *
 * وكلاهما يحتاج تاريخاً (الاتجاهُ أو الزمن) فيُختبر بمرور وقتٍ
 * محاكى — فـ
ow معاملٌ صريحٌ لا استدعاءُ ساعة النظام داخل الدالة.
 */
class CallDirectionIdentityTest {

    @Before
    fun setUp() = RingCallerIdentity.clear()

    @After
    fun tearDown() = RingCallerIdentity.clear()

    // ===== الأمر 3: اتجاهُ المكالمة في خدمة الفرز =====

    /**
     * العقدُ الحاكم لخدمة الفرز: لا يُنشر الرقمُ إلا لمكالمةٍ **واردة**.
     * هذا هو المسارُ الوحيد الذي يجب أن ينجح.
     */
    @Test
    fun `screening publishes the number for an incoming call`() {
        assertTrue(
            CallerScreeningService.shouldPublishIdentity(
                sdkInt = 34,
                readDirection = { DIRECTION_INCOMING }
            )
        )
    }

    /**
     * **الكسرُ الذي يحرسه هذا:** خدمةُ الفرز تُستدعى لكلِّ مكالمةٍ
     * تجري — الصادرةَ قبل الرنّة أيضاً — فكان الرقمُ يُنشر بلا تمييزٍ
     * فيُعلَن «مكالمة واردة من… » وهو المتصلُ نفسُه. والإثباتُ بالإيجاب
     * يفرض الرفضَ على كلِّ ما عدا الوارد.
     */
    @Test
    fun `screening refuses an outgoing call`() {
        assertFalse(
            CallerScreeningService.shouldPublishIdentity(
                sdkInt = 34,
                readDirection = { DIRECTION_OUTGOING }
            )
        )
    }

    /**
     * **«لا أعلم» ليست «واردة»:** `DIRECTION_UNKNOWN` حالةٌ حقيقيةٌ في
     * المنصّة (يعجز النظام عن تحديد الاتجاه)، فلا يجوز أن يُنشر الرقمُ
     * بها — فغيابُ الدليل ليس دليلَ ورود.
     */
    @Test
    fun `screening refuses an unknown direction`() {
        assertFalse(
            CallerScreeningService.shouldPublishIdentity(
                sdkInt = 34,
                readDirection = { DIRECTION_UNKNOWN }
            )
        )
    }

    /**
     * **ثوابتُ الاتجاه مُثبَّتةٌ بـ`javap` على `android.jar` (37):**
     * `DIRECTION_INCOMING = 0` و`DIRECTION_OUTGOING = 1` و
     * `DIRECTION_UNKNOWN = -1`. والقيمُ مستنسخةٌ في
     * [CallerScreeningService] لا مُحيلةً إلى المنصّة، لأنها `int`
     * فيُضمَّن مقدارُها وقتَ التصريف فلا نحتاج `sdk` أصلاً — وهذا
     * الحارسُ يمنع انحرافَها كما يمنع انحرافَ ثوابت نوعِ المكالمة
     * بحارس `platform call type constants are mirrored`.
     */
    @Test
    fun `platform call direction constants are mirrored`() {
        assertEquals(0, DIRECTION_INCOMING)
        assertEquals(1, DIRECTION_OUTGOING)
        assertEquals(-1, DIRECTION_UNKNOWN)
    }

    /**
     * **قبل 29 لا حقلَ للاتجاه أصلاً** — `getCallDirection()` وُجد في
     * Q، بينما `CallScreeningService` موجودةٌ من 24. فبلا حارسِ
     * `sdkInt` ينهار `onScreenCall` بـ`NoSuchMethodError` على جهازٍ
     * قديم. والحارسُ يمرّر الرقمَ بلا تمييزٍ هناك — وهو سلوكُ
     * الخللِ التاريخي، لكنه غيرُ قابلٍ للتنفيذ: دورُ الفرز نفسه من 29
     * فلا تُستدعى الخدمةُ أصلاً على ما دونه.
     */
    @Test
    fun `pre-29 has no direction field so the check is bypassed`() {
        assertTrue(
            CallerScreeningService.shouldPublishIdentity(
                sdkInt = 24,
                readDirection = { DIRECTION_INCOMING }
            )
        )
    }

    /**
     * **الكسرُ الذي يحرسه (وهو الكسرُ التاريخيّ بعينه):**
     * `callDetails.callDirection` كانت تُمرَّر **قيمةً** لا دالّةً، فقيّمها
     * Kotlin **قبل** الدخول إلى `shouldPublishIdentity` — أي أن حارس
     * `sdkInt` كان يوصف حارساً غيرَ موجود، و`getCallDirection()` كان
     * ينهار بـ`NoSuchMethodError` على جهازٍ دون 29 **قبل أن تفتح
     * الدالّةُ نفسُها**. وتعليقُ الكود القديم كان يَعِد بحارسٍ لم يكن
     * هناك.
     *
     * **ولهذا صارت المُعامَلُ دالّةً:** فالعقدُ «لا تُقرأ الخاصيةُ
     * أصلاً ما دون Q» صار **قابلاً للاختبار** بدل أن يكون دعوى. وهذا
     * الاختبارُ يفشل فوراً إن عاد أحدٌ إلى تمرير القيمة مباشرةً، لأن
     * القراءةَ ستُنفَّذ قبل الحارسِ فيسقط الدَّعوى.
     */
    @Test
    fun `pre-29 never reads the direction field at all`() {
        var readCount = 0
        val published = CallerScreeningService.shouldPublishIdentity(
            sdkInt = 24,
            readDirection = {
                readCount++
                DIRECTION_OUTGOING
            }
        )
        assertTrue(
            "ما دون 29: المرورُ بلا تمييزٍ فيبقى كما هو",
            published
        )
        assertEquals(
            "ما دون 29: حقلُ الاتجاه لا يُقرأ أصلاً — " +
                "قراءتُه هي سببُ NoSuchMethodError",
            0,
            readCount
        )
    }


    // ===== الأمر 3: الطابعُ الزمنيُّ للهوية (TTL) =====

    /**
     * الهويةُ الحيّةُ تُقرأ فوراً. بلا هذا يسقط كلُّ ما بعده لأن
     * «الهويةَ غيرَ المنشورة» و«الهويةَ المنتهيةَ» صارا حالةً واحدة.
     */
    @Test
    fun `freshly published identity is readable`() {
        RingCallerIdentity.publish("01001234567", "أحمد", now = 1_000L)
        val (number, name) = RingCallerIdentity.snapshot(now = 1_000L)
        assertEquals("01001234567", number)
        assertEquals("أحمد", name)
        assertTrue(RingCallerIdentity.hasIdentity(now = 1_000L))
    }

    /**
     * **الكسرُ الذي يحرسه (صريحٌ في الأمر: «حتى لا تتسرّب هوية مكالمة
     * سابقة إلى رنينٍ لاحق»):** تُنشر الهويةُ في مكالمةٍ ثم لا يمرُّ
     * `clear()` — لموتِ العملية، أو مسارٍ لم يمرّ بالتصفير — فتبقى
     * «متاحة» إلى الأبد. فلا يجوز أن تُقرأ بعد انتهاء صلاحيتها مهما
     * كانت سليمةً في نصّها.
     */
    @Test
    fun `identity expires after the ttl`() {
        RingCallerIdentity.publish("01001234567", "أحمد", now = 1_000L)
        assertTrue(
            "هويةٌ نُشرت للتوّ تبقى مقروءة",
            RingCallerIdentity.hasIdentity(now = 1_000L + IDENTITY_TTL_MS - 1)
        )
        assertFalse(
            "وبعد انتهاء المهلة لا تُقرأ الهويةُ أبداً",
            RingCallerIdentity.hasIdentity(now = 1_000L + IDENTITY_TTL_MS)
        )
        assertNull(
            RingCallerIdentity.snapshot(now = 1_000L + IDENTITY_TTL_MS).first
        )
        assertNull(
            RingCallerIdentity.snapshot(now = 1_000L + IDENTITY_TTL_MS).second
        )
    }

    /**
     * **«آخرُ نشرٍ يُجديد» لا «أولُ نشر»:** المساراتُ الثلاثة تكتب على
     * دفعات — خدمةُ الفرز تنشر الرقمَ قبل الرنّة، ثم يُنشر الاسمُ من
     * الإشعار أثناءها. فإن حُسب العمرُ من **أول** نشرٍ لفقد الاسمُ حيّيتَه
     * بعد المهلة الأولى رغم وصوله متأخّراً، وهو عكسُ المطلوب.
     */
    @Test
    fun `each publish renews the ttl`() {
        RingCallerIdentity.publish("01001234567", null, now = 1_000L)
        RingCallerIdentity.publish(null, "أحمد", now = 6_000L)
        assertEquals(
            "الاسمُ المتأخّر يُبقي الهويةَ حيّة",
            "أحمد",
            RingCallerIdentity.snapshot(
                now = 6_000L + IDENTITY_TTL_MS - 1
            ).second
        )
        assertFalse(
            "والانتهاءُ يُحسب من آخر نشرٍ لا من أوله",
            RingCallerIdentity.hasIdentity(now = 6_000L + IDENTITY_TTL_MS)
        )
    }

    /**
     * **`clear()` تصفّر الطابعَ الزمنيَّ أيضاً:** تصفيرُ الحقول وحدَها
     * يُبقي طابعَ قديمٍ في نداء publish التالي فيُقرأ على أنه منتهٍ.
     * الثلاثُ وحدَها تُصفَّر معاً أو لا يُصفَّر شيء.
     */
    @Test
    fun `clear also resets the publish timestamp`() {
        RingCallerIdentity.publish("01001234567", "أحمد", now = 1_000L)
        RingCallerIdentity.clear()
        RingCallerIdentity.publish("01001239999", "سارة", now = 100_000L)
        assertTrue(RingCallerIdentity.hasIdentity(now = 100_000L))
        assertEquals(
            "01001239999",
            RingCallerIdentity.snapshot(now = 100_000L).first
        )
    }

    /**
     * **نشرٌ فارغٌ لا يُنشئ هويةً ولا يُجديد الطابعَ:** فالدالّةُ
     * تُبطل عملَها عند `(null, null)`، ولو أُجدد الطابعَ لأبطلت انتهاءَ
     * هويةٍ حقيقيةٍ بكتابةٍ فارغة — فيبقى الاسمُ يُنطق بعد مكالمةٍ
     * انتهت.
     */
    @Test
    fun `an empty publish neither creates nor renews identity`() {
        RingCallerIdentity.publish("01001234567", "أحمد", now = 1_000L)
        RingCallerIdentity.publish(
            null, null, now = 1_000L + IDENTITY_TTL_MS + 5_000L
        )
        assertFalse(
            RingCallerIdentity.hasIdentity(
                now = 1_000L + IDENTITY_TTL_MS + 5_000L
            )
        )
    }

    /**
     **ساعةٌ تتأخّر لا تُنهي الهوية:** إن كان 
ow قبل الطابع —
     تقديماً يدويّاً، أو قياسٌ في عمليةٍ أخرى — فلا تُحسب السالبُ على
     * أنها «انتهى» فيُبطل الطابعُ فوراً. النتيجةُ «نُشر حديثاً»، وهي
     * آمنةٌ: الأسوأُ تأخيرُ الانتهاء، لا نطقُ هويةٍ منتهية.
     */
    @Test
    fun `a clock that moves backwards does not expire identity`() {
        RingCallerIdentity.publish("01001234567", "أحمد", now = 1_000L)
        assertTrue(
            "نشرٌ في «المستقبل» يبقى مقروءاً",
            RingCallerIdentity.hasIdentity(now = 500L)
        )
    }

    /**
     * الطابعُ الزمنيُّ يجب ألّا يتجاوز خمسَ عشرةَ ثانية — سقفٌ حدّده
     * الأمرُ نصًّا. ومحرَّكُ الاختبار يقرأ الثابتَ نفسَه فيقيس العقدَ
     * الحقيقيَّ لا صياغتَه.
     */
    @Test
    fun `ttl is at most fifteen seconds`() {
        assertTrue(
            "المهلة $IDENTITY_TTL_MS يجب ألّا تتجاوز 15000",
            IDENTITY_TTL_MS in 1..15_000L
        )
    }
}
