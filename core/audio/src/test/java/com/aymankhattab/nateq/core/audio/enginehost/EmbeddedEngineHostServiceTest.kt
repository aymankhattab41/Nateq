package com.aymankhattab.nateq.core.audio.enginehost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * اختبارات خدمة المضيف [EmbeddedEngineHostService] (ع٣١-1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmbeddedEngineHostServiceTest {

    private lateinit var controller:
        ServiceController<EmbeddedEngineHostService>
    private lateinit var service: EmbeddedEngineHostService
    private lateinit var binder: IEmbeddedEngineHost

    @Before
    fun setUp() {
        controller = Robolectric.buildService(
            EmbeddedEngineHostService::class.java
        ).create()
        service = controller.get()
        binder = IEmbeddedEngineHost.Stub.asInterface(service.onBind(null))
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun init_withVocalizerEmbeddedAlias_doesNotReturnUnknownEngine() {
        val raw = binder.init("vocalizer_embedded", "/dummy/dir", false)
        val res = HostProtocol.decodeResult(raw)
        assertNotEquals("UNKNOWN_ENGINE", res.code)
    }

    @Test
    fun init_withAcapelaEmbeddedAlias_doesNotReturnUnknownEngine() {
        val raw = binder.init("acapela_embedded", "/dummy/dir", false)
        val res = HostProtocol.decodeResult(raw)
        assertNotEquals("UNKNOWN_ENGINE", res.code)
    }

    @Test
    fun init_withSherpaAliases_doesNotReturnUnknownEngine() {
        val raw1 = binder.init("sherpa_onnx_piper", "/dummy/dir", false)
        val res1 = HostProtocol.decodeResult(raw1)
        assertNotEquals("UNKNOWN_ENGINE", res1.code)

        val raw2 = binder.init("sherpa_piper", "/dummy/dir", false)
        val res2 = HostProtocol.decodeResult(raw2)
        assertNotEquals("UNKNOWN_ENGINE", res2.code)
    }

    @Test
    fun init_withUnknownEngine_returnsUnknownEngine() {
        val raw = binder.init("unsupported_engine_xyz", "/dummy/dir", false)
        val res = HostProtocol.decodeResult(raw)
        assertEquals("UNKNOWN_ENGINE", res.code)
    }

    @Test
    fun sessionFor_returnsSameInstanceForAliasesAndCanonicalId() {
        val sessionForMethod = EmbeddedEngineHostService::class.java
            .getDeclaredMethod("sessionFor", String::class.java)
        sessionForMethod.isAccessible = true

        val sVocalizerCanonical = sessionForMethod.invoke(
            service,
            HostProtocol.ENGINE_VOCALIZER
        )
        val sVocalizerAlias = sessionForMethod.invoke(
            service,
            "vocalizer_embedded"
        )
        assertNotNull(sVocalizerCanonical)
        assertSame(sVocalizerCanonical, sVocalizerAlias)

        val sAcapelaCanonical = sessionForMethod.invoke(
            service,
            HostProtocol.ENGINE_ACAPELA
        )
        val sAcapelaAlias = sessionForMethod.invoke(
            service,
            "acapela_embedded"
        )
        assertNotNull(sAcapelaCanonical)
        assertSame(sAcapelaCanonical, sAcapelaAlias)

        val sSherpaCanonical = sessionForMethod.invoke(
            service,
            HostProtocol.ENGINE_SHERPA
        )
        val sSherpaAlias1 = sessionForMethod.invoke(
            service,
            "sherpa_onnx_piper"
        )
        val sSherpaAlias2 = sessionForMethod.invoke(
            service,
            "sherpa_piper"
        )
        assertNotNull(sSherpaCanonical)
        assertSame(sSherpaCanonical, sSherpaAlias1)
        assertSame(sSherpaCanonical, sSherpaAlias2)
    }
}
