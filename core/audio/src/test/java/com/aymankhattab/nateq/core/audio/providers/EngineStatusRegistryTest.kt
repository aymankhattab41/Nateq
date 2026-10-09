package com.aymankhattab.nateq.core.audio.providers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * حارس ع٢٩-8 بند 1 + ع٣٠-10: سجلّ الحالات يُعلِن سببَ الفشل
 * ولا يبتلعه، ويحفظ الحالة في ملف engine_status_<id>.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineStatusRegistryTest {

    private val engine = "vocalizer_embedded"

    @Before
    fun reset() {
        EmbeddedStorage.resetBumpTimeForTest(0L)
        EngineStatusRegistry.clear()
        EngineStatusRegistry.nowProvider = { 1_000L }
    }

    @After
    fun restoreGlobalState() {
        EmbeddedStorage.resetBumpTimeForTest(0L)
        EngineStatusRegistry.clear()
        EngineStatusRegistry.nowProvider = {
            System.currentTimeMillis()
        }
    }

    @Test
    fun stateForCode_crashLicenseAndFallback() {
        assertEquals(
            EngineStatusRegistry.State.CRASHED_LAST_RUN,
            EngineStatusRegistry.stateForCode("CRASHED_LAST_RUN")
        )
        assertEquals(
            EngineStatusRegistry.State.CRASHED_LAST_RUN,
            EngineStatusRegistry.stateForCode("crashed_last_run")
        )
        assertEquals(
            EngineStatusRegistry.State.LICENSE_ISSUE,
            EngineStatusRegistry.stateForCode("LICENSE_EXPIRED")
        )
        assertEquals(
            EngineStatusRegistry.State.LICENSE_ISSUE,
            EngineStatusRegistry.stateForCode("NO_LICENSEx")
        )
        assertEquals(
            EngineStatusRegistry.State.NATIVE_INIT_FAILED,
            EngineStatusRegistry.stateForCode("HOST_DIED")
        )
    }

    @Test
    fun noteFailure_recordsCodeAndReason() {
        EngineStatusRegistry.noteFailure(
            engine,
            "HOST_DIED",
            "المضيف مات"
        )
        val status = EngineStatusRegistry.statusOf(engine)
        assertNotNull(status)
        assertEquals(
            EngineStatusRegistry.State.NATIVE_INIT_FAILED,
            status!!.state
        )
        assertEquals("HOST_DIED", status.code)
        assertEquals("المضيف مات", status.lastError)
        assertEquals(1_000L, status.updatedAt)
    }

    @Test
    fun noteFilesPresent_doesNotEraseRecordedFailure() {
        EngineStatusRegistry.noteFailure(engine, "HOST_DIED", "سبب")
        EngineStatusRegistry.noteFilesPresent(engine)
        val status = EngineStatusRegistry.statusOf(engine)
        assertNotNull(status)
        assertEquals(
            EngineStatusRegistry.State.NATIVE_INIT_FAILED,
            status!!.state
        )
        assertEquals("سبب", status.lastError)
    }

    @Test
    fun noteFilesPresent_promotesNewOrAbsentToVoicesPresent() {
        EngineStatusRegistry.noteFilesAbsent(engine)
        EngineStatusRegistry.noteFilesPresent(engine)
        assertEquals(
            EngineStatusRegistry.State.VOICES_PRESENT,
            EngineStatusRegistry.statusOf(engine)!!.state
        )
        EngineStatusRegistry.clear()
        EngineStatusRegistry.noteFilesPresent(engine)
        assertEquals(
            EngineStatusRegistry.State.VOICES_PRESENT,
            EngineStatusRegistry.statusOf(engine)!!.state
        )
    }

    @Test
    fun noteFilesAbsent_clearsEverything() {
        EngineStatusRegistry.noteFailure(engine, "HOST_DIED", "سبب")
        EngineStatusRegistry.noteFilesAbsent(engine)
        val status = EngineStatusRegistry.statusOf(engine)
        assertNotNull(status)
        assertEquals(
            EngineStatusRegistry.State.NOT_INSTALLED,
            status!!.state
        )
        assertNull(status.lastError)
    }

    @Test
    fun noteReady_clearsPriorFailure() {
        EngineStatusRegistry.noteFailure(engine, "HOST_DIED", "سبب")
        EngineStatusRegistry.noteReady(engine)
        val status = EngineStatusRegistry.statusOf(engine)
        assertNotNull(status)
        assertEquals(EngineStatusRegistry.State.READY, status!!.state)
        assertNull(status.lastError)
    }

    @Test
    fun unknownEngine_readsAsNull() {
        assertNull(EngineStatusRegistry.statusOf("no_such"))
        assertTrue(EngineStatusRegistry.all().isEmpty())
    }

    @Test
    fun all_sortedAndIsolatedPerEngine() {
        EngineStatusRegistry.noteReady("b_engine")
        EngineStatusRegistry.noteFilesAbsent("a_engine")
        val all = EngineStatusRegistry.all()
        assertEquals(2, all.size)
        assertEquals("a_engine", all[0].engineId)
        assertEquals("b_engine", all[1].engineId)
        assertNotEquals(
            EngineStatusRegistry.statusOf("a_engine")!!.state,
            EngineStatusRegistry.statusOf("b_engine")!!.state
        )
    }

    @Test
    fun labelAr_coversEveryState() {
        val labels = EngineStatusRegistry.State.entries
            .map { EngineStatusRegistry.labelAr(it) }
        assertEquals(
            EngineStatusRegistry.State.entries.size,
            labels.size
        )
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun writeAndReadStatusFile_persistsAcrossMemoryClear() {
        val context: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val filesDir = context.filesDir
        EngineStatusRegistry.setFilesDir(filesDir)

        val status = EngineStatusRegistry.EngineStatus(
            engineId = "vocalizer",
            state = EngineStatusRegistry.State.NATIVE_INIT_FAILED,
            code = "NATIVE_CREATE_FAILED",
            lastError = "NoSuchFieldError: mNativeEnginePtr",
            updatedAt = 5_000L
        )
        EngineStatusRegistry.writeStatusFile(filesDir, status)

        // إفراغ الذاكرة لمحاكاة إعادة تشغيل الواجهة
        EngineStatusRegistry.statusesClearOnly()

        val read = EngineStatusRegistry.statusOf("vocalizer_embedded")
        assertNotNull(read)
        assertEquals(
            EngineStatusRegistry.State.NATIVE_INIT_FAILED,
            read!!.state
        )
        assertEquals("NATIVE_CREATE_FAILED", read.code)
        assertEquals(
            "NoSuchFieldError: mNativeEnginePtr",
            read.lastError
        )
        assertEquals(5_000L, read.updatedAt)
    }

    @Test
    fun normalizeId_mapsHostAndProviderVariants() {
        assertEquals(
            "vocalizer_embedded",
            EngineStatusRegistry.normalizeId("vocalizer")
        )
        assertEquals(
            "vocalizer_embedded",
            EngineStatusRegistry.normalizeId("vocalizer_embedded")
        )
        assertEquals(
            "acapela_embedded",
            EngineStatusRegistry.normalizeId("acapela")
        )
        assertEquals(
            "acapela_embedded",
            EngineStatusRegistry.normalizeId("acapela_embedded")
        )
        assertEquals(
            "sherpa_onnx_piper",
            EngineStatusRegistry.normalizeId("sherpa")
        )
        assertEquals(
            "sherpa_onnx_piper",
            EngineStatusRegistry.normalizeId("sherpa_onnx_piper")
        )
    }

    @Test
    fun statusOf_liveInventory_ignoresStaleDiskVoicesPresent() {
        val context: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val filesDir = context.filesDir
        EngineStatusRegistry.setFilesDir(filesDir)

        // 1. كتابة ملف قرص يحتوي على VOICES_PRESENT
        val staleDiskStatus = EngineStatusRegistry.EngineStatus(
            engineId = "vocalizer",
            state = EngineStatusRegistry.State.VOICES_PRESENT,
            updatedAt = 10_000L
        )
        EngineStatusRegistry.writeStatusFile(filesDir, staleDiskStatus)

        // إفراغ الذاكرة المؤقتة
        EngineStatusRegistry.statusesClearOnly()

        // 2. بدون ملفات حقيقية على القرص، الجرد الحي يعطي NOT_INSTALLED
        val status = EngineStatusRegistry.statusOf(context, "vocalizer")
        assertEquals(
            "حالة القرص القديمة تُستبدل بالجرد الحي",
            EngineStatusRegistry.State.NOT_INSTALLED,
            status.state
        )
    }
}
