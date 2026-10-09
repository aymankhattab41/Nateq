package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * اختبارات [EngineHostClient] مع خدمة المضيف (ع٣١-1 + ع٣١-7).
 *
 * يتحقق من أن نداء init بأي من الصيغ البديلة أو القانونية:
 * 1. لا ينتج رمز UNKNOWN_ENGINE.
 * 2. يعود بنتيجة المضيف الصحيحة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineHostClientIdTest {

    private lateinit var controller:
        ServiceController<EmbeddedEngineHostService>
    private lateinit var service: EmbeddedEngineHostService
    private lateinit var context: Context
    private lateinit var binder: IEmbeddedEngineHost

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = Robolectric.buildService(
            EmbeddedEngineHostService::class.java
        ).create()
        service = controller.get()
        binder = IEmbeddedEngineHost.Stub.asInterface(service.onBind(null))
        EngineHostClient.clearInitialized()
    }

    @After
    fun tearDown() {
        EngineHostClient.clearInitialized()
        controller.destroy()
    }

    @Test
    fun init_withVocalizerAliases_doesNotProduceUnknownEngine() {
        val aliases = listOf(
            "vocalizer_embedded",
            "VOCALIZER_EMBEDDED",
            "vocalizer"
        )
        for (alias in aliases) {
            val raw = binder.init(alias, "/dummy/data/dir", false)
            val res = HostProtocol.decodeResult(raw)
            assertNotEquals(
                "النداء بـ $alias لا ينتج UNKNOWN_ENGINE",
                "UNKNOWN_ENGINE",
                res.code
            )
        }
    }

    @Test
    fun init_withAcapelaAliases_doesNotProduceUnknownEngine() {
        val aliases = listOf(
            "acapela_embedded",
            "ACAPELA_EMBEDDED",
            "acapela"
        )
        for (alias in aliases) {
            val raw = binder.init(alias, "/dummy/data/dir", false)
            val res = HostProtocol.decodeResult(raw)
            assertNotEquals(
                "النداء بـ $alias لا ينتج UNKNOWN_ENGINE",
                "UNKNOWN_ENGINE",
                res.code
            )
        }
    }

    @Test
    fun init_withSherpaAliases_doesNotProduceUnknownEngine() {
        val aliases = listOf(
            "sherpa_onnx_piper",
            "SHERPA_ONNX_PIPER",
            "sherpa_piper",
            "SHERPA_PIPER",
            "sherpa"
        )
        for (alias in aliases) {
            val raw = binder.init(alias, "/dummy/data/dir", false)
            val res = HostProtocol.decodeResult(raw)
            assertNotEquals(
                "النداء بـ $alias لا ينتج UNKNOWN_ENGINE",
                "UNKNOWN_ENGINE",
                res.code
            )
        }
    }

    @Test
    fun init_withTrulyUnknownEngine_producesUnknownEngine() {
        val raw = binder.init("unknown_random_engine", "/dummy", false)
        val res = HostProtocol.decodeResult(raw)
        assertEquals("UNKNOWN_ENGINE", res.code)
    }
}
