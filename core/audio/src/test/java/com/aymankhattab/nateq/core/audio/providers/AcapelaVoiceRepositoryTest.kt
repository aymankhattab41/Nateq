package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * اختبارات وحدة لسلامة مستودع أصوات Acapela وتخزينه المؤقت:
 * 1. لقطة الذاكرة getSnapshot لا تمس القرص ولا تحجب الخيط.
 * 2. التعداد المتكرر الدافئ لا يعيد مسح القرص.
 * 3. دمج الاستدعاءات المتزامنة (coalescing).
 * 4. إبطال الكاش فور رفع مراجعة التثبيت.
 * 5. تحقق الحزمة يعيد استخدام الكاش دون حساب SHA-256 كامل في المسار الدافئ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AcapelaVoiceRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var voiceDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AcapelaVoiceRepository.resetForTests()
        EmbeddedStorage.resetBumpTimeForTest(100L)

        // إعداد حزمة صوت تجريبية
        val base = tempFolder.newFolder("voices", "acapela")
        voiceDir = File(base, "salma_lf").apply { mkdirs() }
        File(voiceDir, "salma_lf.inf").writeText(
            "Speaker = \"Salma\"\nVoiceName = \"Salma\"\n"
        )
        File(voiceDir, "salma_lf.ini").writeText(
            "FILE = \"salma.dat\"\nBNF = \"salma.bnf\"\n"
        )
        File(voiceDir, "salma.dat").writeBytes(ByteArray(8192))
        File(voiceDir, "salma.bnf").writeBytes(ByteArray(8192))
        File(voiceDir, "0x12345678").writeText("DUMMY_LICENSE")
    }

    @After
    fun tearDown() {
        AcapelaVoiceRepository.resetForTests()
    }

    @Test
    fun getSnapshot_initialState_returnsSafeNonBlockingEmptySnapshot() {
        val snapshot = AcapelaVoiceRepository.getSnapshot()
        assertNotNull(snapshot)
        assertFalse(snapshot.isConfigured)
        assertEquals(0, snapshot.voices.size)
    }

    @Test
    fun getOrRefreshSnapshot_warmsSnapshotAndCachesResult() = runBlocking {
        val snap1 = AcapelaVoiceRepository.getOrRefreshSnapshot(context)
        assertNotNull(snap1)
        assertTrue(snap1.revision >= 0L)

        val snap2 = AcapelaVoiceRepository.getOrRefreshSnapshot(context)
        assertEquals(
            "اللقطة الدافئة يجب أن تعود من الذاكرة بنفس الـ timestamp",
            snap1.timestampMs,
            snap2.timestampMs
        )
    }

    @Test
    fun getOrRefreshSnapshot_coalescesConcurrentCalls() = runBlocking {
        val deferreds = (1..5).map {
            async(Dispatchers.IO) {
                AcapelaVoiceRepository.getOrRefreshSnapshot(context)
            }
        }
        val results = deferreds.awaitAll()
        val firstTimestamp = results.first().timestampMs
        for (res in results) {
            assertEquals(
                "الاستدعاءات المتزامنة يجب أن تدمج وتعيد نفس اللقطة",
                firstTimestamp,
                res.timestampMs
            )
        }
    }

    @Test
    fun invalidate_bumpsRevisionAndClearsSnapshot() = runBlocking {
        val snap1 = AcapelaVoiceRepository.getOrRefreshSnapshot(context)
        assertNotNull(snap1)

        AcapelaVoiceRepository.invalidate(context)
        val current = AcapelaVoiceRepository.getSnapshot()
        assertEquals(-1L, current.revision)
        assertEquals(0, current.voices.size)

        val snap2 = AcapelaVoiceRepository.getOrRefreshSnapshot(context)
        assertTrue(snap2.revision > snap1.revision)
    }

    @Test
    fun fingerprintOf_fastMode_doesNotReadFullHash() {
        val testFile = File(voiceDir, "large_voice.dat")
        testFile.writeBytes(ByteArray(64 * 1024))

        val fastFp = AcapelaVoiceDiagnostics.fingerprintOf(
            testFile,
            computeFullHash = false
        )
        assertTrue(fastFp.startsWith("size=65536,mod="))
        assertFalse(fastFp.contains("sha256="))

        val fullFp = AcapelaVoiceDiagnostics.fingerprintOf(
            testFile,
            computeFullHash = true
        )
        assertTrue(fullFp.contains("sha256="))
    }

    @Test
    fun verifyAuthenticVoicePackage_cachesVerificationInWarmPath() {
        val ver1 = AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Salma",
            computeFullHashes = false
        )
        assertNotNull(ver1)

        // استدعاء ثانٍ في المسار الدافئ
        val ver2 = AcapelaVoiceDiagnostics.verifyAuthenticVoicePackage(
            voiceDir = voiceDir,
            expectedSpeakerHint = "Salma",
            computeFullHashes = false
        )
        assertTrue(
            "التحقق الدافئ يجب أن يعود من الذاكرة المؤقتة",
            ver1 === ver2
        )
    }
}
