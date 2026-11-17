package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * حارس شرط API لمحرك مايكروسوفت المدمج: كان isConfigured يتجاهل مستوى API
 * فيُظهر المحرك كمهيّأ على أندرويد 7 فأقل رغم أن مايكروسوفت تشترط API 26
 * (أندرويد 8.0) فأعلى — فيفشل النطق لاحقاً. الاختبار يسجّل الانحدار: تحت 26
 * رفضٌ واضح، وفوقها يمضي إلى الجرد الحقيقي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 37])
class MicrosoftEmbeddedApiGateTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** مجلد صوت كامل بحسب [MicrosoftVoiceManifest] حتى لا يكون الرفض تحت
     *  API 26 بسبب غياب الملفات بل بسبب المستوى وحده. */
    private fun completeVoiceDir(): File {
        val dir = File(context.filesDir, "MsApiGateVoice")
        dir.mkdirs()
        File(dir, "am_v5_decoder.bin").writeBytes(byteArrayOf(1, 2, 3))
        return dir
    }

    /** حدّ القرار 26 بالضبط: 24/25 مرفوضان، 26 فأعلى مقبول. */
    @Test
    fun supportsApi_boundaryIsExactly26() {
        assertFalse(MicrosoftEmbeddedVoiceProvider.supportsApi(24))
        assertFalse(MicrosoftEmbeddedVoiceProvider.supportsApi(25))
        assertTrue(MicrosoftEmbeddedVoiceProvider.supportsApi(26))
        assertTrue(MicrosoftEmbeddedVoiceProvider.supportsApi(37))
    }

    /** سبب الرفض صريح ويذكر الرقم 26 حتى لا يكون الرفض صامتاً. */
    @Test
    fun apiRejectionReason_mentionsRequiredApi26() {
        val reason = MicrosoftEmbeddedVoiceProvider.apiRejectionReason(24)
        assertTrue(reason.contains("26"))
        assertTrue(reason.contains("24"))
        assertTrue(reason.contains("يُرفض"))
    }

    /** الانحدار الأساسي: مع وجود صوت كامل، تحت API 26 تُرفض التهيئة
     *  رغم الملفات، وعلى 26 فأعلى تُمضي إلى فحص الجرد فتُقبل. */
    @Test
    fun isConfigured_belowApi26_rejected_despiteCompleteVoice() {
        val provider = MicrosoftEmbeddedVoiceProvider(
            context, completeVoiceDir()
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            assertFalse(provider.isConfigured())
        } else {
            assertTrue(provider.isConfigured())
        }
    }
}
