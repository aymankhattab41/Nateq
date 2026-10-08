package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس بند ع٢٩-2 (1): نسخة واحدة لكل مزوّد مدمج طوال عمر
 * العملية — أي موضعٍ جديد يتجاوز [EmbeddedProviders] يخلق
 * نسخة ثانية فيفشل هذا الاختبار بهويّةٍ غير متطابقة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmbeddedProvidersTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EmbeddedProviders.shutdownAll()
    }

    @After
    fun tearDown() {
        EmbeddedProviders.shutdownAll()
    }

    @Test
    fun vocalizer_returnsSingleInstancePerProcess() {
        val first = EmbeddedProviders.vocalizer(context)
        val second = EmbeddedProviders.vocalizer(context)
        assertSame(first, second)
    }

    @Test
    fun acapela_returnsSingleInstancePerProcess() {
        val first = EmbeddedProviders.acapela(context)
        val second = EmbeddedProviders.acapela(context)
        assertSame(first, second)
    }

    @Test
    fun sherpa_returnsSingleInstancePerProcess() {
        val first = EmbeddedProviders.sherpa(context)
        val second = EmbeddedProviders.sherpa(context)
        assertSame(first, second)
    }

    @Test
    fun sharedInstance_ignoresDifferentContextWrappers() {
        val direct = EmbeddedProviders.vocalizer(context)
        val viaOther = EmbeddedProviders.vocalizer(
            context.applicationContext
        )
        assertSame(direct, viaOther)
    }

    @Test
    fun shutdownAll_clearsRegistrySoNextAccessIsFresh() {
        val before = EmbeddedProviders.acapela(context)
        EmbeddedProviders.shutdownAll()
        val after = EmbeddedProviders.acapela(context)
        assertNotSame(before, after)
    }

    @Test
    fun construction_doesNotCreateNativeEngine() {
        VocalizerEmbeddedVoiceProvider(context)
        assertNoHostBinding(HostProtocol.ENGINE_VOCALIZER)

        AcapelaEmbeddedVoiceProvider(context)
        assertNoHostBinding(HostProtocol.ENGINE_ACAPELA)

        SherpaOnnxVoiceProvider(context)
        assertNoHostBinding(HostProtocol.ENGINE_SHERPA)
    }

    @Test
    fun isConfigured_doesNotTouchNativeEngine() {
        VocalizerEmbeddedVoiceProvider(context).isConfigured()
        assertNoHostBinding(HostProtocol.ENGINE_VOCALIZER)

        AcapelaEmbeddedVoiceProvider(context).isConfigured()
        assertNoHostBinding(HostProtocol.ENGINE_ACAPELA)

        SherpaOnnxVoiceProvider(context).isConfigured()
        assertNoHostBinding(HostProtocol.ENGINE_SHERPA)
    }

    @Test
    fun sharedVocalizer_staysEnginelessUntilSynthesize() {
        val shared = EmbeddedProviders.vocalizer(context)
        shared.isConfigured()
        assertNoHostBinding(HostProtocol.ENGINE_VOCALIZER)
    }

    /**
     * ع٢٩-5: «محرك بلا نداء» = لا ربط بالمضيف؛ الفحص القرصي لا
     * ينشئ جلسة ولا يربطا بـ`:engine_host` أبداً.
     */
    private fun assertNoHostBinding(engineId: String) {
        assertFalse(
            "must not bind :engine_host for $engineId",
            EngineHostClient.isBound(engineId)
        )
    }
}
