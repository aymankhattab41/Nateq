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
 * اختبارات [resolveDataDir] للمزوّد المدمج (ع٣١-2).
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
        val dir = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        if (dir.exists()) dir.deleteRecursively()
    }

    @Test
    fun vocalizer_resolveDataDir_nullWhenEmpty_dirWhenFilesPresent() {
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
}
