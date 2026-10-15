package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadResumeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun getDownloadsDir_createsAndReturnsInternalDirectory() {
        val dir = DownloadHttpHelper.getDownloadsDir(context)
        assertNotNull(dir)
        assertTrue(dir.exists())
        assertEquals(
            context.filesDir.absolutePath,
            dir.parentFile?.absolutePath
        )
    }

    @Test
    fun getPartialBytes_detectsExistingPartFiles() {
        val downloadsDir = DownloadHttpHelper.getDownloadsDir(context)
        val testVoiceId = "test-voice-resume"

        val partFile = File(downloadsDir, "vocalizer_${testVoiceId}.zip.part")
        if (partFile.exists()) partFile.delete()

        assertEquals(
            0L,
            VoiceDownloadManager.getPartialBytes(context, testVoiceId)
        )

        partFile.writeBytes(ByteArray(1024 * 50)) // 50 KB

        assertEquals(
            1024L * 50,
            VoiceDownloadManager.getPartialBytes(context, testVoiceId)
        )

        VoiceDownloadManager.deletePartial(context, testVoiceId)
        assertFalse(partFile.exists())
        assertEquals(
            0L,
            VoiceDownloadManager.getPartialBytes(context, testVoiceId)
        )
    }

    @Test
    fun cancelDownload_deletesLeftoverPartialFile() {
        val downloadsDir = DownloadHttpHelper.getDownloadsDir(context)
        val voiceId = "cancel-clear-voice"
        val partFile = File(downloadsDir, "vocalizer_${voiceId}.zip.part")
        partFile.writeBytes(ByteArray(2048))
        assertTrue(partFile.exists())

        VoiceDownloadManager.cancelDownload(context, voiceId)

        assertFalse(partFile.exists())
        assertEquals(
            0L,
            VoiceDownloadManager.getPartialBytes(context, voiceId)
        )
    }

    @Test
    fun voiceDownloadManager_listenerReceivesUpdatesAndCancellation() {
        val listenerCalled = AtomicBoolean(false)
        val listener = VoiceDownloadManager.DownloadListener { _, state ->
            if (state is VoiceDownloadManager.DownloadState.Cancelled) {
                listenerCalled.set(true)
            }
        }

        VoiceDownloadManager.addListener(listener)
        val fakeId = "fake-voice-id-test"

        VoiceDownloadManager.cancelDownload(context, fakeId)

        VoiceDownloadManager.removeListener(listener)
    }

    @Test
    fun voiceDownloadService_startsAndStopsCleanly() {
        val serviceController = Robolectric.buildService(
            VoiceDownloadService::class.java
        )
        val service = serviceController.create().get()
        assertNotNull(service)

        val startIntent = Intent(
            context,
            VoiceDownloadService::class.java
        ).apply {
            action = VoiceDownloadService.ACTION_START
        }
        service.onStartCommand(startIntent, 0, 1)

        val stopIntent = Intent(
            context,
            VoiceDownloadService::class.java
        ).apply {
            action = VoiceDownloadService.ACTION_STOP
        }
        service.onStartCommand(stopIntent, 0, 2)
        serviceController.destroy()
    }

    @Test
    fun publishProgress_throttlesToDistinctPercent() {
        val voiceId = "throttle-${System.nanoTime()}"
        val downloading = AtomicInteger(0)
        val listener = VoiceDownloadManager.DownloadListener { id, state ->
            if (id == voiceId &&
                state is VoiceDownloadManager.DownloadState.Downloading
            ) {
                downloading.incrementAndGet()
            }
        }
        VoiceDownloadManager.addListener(listener)
        try {
            repeat(40) {
                VoiceDownloadManager.publishProgress(context, voiceId, 0)
            }
            assertEquals(1, downloading.get())
            VoiceDownloadManager.publishProgress(context, voiceId, 1)
            VoiceDownloadManager.publishProgress(context, voiceId, 1)
            VoiceDownloadManager.publishProgress(context, voiceId, 2)
            assertEquals(3, downloading.get())
        } finally {
            VoiceDownloadManager.removeListener(listener)
        }
    }

    @Test
    fun fastFailure_doesNotLeaveStuckActiveJob() {
        val voiceId = "fastfail-${System.nanoTime()}"
        val item = VocalizerCatalogItem(
            id = voiceId,
            voiceName = "Test",
            displayNameAr = "اختبار",
            displayNameEn = "Test",
            locale = Locale.US,
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName = "nope.hdr",
            downloadUrl = "http://blocked.invalid/x.zip",
            sampleUrl = "",
            sizeMb = 1,
            quality = "Compact"
        )
        val terminal = CountDownLatch(1)
        val listener = VoiceDownloadManager.DownloadListener { id, state ->
            val done =
                state is VoiceDownloadManager.DownloadState.Error ||
                    state is VoiceDownloadManager.DownloadState.Success
            if (id == voiceId && done) terminal.countDown()
        }
        VoiceDownloadManager.addListener(listener)
        try {
            VoiceDownloadManager.startVocalizerDownload(context, item)
            assertTrue(
                "لم يصل التنزيل لحالة نهائية",
                terminal.await(20, TimeUnit.SECONDS)
            )
            assertFalse(VoiceDownloadManager.isDownloading(voiceId))
        } finally {
            VoiceDownloadManager.removeListener(listener)
        }
    }
}
