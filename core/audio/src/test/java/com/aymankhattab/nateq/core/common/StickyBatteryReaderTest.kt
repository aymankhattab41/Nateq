package com.aymankhattab.nateq.core.common

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** يغطي أداة البث اللاصق للبطارية الموحّدة (بند 4.2): القراءة العابرة
 *  تُجيب null بلا بثٍّ لاصق، وتعيد آخرَ بثٍّ لاصق للبطارية انشُر في
 *  ShadowInstrumentation (نفس سلوك النظام على أندرويد 35). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StickyBatteryReaderTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun `transient read without a sticky broadcast returns null`() {
        assertNull(readStickyBattery(context))
    }

    @Test
    fun `transient read returns the last posted sticky level and scale`() {
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 63)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
            }
        )

        val battery = readStickyBattery(context)
        assertNotNull(battery)
        assertEquals(
            63, battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        )
        assertEquals(
            100, battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        )
    }
}