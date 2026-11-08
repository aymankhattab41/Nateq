package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AcapelaVoiceImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EngineStatusRegistry.clear()
        val acBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        if (acBase.exists()) {
            acBase.deleteRecursively()
        }
    }

    @After
    fun tearDown() {
        EngineStatusRegistry.clear()
        val acBase = EmbeddedStorage.baseDir(
            context,
            EmbeddedStorage.Engine.ACAPELA
        )
        if (acBase.exists()) {
            acBase.deleteRecursively()
        }
    }

    @Test
    fun importFromDirectory_copiesValidVoiceAndUpdatesRegistry() = runBlocking {
        val srcDir = tempFolder.newFolder("mehdi_source")
        File(srcDir, "mehdi.inf").writeText("Speaker=Mehdi\nIsoCode=ara")
        File(srcDir, "mehdi.dat").writeBytes(ByteArray(15_000))

        val result = AcapelaVoiceImporter.importFromDirectory(context, srcDir)
        assertTrue(result.success)
        assertEquals(1, result.importedVoices.size)
        assertTrue(result.importedVoices.first().contains("Mehdi"))

        val status = EngineStatusRegistry.statusOf(
            AcapelaEmbeddedVoiceProvider.PROVIDER_ID
        )
        assertEquals(
            EngineStatusRegistry.State.VOICES_PRESENT,
            status?.state
        )
    }

    @Test
    fun importFromDirectory_failsOnEmptySource() = runBlocking {
        val emptyDir = tempFolder.newFolder("empty_source")
        val result = AcapelaVoiceImporter.importFromDirectory(context, emptyDir)
        assertFalse(result.success)
        assertTrue(result.errorMessage?.isNotBlank() == true)
    }
}
