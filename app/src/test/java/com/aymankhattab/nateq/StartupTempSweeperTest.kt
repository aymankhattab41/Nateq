package com.aymankhattab.nateq

import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.util.StartupTempSweeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** اختبارات منظف الملفات المؤقتة اليتيمة عند الإقلاع (بند 19.2) عبر Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupTempSweeperTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun orphanWavFilesInCache_areDeleted() {
        val cache = context.cacheDir
        val wavA = File(cache, "nateq_tts_1.wav").apply { writeBytes(ByteArray(64)) }
        val wavB = File(cache, "nateq_tts_2.wav").apply { writeBytes(ByteArray(64)) }
        val kept = File(cache, "settings.dat").apply { writeBytes(ByteArray(16)) }

        val deleted = StartupTempSweeper(context).sweep()

        assertTrue(deleted >= 2)
        assertFalse("الملف اليتيم A حُذف", wavA.exists())
        assertFalse("الملف اليتيم B حُذف", wavB.exists())
        assertTrue("الملف غير الـ wav بقي", kept.exists())
    }

    @Test
    fun emptyCache_sweepsNothing() {
        val deleted = StartupTempSweeper(context).sweep()
        assertEquals(0, deleted)
    }

    @Test
    fun activeApkInDownloads_isNeverDeleted() {
        val downloads = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val apk = File(downloads, "lord_tts.apk").apply { writeBytes(ByteArray(10_000)) }

        StartupTempSweeper(context).sweep()

        assertTrue("ملف الـ APK الحالي بقي", apk.exists())
    }

    @Test
    fun partialAndEmptyDownloads_areNotDeleted() {
        val downloads = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val empty = File(downloads, "empty.bin").apply { writeBytes(ByteArray(0)) }
        val partial = File(downloads, "update.tmp").apply { writeBytes(ByteArray(100)) }

        StartupTempSweeper(context).sweep()

        assertTrue("الملف الفارغ (تنزيل قيد البدء) بقي", empty.exists())
        assertTrue("الملف الجزئي بقي", partial.exists())
    }
}
