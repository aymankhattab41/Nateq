package com.aymankhattab.nateq.core.audio.announcement

import android.content.Intent
import android.os.BatteryManager
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

    private fun batteryIntent(level: Int, scale: Int = 100): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, level)
            putExtra(BatteryManager.EXTRA_SCALE, scale)
        }

    @Before
    fun setUp() {
        BatteryAnnouncementReceiver.resetLevelFilterForTesting()
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
}