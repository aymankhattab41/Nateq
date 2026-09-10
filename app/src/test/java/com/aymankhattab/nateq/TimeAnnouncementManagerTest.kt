package com.aymankhattab.nateq

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.announcement.TimeAnnouncementManager
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import com.aymankhattab.nateq.core.audio.announcement.TimeAlarmReceiver
import com.aymankhattab.nateq.core.common.TimeProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.lang.reflect.Method
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm

/**
 * اختبارات مدير إعلان الوقت — نوعان:
 *  1) التنسيق الخالص (عربي/إنجليزي طبيعي ورقمي) عبر Reflection على الدوال
 *     الخاصة — توقعاتٌ حتمية لا تعتمد على ساعة النظام.
 *  2) الجدولة البنيوية عبر ShadowAlarmManager: عدد المنبهات وترتيبها
 *     النسبي — بساعة افتراضية ثابتة (FakeClock) فيبقى الاختبار مستقراً
 *     مهما كانت اللحظة الفعلية للتنفيذ.
 *  3) ساعات الهدوء (حساب النهاية وفحص الدخول): حتمية عبر FakeClock عند
 *     لحظات مقصودة (عابرة لمنتصف الليل / نفس اليوم).
 *
 * نتجنّب دوال speak*() التي تولّد TTS فعلياً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeAnnouncementManagerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var fixedClock: FakeClock
    private lateinit var manager: TimeAnnouncementManager

    private fun newManager(
        clock: TimeProvider = fixedClock
    ): TimeAnnouncementManager {
        val providers = listOf(SystemVoiceProvider(context, settings))
        val catalog = VoiceCatalog(providers)
        val handler = SynthesisRequestHandler(catalog, settings)
        return TimeAnnouncementManager(
            context, settings, catalog, handler, clock
        )
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
        // ساعة افتراضية ثابتة (الأحد 2017-01-01 ظهراً) — لا تبعية على الساعة
        // الحية: لا يتغيّر عدد المنبهات مهما كانت لحظة التنفيذ الفعلية.
        fixedClock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 12, 0)
        )
        manager = newManager()
    }

    @After
    fun tearDown() {
        TimeAlarmReceiver.cancel(context)
    }

    private fun scheduledAlarms(): List<ScheduledAlarm> {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return shadowOf(alarmManager).getScheduledAlarms()
    }

    // ──────────────── Reflection إلى الدوال الخاصة ────────────────

    private fun reflect(name: String, vararg paramTypes: Class<*>): Method {
        val m = TimeAnnouncementManager::class.java.getDeclaredMethod(
            name, *paramTypes
        )
        m.isAccessible = true
        return m
    }

    private val INT_TYPE: Class<*> = Int::class.javaPrimitiveType!!
    private val BOOL_TYPE: Class<*> = Boolean::class.javaPrimitiveType!!

    private fun formatArabic(hour: Int, minute: Int): String =
        reflect("formatArabicNaturalTime", INT_TYPE, INT_TYPE)
            .invoke(manager, hour, minute) as String

    private fun formatEnglish(hour: Int, minute: Int): String =
        reflect("formatEnglishNaturalTime", INT_TYPE, INT_TYPE)
            .invoke(manager, hour, minute) as String

    private fun formatDigital(
        hour: Int, minute: Int, isEnglish: Boolean, use24h: Boolean
    ): String =
        reflect(
            "formatDigitalTime",
            INT_TYPE, INT_TYPE,
            BOOL_TYPE, BOOL_TYPE
        ).invoke(manager, hour, minute, isEnglish, use24h) as String

    private fun calculateQuietEnd(): Long =
        reflect("calculateQuietEndMillis").invoke(manager) as Long

    private fun isInQuietHours(): Boolean =
        reflect("isInQuietHours").invoke(manager) as Boolean

    // ═══════════════════════ التنسيق العربي الطبيعي ═══════════════════════

    @Test
    fun formatArabic_oClockMorning() {
        assertEquals("الساعة الآن العاشرة صباحاً", formatArabic(10, 0))
    }

    @Test
    fun formatArabic_quarterPast() {
        assertEquals("الساعة الآن العاشرة والربع صباحاً", formatArabic(10, 15))
    }

    @Test
    fun formatArabic_halfPast() {
        assertEquals("الساعة الآن العاشرة والنصف صباحاً", formatArabic(10, 30))
    }

    @Test
    fun formatArabic_quarterTo() {
        assertEquals(
            "الساعة الآن الحادية عشرة إلا ربع صباحاً",
            formatArabic(10, 45)
        )
    }

    @Test
    fun formatArabic_minutesToNextHour() {
        // 10:55 → «الحادية عشرة إلا خمس دقائق صباحاً»
        assertEquals(
            "الساعة الآن الحادية عشرة إلا خمس دقائق صباحاً",
            formatArabic(10, 55)
        )
    }

    @Test
    fun formatArabic_afternoon() {
        assertEquals("الساعة الآن الواحدة والربع مساءً", formatArabic(13, 15))
    }

    @Test
    fun formatArabic_midday() {
        assertEquals("الساعة الآن الثانية عشرة ظهراً", formatArabic(12, 0))
    }

    @Test
    fun formatArabic_midnight() {
        // 0:00 → الثانية عشرة صباحاً (الساعة 12 في الصباح)
        assertEquals("الساعة الآن الثانية عشرة صباحاً", formatArabic(0, 0))
    }

    @Test
    fun formatArabic_dualMinutes() {
        assertEquals(
            "الساعة الآن العاشرة و دقيقتان صباحاً",
            formatArabic(10, 2)
        )
    }

    @Test
    fun formatArabic_pluralMinutes() {
        assertEquals(
            "الساعة الآن الثالثة و عشر دقائق مساءً",
            formatArabic(15, 10)
        )
    }

    // ═══════════════════════ التنسيق الإنجليزي الطبيعي ═══════════════════════

    @Test
    fun formatEnglish_oClock() {
        assertEquals("2 o'clock PM", formatEnglish(14, 0))
        assertEquals("12 o'clock AM", formatEnglish(0, 0))
    }

    @Test
    fun formatEnglish_quarterPast() {
        assertEquals("quarter past 10 AM", formatEnglish(10, 15))
    }

    @Test
    fun formatEnglish_halfPast() {
        assertEquals("half past 10 AM", formatEnglish(10, 30))
    }

    @Test
    fun formatEnglish_quarterTo() {
        assertEquals("quarter to 11 AM", formatEnglish(10, 45))
    }

    @Test
    fun formatEnglish_minutes() {
        assertEquals("5 minutes past 10 AM", formatEnglish(10, 5))
        assertEquals("25 minutes past 10 AM", formatEnglish(10, 25))
        assertEquals("20 minutes to 11 PM", formatEnglish(22, 40))
    }

    // ═══════════════════════ التنسيق الرقمي ═══════════════════════

    @Test
    fun formatDigital_arabic24h_midnightPhrase() {
        assertEquals(
            "الساعة الآن منتصف الليل",
            formatDigital(0, 0, false, true)
        )
    }

    @Test
    fun formatDigital_english12h() {
        // 13:00 في نظام 12 ساعة → one o'clock? نعم English words: one + ""
        // (لا PM بلا 24h)
        val noon = formatDigital(12, 0, true, false)
        assertTrue(noon.startsWith("twelve"))
    }

    @Test
    fun formatDigital_english24h_noMeridiem() {
        val result = formatDigital(13, 0, true, true)
        assertTrue(result.startsWith("thirteen"))
    }

    @Test
    fun formatDigital_arabic12h_ordinalHour() {
        // 9:00 رقمي → «الساعة الآن التاسعة»
        assertEquals("الساعة الآن التاسعة", formatDigital(9, 0, false, false))
    }

    // ═══════════════════════ حسابات ساعات الهدوء ═══════════════════════

    @Test
    fun calculateQuietEnd_crossMidnight_returnsTomorrowEnd() {
        // الأحد 2017-01-01 23:30 — نافذة 23→7 تعبر منتصف الليل، فتُحسب نهاية
        // الهدوء صباح الاثنين 07:00 (لحظة الغد) وليست نهاية اليوم المنقضي.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 23, 30)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 23)
        settings.setQuietEndForDay(day, 7)

        val end = calculateQuietEnd()
        assertEquals(
            millisFor(2017, Calendar.JANUARY, 2, 7, 0), end
        )
        assertTrue(
            "نهاية الهدوء في المستقبل",
            end > clock.currentTimeMillis()
        )
    }

    @Test
    fun calculateQuietEnd_sameDayWindow_returnsTodayEnd() {
        // الأحد 2017-01-01 10:00 — نافذة 7→23 تنتهي اليوم (23:00)
        // لأنها بعد الآن.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 10, 0)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 7)
        settings.setQuietEndForDay(day, 23)

        val end = calculateQuietEnd()
        assertEquals(
            millisFor(2017, Calendar.JANUARY, 1, 23, 0), end
        )
        assertTrue(
            "نهاية الهدوء في المستقبل",
            end > clock.currentTimeMillis()
        )
    }

    @Test
    fun isInQuietHours_crossMidnight_insideWindow() {
        // قبل الفجر (05:00) داخل نافذة 23→7 العابرة لمنتصف الليل.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 5, 0)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 23)
        settings.setQuietEndForDay(day, 7)
        assertTrue(isInQuietHours())
    }

    @Test
    fun isInQuietHours_crossMidnight_outsideWindow() {
        // ظهر الأحد (12:00) خارج نافذة 23→7.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 12, 0)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 23)
        settings.setQuietEndForDay(day, 7)
        assertFalse(isInQuietHours())
    }

    @Test
    fun isInQuietHours_sameDayWindow_inside() {
        // ظهر الأحد (12:00) داخل نافذة 7→23.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 12, 0)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 7)
        settings.setQuietEndForDay(day, 23)
        assertTrue(isInQuietHours())
    }

    @Test
    fun isInQuietHours_sameDayWindow_outside() {
        // قبل الفجر (05:00) خارج نافذة 7→23.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 5, 0)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 7)
        settings.setQuietEndForDay(day, 23)
        assertFalse(isInQuietHours())
    }

    @Test
    fun isInQuietHours_disabledAllDay_false() {
        // 0→0 لا يمثّل فترة: دائماً خارج ساعات الهدوء مهما كانت الساعة.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 12, 0)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 0)
        settings.setQuietEndForDay(day, 0)
        assertFalse(isInQuietHours())
    }

    @Test
    fun isInQuietHours_disabledDay_falseEvenInsideWindow() {
        // اليوم المعطَّل (مفتاح الشاشة) يُستثنى كلياً: ظهر الأحد داخل نافذة
        // 7→23 لكن المفتاح معطّل فلا يكون في ساعات الهدوء البتة.
        val clock = FakeClock(
            millisFor(2017, Calendar.JANUARY, 1, 12, 0)
        )
        manager = newManager(clock)
        val day = clock.now().get(Calendar.DAY_OF_WEEK)
        settings.setQuietStartForDay(day, 7)
        settings.setQuietEndForDay(day, 23)
        settings.setDayQuietEnabled(day, false)
        assertFalse(isInQuietHours())
    }

    // ═══════════════════════ الجدولة البنيوية ═══════════════════════

    @Test
    fun start_schedulesExactlyOneAlarm() {
        // بساعة افتراضية ثابتة خارج الهدوء: منبه واحد في المستقبل — تحقّق من
        // البنية لا من القيمة الدقيقة (الكمية حتمية مهما كانت لحظة التنفيذ).
        TimeAlarmReceiver.cancel(context)
        manager = newManager()
        manager.start()
        assertEquals(
            "start() يُجدول منبهاً واحداً بالضبط",
            1, scheduledAlarms().size
        )
    }

    @Test
    fun start_whenDisabled_skipsScheduling() {
        settings.setTimeAnnouncementEnabled(false)
        manager = newManager()
        TimeAlarmReceiver.cancel(context)
        manager.start()
        assertEquals(
            "تعطيل الإعلان لا يُجدول منبهاً",
            0, scheduledAlarms().size
        )
    }

    @Test
    fun onAlarmTick_whenDisabled_cancels() {
        settings.setTimeAnnouncementEnabled(false)
        manager = newManager()
        TimeAlarmReceiver.cancel(context)
        manager.onAlarmTick()
        assertEquals("تعطيل الإعلان يزيل المنبه", 0, scheduledAlarms().size)
    }

    @Test
    fun onAlarmTick_whenEnabled_schedulesNext() {
        settings.setTimeAnnouncementEnabled(true)
        settings.setTimeAnnouncementInterval(30)
        settings.setAllAnnouncementsEnabled(true)
        manager = newManager()
        TimeAlarmReceiver.cancel(context)
        manager.onAlarmTick()
        assertEquals(
            "onAlarmTick يُجدول المنبه التالي",
            1, scheduledAlarms().size
        )
    }

    @Test
    fun stop_cancelsAlarm() {
        settings.setTimeAnnouncementEnabled(true)
        manager = newManager()
        TimeAlarmReceiver.cancel(context)
        manager.start()
        assertEquals(1, scheduledAlarms().size)
        manager.stop()
        assertEquals("stop() يلغي المنبه", 0, scheduledAlarms().size)
    }

    @Test
    fun start_silent_stillSchedulesExactlyOneAlarm() {
        // مسار الإقلاع/إعادة الجدولة: start(announceImmediately = false) يجب أن
        // يجدول منبهاً واحداً بالضبط دون نطق فوري (تغذية Boot Glitch) — البنية
        // مثل start() العادي، والفرق المقصود في النطقين فقط.
        TimeAlarmReceiver.cancel(context)
        manager = newManager()
        manager.start(announceImmediately = false)
        assertEquals(
            "start الصامت يُجدول منبهاً واحداً بالضبط",
            1, scheduledAlarms().size
        )
    }

    // ──────────────── أدوات مساعدة ────────────────

    /** لحظة محددة حتمية (سنة/شهر/يوم/ساعة/دقيقة بالمنطقة الافتراضية). */
    private fun millisFor(
        year: Int, month: Int, day: Int, hour: Int, minute: Int
    ): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
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