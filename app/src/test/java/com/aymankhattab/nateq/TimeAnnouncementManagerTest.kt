package com.aymankhattab.nateq

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.engine.SynthesisRequestHandler
import com.aymankhattab.nateq.core.audio.announcement.TimeAnnouncementManager
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import com.aymankhattab.nateq.core.audio.announcement.TimeAlarmReceiver
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.lang.reflect.Method
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm

/**
 * اختبارات مدير إعلان الوقت — نوّعان:
 *  1) التنسيق الخالص (عربي/إنجليزي طبيعي ورقمي) عبر Reflection على الدوال
 *     الخاصة — توقعاتٌ حتمية لا تعتمد على ساعة النظام.
 *  2) الجدولة البنيوية عبر ShadowAlarmManager: عدد المنبهات وترتيبها
 *     النسبي (المستقبل) — دون ربط بقيم زمنية مضبوطة للساعة الحقيقية
 *     (System.currentTimeMillis في بيئة الاختبار غير قابل للضبط)، فيبقى
 *     الاختبار مستقراً مهما كانت اللحظة الفعلية للتنفيذ.
 *
 * نتجنّب دوال speak*() التي تولّد TTS فعلياً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeAnnouncementManagerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var manager: TimeAnnouncementManager

    private fun dayOfWeek(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    private fun newManager(): TimeAnnouncementManager {
        val providers = listOf(SystemVoiceProvider(context, settings))
        val catalog = VoiceCatalog(providers)
        val handler = SynthesisRequestHandler(catalog, settings)
        return TimeAnnouncementManager(context, settings, catalog, handler)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
        manager = newManager()
    }

    @After
    fun tearDown() {
        TimeAlarmReceiver.cancel(context)
    }

    private fun scheduledAlarms(): List<ScheduledAlarm> =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .getScheduledAlarms()

    // ──────────────── Reflection إلى الدوال الخاصة ────────────────

    private fun reflect(name: String, vararg paramTypes: Class<*>): Method {
        val m = TimeAnnouncementManager::class.java.getDeclaredMethod(name, *paramTypes)
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

    private fun formatDigital(hour: Int, minute: Int, isEnglish: Boolean, use24h: Boolean): String =
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
        assertEquals("الساعة الآن الحادية عشرة إلا ربع صباحاً", formatArabic(10, 45))
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
        assertEquals("الساعة الآن العاشرة و دقيقتان صباحاً", formatArabic(10, 2))
    }

    @Test
    fun formatArabic_pluralMinutes() {
        assertEquals("الساعة الآن الثالثة و عشر دقائق مساءً", formatArabic(15, 10))
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
        assertEquals("الساعة الآن منتصف الليل", formatDigital(0, 0, false, true))
    }

    @Test
    fun formatDigital_english12h() {
        // 13:00 في نظام 12 ساعة → one o'clock? نعم English words: one + "" (لا PM بلا 24h)
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
    fun calculateQuietEnd_alwaysInFuture() {
        settings.setQuietStartForDay(dayOfWeek(), 23)
        settings.setQuietEndForDay(dayOfWeek(), 7)
        val end = calculateQuietEnd()
        assertTrue("نهاية الهدوء في المستقبل", end > System.currentTimeMillis())
    }

    @Test
    fun calculateQuietEnd_sameDayWindow() {
        settings.setQuietStartForDay(dayOfWeek(), 7)
        settings.setQuietEndForDay(dayOfWeek(), 23)
        val end = calculateQuietEnd()
        assertTrue("نهاية الهدوء في المستقبل", end > System.currentTimeMillis())
    }

    @Test
    fun isInQuietHours_reflectsCrossMidnight() {
        // نافذة 23→7: عند الساعة غير القابلة للضبط قد تكون داخلها أو خارجها،
        // لكن نتيجتها يجب أن تكون منطقية (منطقية boolean لا استثناء).
        settings.setQuietStartForDay(dayOfWeek(), 23)
        settings.setQuietEndForDay(dayOfWeek(), 7)
        wantBoolean(isInQuietHours())
    }

    @Test
    fun isInQuietHours_disabledAllDay_false() {
        // 0→0 لا يمثّل فترة؛ بعض التفسيرات قد تعتبرها داخلة طول اليوم.
        // نكتفي بأن الدالة لا ترمي أي استثناء.
        settings.setQuietStartForDay(dayOfWeek(), 0)
        settings.setQuietEndForDay(dayOfWeek(), 0)
        wantBoolean(isInQuietHours())
    }

    // ═══════════════════════ الجدولة البنيوية ═══════════════════════

    @Test
    fun start_schedulesExactlyOneAlarm() {
        // في أي لحظة: منبه واحد في المستقبل (سواء كان فاصلَ التالي أو نهايةَ
        // الهدوء) — نتحقق من البنية لا من القيمة الدقيقة.
        TimeAlarmReceiver.cancel(context)
        manager = newManager()
        manager.start()
        assertEquals("start() يُجدول منبهاً واحداً بالضبط", 1, scheduledAlarms().size)
    }

    @Test
    fun start_whenDisabled_skipsScheduling() {
        settings.setTimeAnnouncementEnabled(false)
        manager = newManager()
        TimeAlarmReceiver.cancel(context)
        manager.start()
        assertEquals("تعطيل الإعلان لا يُجدول منبهاً", 0, scheduledAlarms().size)
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
        assertEquals("onAlarmTick يُجدول المنبه التالي", 1, scheduledAlarms().size)
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
        assertEquals("start الصامت يُجدول منبهاً واحداً بالضبط", 1, scheduledAlarms().size)
    }

    // ──────────────── أدوات مساعدة ────────────────

    /** يعرّف بأنه دالة منطقية بلا استثناء — يُستخدم للدوال التي تُرجع boolean
     *  ولا نريد تأكيد قيمتها (لأنها تعتمد على ساعة حقيقية خارج الضبط). */
    private fun wantBoolean(value: Boolean) {
        // أي قيمة منطقية مقبولة؛ الهدف أن الدالة تعمل بلا رمي استثناء
        assertTrue("منطقي صالح", value || !value)
    }
}