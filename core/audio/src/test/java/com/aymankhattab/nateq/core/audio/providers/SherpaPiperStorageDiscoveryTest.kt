package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات اكتشاف ومسارات نماذج Sherpa/Piper وهجرتها (ع٣١-3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SherpaPiperStorageDiscoveryTest {

    private lateinit var context: Context
    private lateinit var provider: SherpaOnnxVoiceProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = SherpaOnnxVoiceProvider(context)
        cleanupDirs()
    }

    @After
    fun tearDown() {
        cleanupDirs()
    }

    private fun cleanupDirs() {
        listOf(
            EmbeddedStorage.Engine.SHERPA,
            EmbeddedStorage.Engine.PIPER
        ).forEach { engine ->
            val dir = EmbeddedStorage.baseDir(context, engine)
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    @Test
    fun legacyPiperDir_discoveredBySherpaProvider_andConfiguredBecomesTrue() {
        assertFalse(provider.isConfigured())
        assertTrue(provider.discoverModels().isEmpty())

        // وضع نموذج وهمي داخل filesDir/Piper/<name>/
        val piperBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.PIPER
        )
        val voiceDir = File(piperBase, "ar_test_voice")
        voiceDir.mkdirs()
        File(voiceDir, "voice.onnx").writeBytes(ByteArray(32))
        File(voiceDir, "tokens.txt").writeText("a 1\nb 2\n")

        // إشعار التخزين بتغيّر المحتوى
        EmbeddedStorage.bumpVersion()
        EnginePicker.invalidateAfterStorageChange()

        val models = provider.discoverModels()
        assertEquals(1, models.size)
        assertEquals("sherpa_voice", models[0].id)
        assertTrue(provider.isConfigured())
    }

    @Test
    fun isConfiguredCached_invalidatedOnVersionBump() {
        assertFalse(provider.isConfigured())

        val piperBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.PIPER
        )
        val voiceDir = File(piperBase, "voice_fast")
        voiceDir.mkdirs()
        File(voiceDir, "model.onnx").writeBytes(ByteArray(32))
        File(voiceDir, "tokens.txt").writeText("x 1\n")

        // قبل رفع الـ version: الكاش ما زال false
        assertFalse(provider.isConfigured())

        // رفع رقم الإصدار يُبطل الكاش فورياً
        EmbeddedStorage.bumpVersion()
        assertTrue(provider.isConfigured())
    }

    @Test
    fun migratePiperToSherpa_movesFilesToSherpaTTS_andKeepsDiscovered() {
        val piperBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.PIPER
        )
        val sherpaBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.SHERPA
        )

        val voiceDir = File(piperBase, "legacy_piper_model")
        voiceDir.mkdirs()
        File(voiceDir, "model.onnx").writeBytes(ByteArray(32))
        File(voiceDir, "tokens.txt").writeText("t 1\n")

        val moved = EmbeddedStorage.migratePiperToSherpa(context)
        assertEquals(1, moved)

        val targetDir = File(sherpaBase, "legacy_piper_model")
        assertTrue(targetDir.exists())
        assertTrue(File(targetDir, "model.onnx").exists())
        assertTrue(File(targetDir, "tokens.txt").exists())
        assertFalse(voiceDir.exists())

        val models = provider.discoverModels()
        assertEquals(1, models.size)
        assertTrue(provider.isConfigured())
    }
}
