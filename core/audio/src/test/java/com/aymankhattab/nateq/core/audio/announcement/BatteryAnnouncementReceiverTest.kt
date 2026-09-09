package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.common.TimeProvider
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** اختبار فلتر بث البطارية الدائم (بند 16.1): يُعالج البث الدم مرة
 *  واحدة لكل نسبة منعطف، ويُفلتر التكرار في الذاكرة قبل أي
 *  عملية لاتزامنية أو قراءة قرص. */
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
        context.getSharedPreferences(
            "nateq_battery_state", Context.MODE_PRIVATE
        )
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