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
        shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>()
        ).denyPermissions(android.Manifest.permission.READ_PHONE_STATE)
        assertFalse(
            "بلا READ_PHONE_STATE نعتبر الإذن غائباً",
            receiver.hasCallerPermission(context)
        )
        shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>()
        ).grantPermissions(android.Manifest.permission.READ_PHONE_STATE)
        assertTrue(
            "بمنح READ_PHONE_STATE نعتبر الإذن حاضراً",
            receiver.hasCallerPermission(context)
        )
    }
}