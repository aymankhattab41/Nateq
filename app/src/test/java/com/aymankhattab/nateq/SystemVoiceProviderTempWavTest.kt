package com.aymankhattab.nateq

import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.providers.SystemVoiceProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * يغطّي ملف التوليف الوحيد الثابت الاسم في [SystemVoiceProvider] (بند
 * تسريع النطق): لا يُنشأ ولا يُحذف ملفٌ جديد لكل نطق — الملف نفسه يُعاد
 * استخدامه والحذف النهائي يقع في [SystemVoiceProvider.shutdown].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemVoiceProviderTempWavTest {

    @Test
    fun tempWavFile_isStableAndInCacheDir() {
        val provider = SystemVoiceProvider(
            ApplicationProvider.getApplicationContext()
        )
        val method = SystemVoiceProvider::class.java.getDeclaredMethod(
            "tempWavFile"
        )
        method.isAccessible = true
        val first = method.invoke(provider) as File
        val second = method.invoke(provider) as File
        assertEquals("nateq_tts_session.wav", first.name)
        val cacheDir =
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .cacheDir
        assertEquals(
            "يقع ضمن cacheDir",
            cacheDir.canonicalPath,
            first.parentFile!!.canonicalPath
        )
        assertEquals("مستقر — الاستدعاء الثاني يعيد نفس الملف", first, second)
    }

    @Test
    fun shutdown_deletesSessionFile() {
        val context =
            ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = SystemVoiceProvider(context)
        val method = SystemVoiceProvider::class.java.getDeclaredMethod(
            "tempWavFile"
        )
        method.isAccessible = true
        val file = method.invoke(provider) as File
        file.writeBytes(ByteArray(8))
        provider.shutdown()
        assertFalse("يُحذف الملف عند الإغلاق النهائي", file.exists())
    }
}