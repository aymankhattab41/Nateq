package com.aymankhattab.nateq.core.audio.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
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
 * اختبارات تسجيل المكالمات الصوتية المحدثة وفق مواصفات الجولة 48.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CallRecordingRound48Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testRmsCalculation_zerosGiveZeroRms() {
        // مولّد عينات صفرية يُعطي RMS = 0
        val zeroBuffer = ShortArray(16000) { 0 }
        val rms = CallRecordManager.calculateRms(zeroBuffer, zeroBuffer.size)
        assertEquals(0.0, rms, 0.0001)
    }

    @Test
    fun testRmsCalculation_activeAudioGivesNonZeroRms() {
        // عينات صوت نشطة تعطي RMS > 0
        val activeBuffer = ShortArray(16000) { (it % 500).toShort() }
        val rms = CallRecordManager.calculateRms(
            activeBuffer,
            activeBuffer.size
        )
        assertTrue(rms > 10.0)
    }

    @Test
    fun testWavHeaderFormatAndLength() {
        val header = CallRecordManager.buildWavHeader(
            totalAudioLen = 32000L,
            sampleRate = 16000,
            channels = 1,
            bitsPerSample = 16
        )
        assertEquals(44, header.size)
        assertEquals('R'.code.toByte(), header[0])
        assertEquals('I'.code.toByte(), header[1])
        assertEquals('F'.code.toByte(), header[2])
        assertEquals('F'.code.toByte(), header[3])
        assertEquals('W'.code.toByte(), header[8])
        assertEquals('A'.code.toByte(), header[9])
        assertEquals('V'.code.toByte(), header[10])
        assertEquals('E'.code.toByte(), header[11])
    }

    @Test
    fun testWavHeaderPeriodicRewriteKeepsValidSize() {
        // كتابة ترويسة أولية ثم إضافة PCM ثم تحديث الترويسة وقراءتها
        val testFile = File(context.cacheDir, "test_record_integrity.wav")
        if (testFile.exists()) testFile.delete()

        val raf = RandomAccessFile(testFile, "rw")
        val initialHeader = CallRecordManager.buildWavHeader(0L, 16000, 1, 16)
        raf.write(initialHeader)

        // محاكاة كتابة 2 ثانية صوت (64000 بايت)
        val pcmData = ByteArray(64000) { 1 }
        raf.write(pcmData)

        // تحديث الترويسة في رأس الملف
        val updatedHeader = CallRecordManager.buildWavHeader(
            64000L,
            16000,
            1,
            16
        )
        raf.seek(0)
        raf.write(updatedHeader)
        raf.close()

        val readRaf = RandomAccessFile(testFile, "r")
        readRaf.seek(40)
        val b0 = readRaf.read().toLong()
        val b1 = readRaf.read().toLong()
        val b2 = readRaf.read().toLong()
        val b3 = readRaf.read().toLong()
        val parsedAudioLen = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        readRaf.close()

        assertEquals(64000L, parsedAudioLen)
        testFile.delete()
    }

    @Test
    fun testLegacyMigrationCopiesFiles() {
        val legacyDir = File(context.filesDir, "call_recordings")
        if (!legacyDir.exists()) legacyDir.mkdirs()

        val oldWav = File(legacyDir, "old_rec_123.wav")
        val header = CallRecordManager.buildWavHeader(3200L, 16000, 1, 16)
        oldWav.writeBytes(header)

        assertTrue(oldWav.exists())
        CallRecordManager.migrateLegacyRecordings(context)

        // يجب نقل الملف ومسح القديم
        assertFalse(oldWav.exists())
        val records = CallRecordManager.getRecordings(context)
        assertTrue(records.isNotEmpty())
    }

    @Test
    fun testFolderDisplayName() {
        val name = CallRecordManager.getRecordingFolderDisplayName()
        assertNotNull(name)
        assertTrue(name.contains("Lord TTS"))
    }
}
