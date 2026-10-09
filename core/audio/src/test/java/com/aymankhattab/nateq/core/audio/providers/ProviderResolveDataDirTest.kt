package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات [resolveDataDir] للمزودين الثلاثة (ع٣١-2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderResolveDataDirTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cleanupDirs()
    }

    @After
    fun tearDown() {
        cleanupDirs()
    }

    private fun cleanupDirs() {
        listOf(
            EmbeddedStorage.Engine.VOCALIZER,
            EmbeddedStorage.Engine.ACAPELA,
            EmbeddedStorage.Engine.SHERPA
        ).forEach { engine ->
            val dir = EmbeddedStorage.baseDir(context, engine)
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    @Test
    fun vocalizer_resolveDataDir_returnsNullWhenEmpty_andDirWhenFilesPresent() {
        val resolvedEmpty =
            VocalizerEmbeddedVoiceProvider.resolveDataDir(context)
        assertNull(resolvedEmpty)

        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        baseDir.mkdirs()
        val hdrFile = File(baseDir, "test_voice.hdr")
        hdrFile.writeBytes(ByteArray(100))
        val datFile = File(baseDir, "test_voice.dat")
        datFile.writeBytes(ByteArray(1_048_576))

        val resolved =
            VocalizerEmbeddedVoiceProvider.resolveDataDir(context)
        assertNotNull(resolved)
        assertEquals(baseDir.absolutePath, resolved!!.absolutePath)
    }

    @Test
    fun sherpa_resolveDataDir_returnsNullWhenEmpty_andModelDirWhenPresent() {
        val resolvedEmpty =
            SherpaOnnxVoiceProvider.resolveDataDir(context)
        assertNull(resolvedEmpty)

        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.SHERPA
        )
        val modelDir = File(baseDir, "v1")
        modelDir.mkdirs()
        File(modelDir, "model.onnx").writeBytes(ByteArray(16))
        File(modelDir, "tokens.txt").writeText("a 1\nb 2")

        val resolved =
            SherpaOnnxVoiceProvider.resolveDataDir(context)
        assertNotNull(resolved)
        assertEquals(modelDir.absolutePath, resolved!!.absolutePath)
    }

    @Test
    fun acapela_resolveDataDir_returnsNullWhenEmpty_andCandidateWhenPresent() {
        val resolvedEmpty =
            AcapelaEmbeddedVoiceProvider.resolveDataDir(context)
        assertNull(resolvedEmpty)

        val baseDir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        baseDir.mkdirs()
        File(baseDir, "voicelist").writeText("voice1\nvoice2")

        val resolved =
            AcapelaEmbeddedVoiceProvider.resolveDataDir(context)
        assertNotNull(resolved)
        assertEquals(baseDir.absolutePath, resolved!!.absolutePath)
    }
}
