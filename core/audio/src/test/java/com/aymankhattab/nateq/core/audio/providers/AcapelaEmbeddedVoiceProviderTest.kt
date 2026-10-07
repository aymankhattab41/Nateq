package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.acapelagroup.android.tts.AcattsandroidService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class AcapelaEmbeddedVoiceProviderTest {

    private lateinit var context: Context
    private lateinit var provider: AcapelaEmbeddedVoiceProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = AcapelaEmbeddedVoiceProvider(context)
    }

    @Test
    fun testProviderMetadata() {
        assertEquals("acapela_embedded", provider.providerId)
        assertTrue(provider.displayName.contains("Acapela"))
    }

    @Test
    fun testAcattsandroidServiceCreation() {
        val service = AcattsandroidService()
        assertNotNull(service)
    }

    @Test
    fun testCustomVoiceDirScan() {
        val tempDir = File(context.cacheDir, "test_acapela_voices")
        tempDir.mkdirs()
        try {
            val salmaFile = File(tempDir, "salma22k.dat")
            salmaFile.writeBytes(ByteArray(100))
            val mehdiFile = File(tempDir, "mehdi22k.bvz")
            mehdiFile.writeBytes(ByteArray(100))

            val customProvider = AcapelaEmbeddedVoiceProvider(
                context,
                tempDir
            )
            assertNotNull(customProvider)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
