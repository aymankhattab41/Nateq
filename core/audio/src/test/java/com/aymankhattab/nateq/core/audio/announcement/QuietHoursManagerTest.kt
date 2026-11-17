package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * اختبارات ساعات الهدوء:
 * - فحص صيغة 24 ساعة و12 ساعة.
 * - اليوم الصامت بالكامل (QUIET_HOUR_FULL_MASK).
 * - الساعات من 1 إلى 7 (صمت 1..7 ونطق 8).
 * - ترحيل الإعدادات القديمة إلى قناع البتات دون فقدان بيانات.
 * - انتقال منتصف الليل (هدوء ليلة الجمعة لا يسكت صباح السبت).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class QuietHoursManagerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = SettingsRepository(context)
    }

    @Test
    fun testFullSilentDay() {
        val friday = Calendar.FRIDAY
        settings.setQuietHoursBitmaskForDay(
            friday,
            SettingsRepository.QUIET_HOUR_FULL_MASK
        )

        assertTrue(settings.isDayQuietEnabled(friday))
        for (h in 1..24) {
            assertTrue(
                "Hour $h should be set",
                settings.isHourBitSet(friday, h)
            )
        }

        // فحص الساعات النهارية والليلية
        for (calHour in 1..23) {
            assertTrue(
                "Calendar hour $calHour should be quiet",
                settings.isHourQuiet(friday, calHour)
            )
        }
        assertTrue(settings.isHourQuiet(friday, 24))
    }

    @Test
    fun testHours1To7_Silent1To7_Speaks8() {
        val monday = Calendar.MONDAY
        // تعيين الساعات 1..7 صامتة (بتات 0..6)
        var mask = 0
        for (h in 1..7) {
            mask = mask or (1 shl (h - 1))
        }
        settings.setQuietHoursBitmaskForDay(monday, mask)

        for (h in 1..7) {
            assertTrue("Hour $h must be quiet", settings.isHourQuiet(monday, h))
        }
        // الساعة 8 تنطق
        assertFalse("Hour 8 must speak", settings.isHourQuiet(monday, 8))
        assertFalse("Hour 12 must speak", settings.isHourQuiet(monday, 12))
        assertFalse("Hour 23 must speak", settings.isHourQuiet(monday, 23))
    }

    @Test
    fun testMidnightTransition_FridayNightDoesNotSilenceSaturdayMorning() {
        val friday = Calendar.FRIDAY // 6
        val saturday = Calendar.SATURDAY // 7

        // ليلة الجمعة: الساعات 22 و23 و24 صامتة
        var fridayMask = 0
        fridayMask = fridayMask or (1 shl (22 - 1))
        fridayMask = fridayMask or (1 shl (23 - 1))
        fridayMask = fridayMask or (1 shl (24 - 1))
        settings.setQuietHoursBitmaskForDay(friday, fridayMask)

        // السبت: كل الساعات تنطق (قناع فارغ)
        settings.setQuietHoursBitmaskForDay(saturday, 0)

        // الجمعة الساعة 23:00 صامتة
        assertTrue(settings.isHourQuiet(friday, 23))

        // منتصف الليل (00:00 صبيحة السبت): يقرأ ساعة 24 من ليلة الجمعة
        assertTrue(settings.isHourQuiet(saturday, 0))

        // السبت الساعة 01:00 صباحاً: ينطق (لا يسكت بهدوء ليلة الجمعة)
        assertFalse(settings.isHourQuiet(saturday, 1))
        // السبت الساعة 08:00 صباحاً: ينطق
        assertFalse(settings.isHourQuiet(saturday, 8))
    }

    @Test
    fun testMigrationFromOldOvernightSchedule() {
        val prefs = context.getSharedPreferences(
            "nateq_settings",
            Context.MODE_PRIVATE
        )
        // إعداد قديم: الجمعة هدوء من 23 إلى 7
        prefs.edit()
            .putBoolean("time_quiet_day6_enabled", true)
            .putInt("time_quiet_day6_start", 23)
            .putInt("time_quiet_day6_end", 7)
            .putBoolean("time_quiet_day7_enabled", true)
            .putInt("time_quiet_day7_start", 23)
            .putInt("time_quiet_day7_end", 7)
            .putBoolean(
                SettingsRepository.KEY_QUIET_BITMASK_MIGRATED,
                false
            )
            .commit()

        val repo = SettingsRepository(context)
        val fridayMask = repo.getQuietHoursBitmaskForDay(Calendar.FRIDAY)
        val saturdayMask = repo.getQuietHoursBitmaskForDay(Calendar.SATURDAY)

        // الجمعة تحوي ساعة 23 و24
        assertTrue((fridayMask and (1 shl (23 - 1))) != 0)
        assertTrue((fridayMask and (1 shl (24 - 1))) != 0)

        // السبت يحوي ساعات 1..7 المرحلة من ليلة الجمعة
        for (h in 1..7) {
            assertTrue(
                "Saturday hour $h migrated",
                (saturdayMask and (1 shl (h - 1))) != 0
            )
        }
    }

    @Test
    fun test24HourToggleMode() {
        settings.setTime24Hour(true)
        assertTrue(settings.isTime24Hour())

        settings.setTime24Hour(false)
        assertFalse(settings.isTime24Hour())
    }

    /**
     * [بناء 10728] نهاية الهدوء تُشتقّ من قناع البتات: صمت 1..7 ينتهي
     * 08:00 لا من المفتاح القديم الذي لم يعد يُحدَّث.
     */
    @Test
    fun testQuietEndFromBitmask_1To7EndsAt8() {
        settings.setQuietHoursBitmaskForDay(
            Calendar.MONDAY,
            maskForHours(1..7)
        )
        val mondayMorning = Calendar.getInstance().apply {
            set(2026, Calendar.JUNE, 1, 5, 10, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(
            Calendar.MONDAY,
            mondayMorning.get(Calendar.DAY_OF_WEEK)
        )

        val end = TimeAnnouncementManager.quietEndMillisFrom(
            mondayMorning
        ) { day, hour -> settings.isHourQuiet(day, hour) }

        val endCal = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(Calendar.MONDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(8, endCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, endCal.get(Calendar.MINUTE))
        assertEquals(0, endCal.get(Calendar.SECOND))
    }

    /**
     * [بناء 10728] عبور منتصف الليل: هدوء الجمعة 23 و24 والسبت 1..3
     * ينتهي السبت 04:00.
     */
    @Test
    fun testQuietEndFromBitmask_crossesMidnightToSaturday4() {
        settings.setQuietHoursBitmaskForDay(
            Calendar.FRIDAY,
            maskForHours(listOf(23, 24))
        )
        settings.setQuietHoursBitmaskForDay(
            Calendar.SATURDAY,
            maskForHours(1..3)
        )
        val fridayNight = Calendar.getInstance().apply {
            set(2026, Calendar.JUNE, 5, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(
            Calendar.FRIDAY,
            fridayNight.get(Calendar.DAY_OF_WEEK)
        )

        val end = TimeAnnouncementManager.quietEndMillisFrom(
            fridayNight
        ) { day, hour -> settings.isHourQuiet(day, hour) }

        val endCal = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(Calendar.SATURDAY, endCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(4, endCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, endCal.get(Calendar.MINUTE))
    }

    /**
     * [بناء 10728] مفاتيح قديمة مضللة (23→7) لا تُقرأ: النتيجة من
     * القناع (1..7) = 08:00 ولو كان المفتاح القديم يعطي 07:00.
     */
    @Test
    fun testQuietEndIgnoresStaleStartEndKeys() {
        settings.setQuietHoursBitmaskForDay(
            Calendar.MONDAY,
            maskForHours(1..7)
        )
        context.getSharedPreferences(
            "nateq_settings",
            Context.MODE_PRIVATE
        ).edit()
            .putInt("time_quiet_day2_start", 23)
            .putInt("time_quiet_day2_end", 7)
            .commit()

        val mondayMorning = Calendar.getInstance().apply {
            set(2026, Calendar.JUNE, 1, 5, 10, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = TimeAnnouncementManager.quietEndMillisFrom(
            mondayMorning
        ) { day, hour -> settings.isHourQuiet(day, hour) }

        val endCal = Calendar.getInstance().apply { timeInMillis = end }
        assertEquals(8, endCal.get(Calendar.HOUR_OF_DAY))
    }

    /** قناع بتات لساعات 1..24 (قيمة بت = الساعة). */
    private fun maskForHours(hours: Iterable<Int>): Int {
        var mask = 0
        for (hour in hours) {
            mask = mask or (1 shl (hour - 1))
        }
        return mask
    }
}
