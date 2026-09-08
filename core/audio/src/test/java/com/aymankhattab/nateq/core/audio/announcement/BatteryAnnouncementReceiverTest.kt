package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** اختبار فلتر بث البطارية الدائم (بند 16.1): يُعالج البث الدم مرة واحدة لكل
 *  نسبة منعطف، ويُفلتر التكرار في الذاكرة قبل أي عملية لاتزامنية أو قراءة قرص. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
        // مسح ختوم نافذة منع التكرار بين الاختبارات حتى لا تتسرب.
        context.getSharedPreferences("nateq_battery_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        BatteryAnnouncementReceiver.resetLevelFilterForTesting()
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
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(-1, 100)))
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(-1, -1)))
    }

    @Test
    fun `percent uses same rounding as handler`() {
        // (level*100)/scale — نفس حسابات معالجة الإعلان: 49.9% تُقرَّب إلى 49
        // فتُفلتر بعد معالجة 49%، وليست نسبة جديدة.
        assertTrue(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(49, 100)))
        assertFalse(BatteryAnnouncementReceiver.isNewLevel(batteryIntent(499, 1000)))
    }

    // ===== نافذة منع تكرار الإعلان (5 دقائق) ====

    @Test
    fun `first announcement is allowed`() {
        // بلا ختم سابق يُعدّ مستوى 50 "منطوقاً مؤخراً": مع الانعكاس المنطقي
        // القديم كانت هذه الحالة تُسكت الإعلان всегда (لا ينطق أبداً أول مرة).
        assertFalse(BatteryAnnouncementReceiver().announcedRecently(context, "%50"))
    }

    @Test
    fun `immediately after mark is suppressed`() {
        BatteryAnnouncementReceiver().markAnnounced(context, "%50")
        assertTrue(BatteryAnnouncementReceiver().announcedRecently(context, "%50"))
    }

    @Test
    fun `after five minutes the same level is allowed again`() {
        // تمرير الآن يدوياً بدلاً من عداد الساعة المحاكى (ShadowSystemClock لا
        // يتحكّم في currentTimeMillis في هذا الإعداد). القيمة المُمرَّرة تحاكي
        // مرور 5 دقائق بالضبط + هامش أمان فوق فرق الميلي ثانية المتبقية.
        val markedAt = System.currentTimeMillis()
        BatteryAnnouncementReceiver().markAnnounced(context, "%50")
        // داخل النافذة (بعد دقيقة) ما زال يُمنع.
        assertTrue(BatteryAnnouncementReceiver().announcedRecently(context, "%50", markedAt + 60_000L))
        // عند تجاوز الخمس دقائق يُسمح مجدداً (بهامش أمان +5 ثوانٍ فوق هامش الميلي ثانية المتبقية من التخزين).
        assertFalse(BatteryAnnouncementReceiver().announcedRecently(context, "%50", markedAt + (5 * 60 * 1000L) + 5_000L))
    }

    @Test
    fun `old stamp from a previous boot never suppresses`() {
        // محاكاة ختمٍ كُتب قبل إعادة تشغيل الهاتف (قبل 10 ساعات): الجدار الزمني
        // يعبر إعادة الإقلاع فيُسمح بالنطق الآن بدل التجمد حتى تنقضي المدة.
        val oldStamp = System.currentTimeMillis() - 10 * 60 * 60 * 1000L
        context.getSharedPreferences("nateq_battery_state", Context.MODE_PRIVATE)
            .edit().putLong("battery_last_announced_%50", oldStamp).commit()
        assertFalse(BatteryAnnouncementReceiver().announcedRecently(context, "%50"))
    }
}