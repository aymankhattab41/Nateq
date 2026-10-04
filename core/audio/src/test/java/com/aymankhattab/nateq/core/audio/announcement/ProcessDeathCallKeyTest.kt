package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارسُ سدِّ فجوة موتِ العملية في مسار مكالمة الإشعار.
 *
 * ## العيب
 *
 * مفتاحُ جلسة مكالمة الإشعار في ذاكرة العملية
 * ([CallerAnnouncementReceiver.notificationCallKey]). فتموت العمليةُ في
 * وسط رنينِ مكالمةِ تطبيق، فينسى المفتاح، ثم يردُّ التطبيقُ التحديثَ
 * التالي لنفس المكالمة فيحسبه المستمعُ إعلاناً جديداً فيُنطق الاسمُ
 * ثانيةً فوق مكالمةٍ جارية — مع جدولِ تكراراته كلِّه.
 *
 * ## الحلُّ من مخرجِ النظام لا من تخزينٍ جديد
 *
 * المستمعُ مربوطٌ بالإشعارات أصلاً، فيستطيع عند ربطه أن يستعلم
 * `activeNotifications` فيجد إشعارَ المكالمة التي كانت ترنّ قبل موته
 * فيتبنّاه مفتاحاً حيّاً. فالتحديثُ التالي يُرفض بحكمِ البنية: بلا
 * `SharedPreferences` وبلا ساعةٍ وبلا نافذةِ زمنية.
 *
 * **وهو تبنٍّ لا إعلان:** المكالمةُ الموجودةُ في لقطة إعادة الربط
 * سبقتْ هذه النسخةَ من المستمع، فنحن لا نعرف إن سمعَها المستخدمُ قبل
 * الموت. والاختيارُ بين خطأين: **إعادةُ نطقٍ فوق مكالمةٍ جارية**
 * (ما عولج في بند 2) و**سكوتٌ لمكالمةٍ فاتَ ذكرُها**. والسكوتُ هو
 * الأقلُّ ضرراً في مكالمةٍ جارية، فهو الخيارُ المتَّخذ.
 *
 * **وقيدُ الأمان:** ما في لقطةِ الربط حيٌّ الآن، وما يُحذفُ بعدها
 * يصلنا في `onNotificationRemoved` فيحرّر المفتاح. فلا يُسكبَ متصلٌ
 * إلى الأبد إلا إذا ماتت العمليةُ في النافذةِ بين آخرِ حذفٍ للقائمة
 * وأولِ قراءةٍ لها — وهي نافذةٌ تكاد لا تُرى.
 *
 * **ودورُ الاختبار:** يستدعي **دوالَ الإنتاج نفسها**
 * ([adoptLiveNotificationCall] و[pickNewestLiveCallKey] و
 * [shouldTriggerNotificationCall] و[endNotificationCall]) على حالةِ
 * الإنتاج الحقيقية `notificationCallKey` — فلا يحاكي حالةً ولا يعيد
 * كتابة القرار الذي يحرسه. و`Robolectric` لأنّ هذه الدوال تكتب في
 * `android.util.Log` وتستدعي `AnnouncementSpeaker.stop()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ProcessDeathCallKeyTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Before
    fun clearLiveKey() {
        // تصفيرُ حالةِ الإنتاج نفسها لا حقلَ اختبارٍ موازياً: نتبنّى
        // مفتاحاً ثم نحرّره بالمُحرِّر الحقيقي، فيبدأ كلُّ اختبار
        // من `notificationCallKey == null` كما في عمليةٍ جديدة.
        CallerAnnouncementReceiver.adoptLiveNotificationCall(RESET_KEY)
        CallerAnnouncementReceiver.endNotificationCall(context, null)
    }

    /**
     * **العقدُ الحاكم:** إشعارُ مكالمةٍ حيٌّ وُجد عند إعادة الربط
     * فيُتبنّى مفتاحُه، فيرفض المستمعُ تحديثَه فلا يُعاد النطق.
     *
     * **والكسرُ الذي يحرسه:** بلا تبنٍّ كان `lastKey = null` فيُرجع
     * `shouldTriggerNotificationCall` القيمَ `true` على أول تحديث بعد
     * الموت — وهو النطقُ المكرر فوق المكالمة الجارية.
     */
    @Test
    fun `a live call adopted at rebind blocks its own next update`() {
        val key = NateqNotificationListener.notificationCallKey(
            PACKAGE_WHATSAPP, "0501234567", "سارة"
        )
        CallerAnnouncementReceiver.adoptLiveNotificationCall(key)

        assertFalse(
            "التحديثُ بعد التبني يجب ألّا يُطلق إعلاناً ثانياً",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = key,
                lastKey = CallerAnnouncementReceiver.notificationCallKey
            )
        )
    }

    /**
     * **تبنّيُ مفتاحٍ لا يُسكت المتصلَ الآخر:** المفتاحُ جزءٌ من
     * هوية المكالمة (حزمة + رقم + اسم)، فمكالمةُ متصلٍ آخر مفتاحُها
     * مختلفٌ فيجب أن تُعلَن فوراً.
     */
    @Test
    fun `another caller is announced while a live key is adopted`() {
        CallerAnnouncementReceiver.adoptLiveNotificationCall(
            NateqNotificationListener.notificationCallKey(
                PACKAGE_WHATSAPP, "0501234567", "سارة"
            )
        )
        val other = NateqNotificationListener.notificationCallKey(
            PACKAGE_TELEGRAM, "01001234567", "خالد"
        )
        assertTrue(
            "متصلٌ آخر لا ينتظر أحداً",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = other,
                lastKey = CallerAnnouncementReceiver.notificationCallKey
            )
        )
    }

    /**
     * **جلسةٌ جاريةٌ لا تُداس:** إن كان هناك مفتاحٌ حيٌّ أصلاً فمن
     * إعلانٍ بدأناه في هذه العملية فالتبنّي لا يلمسه. ولو طُبّقت
     * الكتابةُ بلا شرط لأمكن لمكالمةٍ في لقطة إعادة الربط أن تُسكت
     * إعلانَ مكالمةٍ حقيقيةٍ بدأ للتوّ — فالمفتاحُ الأولُ أَولى.
     */
    @Test
    fun `adoption never clobbers a key that is already live`() {
        val live = NateqNotificationListener.notificationCallKey(
            PACKAGE_WHATSAPP, "0501234567", "سارة"
        )
        val other = NateqNotificationListener.notificationCallKey(
            PACKAGE_TELEGRAM, "01001234567", "خالد"
        )
        CallerAnnouncementReceiver.adoptLiveNotificationCall(live)
        CallerAnnouncementReceiver.adoptLiveNotificationCall(other)
        assertEquals(
            "المفتاحُ الجاري مقدَّمٌ على مُرشَّحِ إعادة الربط",
            live,
            CallerAnnouncementReceiver.notificationCallKey
        )
    }

    /**
     * **أكثرُ من مكالمةٍ ترنّ عند إعادة الربط — والأحدثُ يفوز:**
     * فالمكالمةُ الأحدثُ هي التي رنَّت الآن فيُتوقَّعُ سماعُها، ولو
     * أخذنا الأقدمَ لبقيت الأحدثُ بلا إعلان.
     */
    @Test
    fun `the newest live call wins when several ring at rebind`() {
        val older = NateqNotificationListener.notificationCallKey(
            PACKAGE_WHATSAPP, "0501234567", "سارة"
        )
        val newer = NateqNotificationListener.notificationCallKey(
            PACKAGE_TELEGRAM, "01001234567", "خالد"
        )
        assertEquals(
            newer,
            CallerAnnouncementReceiver.pickNewestLiveCallKey(
                listOf(100L to older, 200L to newer)
            )
        )
    }

    /** ولا مكالمةَ حيّةً أصلاً (قائمةٌ فارغة): لا تبنٍّ ولا مفتاح. */
    @Test
    fun `no live call at rebind adopts nothing`() {
        assertNull(
            CallerAnnouncementReceiver.pickNewestLiveCallKey(emptyList())
        )
        CallerAnnouncementReceiver.adoptLiveNotificationCall(null)
        assertNull(CallerAnnouncementReceiver.notificationCallKey)
    }

    /**
     * **مفتاحٌ فارغٌ لا يُتبنّى:** فإشعارُ مكالمةٍ بلا هويةٍ مُستخلَصة
     * يُنتج `pkg||` وهو ليس مفتاحَ هوية، فتبنّيه يُسكت كلَّ مفاتيح
     * تلك الحزمة بلا سبب.
     */
    @Test
    fun `a blank live key is never adopted`() {
        CallerAnnouncementReceiver.adoptLiveNotificationCall("   ")
        assertNull(CallerAnnouncementReceiver.notificationCallKey)
        assertTrue(
            "بلا مفتاحٍ حيّ يُعلَن كلُّ إشعار",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = PACKAGE_WHATSAPP,
                lastKey = CallerAnnouncementReceiver.notificationCallKey
            )
        )
    }

    /**
     * **بابُ الخروج يبقى مفتوحاً — وهو قيدُ الأمان نفسُه:**
     * التبنّي يُسكت التحديثات ما دام الإشعارُ حياً، فإذا حُذف
     * الإشعار (انتهت المكالمة) فالمفتاحُ يُحرَّر عبر المُحرِّر
     * الحقيقي فيعود المتصلُ نفسه يُعلَن في مكالمته التالية. لولا
     * `endNotificationCall` لكُبِح المتصلُ إلى الأبد بمفتاحه.
     */
    @Test
    fun `removal releases the adopted key so the same caller rings again`() {
        val key = NateqNotificationListener.notificationCallKey(
            PACKAGE_WHATSAPP, "0501234567", "سارة"
        )
        CallerAnnouncementReceiver.adoptLiveNotificationCall(key)
        assertFalse(
            "قبل الحذف: التحديثُ يُكبَت",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = key,
                lastKey = CallerAnnouncementReceiver.notificationCallKey
            )
        )

        CallerAnnouncementReceiver.endNotificationCall(
            context, PACKAGE_WHATSAPP
        )

        assertNull(
            "الحذفُ يحرّر المفتاح",
            CallerAnnouncementReceiver.notificationCallKey
        )
        assertTrue(
            "بعد الحذف تُعلَن مكالمةُ المتصل نفسه",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = key,
                lastKey = CallerAnnouncementReceiver.notificationCallKey
            )
        )
    }

    /**
     * **حارسُ البنية — التبنّيُ حافظٌ لا مُفسِد:** إعادةُ الربط قد
     * تتكرّر، فالتبنّيُ نفسُه مراراً لا يُفسد المفتاحَ ولا يلغي
     * شيئاً؛ فمن قرأ اللقطةَ مرتين لا يُعيد كتابةً مكرَّرة.
     */
    @Test
    fun `adoption is idempotent`() {
        val key = NateqNotificationListener.notificationCallKey(
            PACKAGE_WHATSAPP, "0501234567", "سارة"
        )
        CallerAnnouncementReceiver.adoptLiveNotificationCall(key)
        CallerAnnouncementReceiver.adoptLiveNotificationCall(key)
        assertEquals(
            key,
            CallerAnnouncementReceiver.notificationCallKey
        )
        assertFalse(
            "ولا يُفتح thereby بابٌ للإعلان",
            CallerAnnouncementReceiver.shouldTriggerNotificationCall(
                key = key,
                lastKey = CallerAnnouncementReceiver.notificationCallKey
            )
        )
    }

    private companion object {
        const val PACKAGE_WHATSAPP = "com.whatsapp"
        const val PACKAGE_TELEGRAM = "org.telegram.messenger"
        const val RESET_KEY = "com.reset|0|0"
    }
}
