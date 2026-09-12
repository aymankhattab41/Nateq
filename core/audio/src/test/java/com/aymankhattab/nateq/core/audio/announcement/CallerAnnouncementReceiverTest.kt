package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * يغطي تطبيع رقم المتصل الخاص/المجهول: «-1» و«UNKNOWN» ونظائرهما تُستبعد قبل
 * أي بحث في الخريطة المخصصة أو سجلّ الاتصالات/المكالمات — فلا يُنطق اسم جهة
 * اتصالٍ تتصادف أرقامها (كملحق «1» لرموز أمريكا) لمكالمةٍ مجهولةٍ فعلياً.
 * كما يتأكد أن الأرقام الحقيقية تُنتَج بشكلها الرقمي المجرّد فقط للبحث.
 * ويغطي أيضاً منطق سحب إذن READ_PHONE_STATE: الشفاء الذاتي الذي يطفئ تفعيل
 * إعلان المتصل عند سحبه رغم بقاء التفعيل قائماً.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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

    private fun match(key: String, to: String?): String? =
        CallerAnnouncementReceiver().matchCustomName(
            mapOf(key to "أحمد"), to
        )

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
}