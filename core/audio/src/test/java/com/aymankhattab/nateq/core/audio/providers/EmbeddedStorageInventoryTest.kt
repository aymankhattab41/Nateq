package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجرد الحي والتحقق من اكتمال الأصوات وحالات المحركات (ع٣١-4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmbeddedStorageInventoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineStatusRegistry.setFilesDir(context.filesDir)
        EngineStatusRegistry.clear()
        EngineStatusRegistry.nowProvider = { System.currentTimeMillis() }
        EmbeddedStorage.resetBumpTimeForTest(0L)
        cleanupAllDirs()
    }

    @After
    fun tearDown() {
        EngineStatusRegistry.clear()
        EngineStatusRegistry.nowProvider = { System.currentTimeMillis() }
        EmbeddedStorage.resetBumpTimeForTest(0L)
        cleanupAllDirs()
    }

    private fun cleanupAllDirs() {
        EmbeddedStorage.Engine.entries.forEach { engine ->
            val dir = EmbeddedStorage.baseDir(context, engine)
            if (dir.exists()) dir.deleteRecursively()
        }
        context.filesDir.listFiles()?.filter {
            it.name.startsWith("engine_status_")
        }?.forEach { it.delete() }
    }

    @Test
    fun vocalizer_emptyDir_reportsNotInstalled_andNotConfigured() {
        val provider = VocalizerEmbeddedVoiceProvider(context)
        val inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )

        assertTrue(inv.files.isEmpty())
        assertTrue(inv.completedVoices.isEmpty())
        assertFalse(inv.isCompleteVoicePresent)
        assertFalse(provider.isConfigured())

        val status = EngineStatusRegistry.statusOf(
            context,
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertEquals(EngineStatusRegistry.State.NOT_INSTALLED, status.state)
    }

    @Test
    fun vocalizer_incompleteOrSmallDat_notConsideredComplete() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        baseDir.mkdirs()

        // حالة 1: ملف .hdr فقط بدون .dat
        val hdr1 = File(baseDir, "voice1.hdr")
        hdr1.writeBytes(ByteArray(100))

        var inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        assertFalse(inv.isCompleteVoicePresent)

        // حالة 2: ملف .dat صغير (< 1MB)
        val dat1 = File(baseDir, "voice1.dat")
        dat1.writeBytes(ByteArray(500 * 1024)) // 500 KB

        inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        assertFalse(inv.isCompleteVoicePresent)

        // ملف غير معروف
        val randomFile = File(baseDir, "corrupted.bin")
        randomFile.writeBytes(ByteArray(200))

        inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        val unrec = inv.files.firstOrNull { it.name == "corrupted.bin" }
        assertNotNull(unrec)
        assertFalse(unrec!!.isRecognized)
    }

    @Test
    fun vocalizer_datWithoutHdr_isNotComplete() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        baseDir.mkdirs()

        // ملف .dat كبير (>1MB) لكن بدون .hdr مقابل
        val datOnly = File(baseDir, "laila.dat")
        datOnly.writeBytes(ByteArray(2 * 1024 * 1024))

        val inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        assertFalse(inv.isCompleteVoicePresent)
        assertTrue(inv.completedVoices.isEmpty())
    }

    @Test
    fun vocalizer_legacyDatNotInCatalog_isMarkedUnrecognized() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        baseDir.mkdirs()

        // ملف .dat قديم غير موجود في كتالوج النماذج
        val legacy = File(baseDir, "legacy_old_voice_v1.dat")
        legacy.writeBytes(ByteArray(2 * 1024 * 1024))

        val inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        val fileItem = inv.files.firstOrNull {
            it.name == "legacy_old_voice_v1.dat"
        }
        assertNotNull(fileItem)
        assertFalse(fileItem!!.isRecognized)
    }

    @Test
    fun vocalizer_completeVoice_makesConfiguredTrue_andVoicesPresent() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        baseDir.mkdirs()

        val hdr = File(baseDir, "laila.hdr")
        hdr.writeBytes(ByteArray(500))

        val dat = File(baseDir, "laila.dat")
        dat.writeBytes(ByteArray(1024 * 1024 + 100)) // > 1MB

        val inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        assertTrue(inv.isCompleteVoicePresent)
        assertEquals(1, inv.completedVoices.size)
        assertEquals("laila", inv.completedVoices[0])

        val provider = VocalizerEmbeddedVoiceProvider(context)
        assertTrue(provider.isConfigured())

        val status = EngineStatusRegistry.statusOf(
            context,
            VocalizerEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertEquals(EngineStatusRegistry.State.VOICES_PRESENT, status.state)
    }

    @Test
    fun sherpa_completeModel_detectedByInventory() {
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.SHERPA
        )
        val modelDir = File(baseDir, "piper-ar-voice")
        modelDir.mkdirs()

        val onnx = File(modelDir, "model.onnx")
        onnx.writeBytes(ByteArray(150 * 1024)) // > 100KB

        val tokens = File(modelDir, "tokens.txt")
        tokens.writeText("token 1\n")

        val inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.SHERPA
        )
        assertTrue(inv.isCompleteVoicePresent)

        val provider = SherpaOnnxVoiceProvider(context)
        assertTrue(provider.isConfigured())
    }

    @Test
    fun engineStatus_staleFailureInvalidatedOnBump() {
        val engineId = "vocalizer_embedded"
        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        baseDir.mkdirs()

        File(baseDir, "voice.hdr").writeBytes(ByteArray(100))
        File(baseDir, "voice.dat").writeBytes(ByteArray(2 * 1024 * 1024))

        // 1. تسجيل فشل في زمن T=1000
        EngineStatusRegistry.nowProvider = { 1_000L }
        EngineStatusRegistry.noteFailure(
            engineId,
            "NATIVE_INIT_FAILED",
            "Init error"
        )

        var status = EngineStatusRegistry.statusOf(context, engineId)
        assertEquals(
            EngineStatusRegistry.State.NATIVE_INIT_FAILED,
            status.state
        )

        // 2. حدوث تغيير في التخزين في زمن T=2000
        EmbeddedStorage.resetBumpTimeForTest(2_000L)

        // 3. قراءة الحالة بعد الـ bump تتجاهل الفشل القديم وتستخرج الحالة الحية
        status = EngineStatusRegistry.statusOf(context, engineId)
        assertEquals(EngineStatusRegistry.State.VOICES_PRESENT, status.state)
    }
}
