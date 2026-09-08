package com.aymankhattab.nateq.core.audio.announcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * يغطي تطبيع رقم المتصل الخاص/المجهول: «-1» و«UNKNOWN» ونظائرهما تُستبعد قبل
 * أي بحث في الخريطة المخصصة أو سجلّ الاتصالات/المكالمات — فلا يُنطق اسم جهة
 * اتصالٍ تتصادف أرقامها (كملحق «1» لرموز أمريكا) لمكالمةٍ مجهولةٍ فعلياً.
 * كما يتأكد أن الأرقام الحقيقية تُنتَج بشكلها الرقمي المجرّد فقط للبحث.
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
}