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
 * اختبارات مسارات Sherpa/Piper وهجرتها واكتشافها الفوري (ع٣١-3 + ع٣١-7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmbeddedStorageSherpaTest {

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
        File(voiceDir, "voice.onnx").writeBytes(ByteArray(150 * 1024))
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
        File(voiceDir, "model.onnx").writeBytes(ByteArray(150 * 1024))
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

        val voiceDir = File(piperBase, "legacy_model")
        voiceDir.mkdirs()
        File(voiceDir, "model.onnx").writeBytes(ByteArray(150 * 1024))
        File(voiceDir, "tokens.txt").writeText("t 1\n")

        // تشغيل الهجرة
        EmbeddedStorage.migratePiperToSherpa(context)

        // التحقق من الانتقال إلى مجلد SherpaTTS
        val migratedDir = File(sherpaBase, "legacy_model")
        assertTrue(migratedDir.exists())
        assertTrue(File(migratedDir, "model.onnx").exists())
        assertTrue(File(migratedDir, "tokens.txt").exists())

        val models = provider.discoverModels()
        assertEquals(1, models.size)
        assertTrue(provider.isConfigured())
    }

    @Test
    fun allSearchDirs_containsBothSherpaAndPiperDirs() {
        val dirs = EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.SHERPA
        )
        val sherpaDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.SHERPA
        )
        val piperDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.PIPER
        )
        assertTrue(dirs.contains(sherpaDir))
        assertTrue(dirs.contains(piperDir))
    }
}
