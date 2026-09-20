package com.aymankhattab.nateq

import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.StartupTempSweeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * اختبارات منظف الملفات المؤقتة اليتيمة عند الإقلاع (بند 19.2) عبر
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class StartupTempSweeperTest {

    private val context
        get() = ApplicationProvider
            .getApplicationContext<android.content.Context>()

    @Test
    fun orphanWavFilesInCache_areDeleted() {
        val cache = context.cacheDir
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        val wavA = File(cache, "nateq_tts_1.wav")
            .apply {
                writeBytes(ByteArray(64))
                setLastModified(cutoff - 60 * 60 * 1000L)
            }
        val wavB = File(cache, "nateq_tts_2.wav")
            .apply {
                writeBytes(ByteArray(64))
                setLastModified(cutoff - 3 * 60 * 60 * 1000L)
            }
        val kept = File(cache, "settings.dat")
            .apply { writeBytes(ByteArray(16)) }

        val deleted = StartupTempSweeper(context).sweep()

        assertTrue(deleted >= 2)
        assertFalse("الملف اليتيم A حُذف", wavA.exists())
        assertFalse("الملف اليتيم B حُذف", wavB.exists())
        assertTrue("الملف غير الـ wav بقي", kept.exists())
    }

    @Test
    fun freshWavInCache_isKept() {
        // ملف واف أحدث من حدّ العمر (30 دقيقة) قد يكون جلسة TTS تولّدها
        // الآن خدمة :tts في عملية منفصلة — كان يُحذف لحظياً فتنقطع بداية
        // الصوت (بند الصمت عند الإقلاع).
        val cache = context.cacheDir
        val fresh = File(cache, "nateq_tts_now.wav")
            .apply { writeBytes(ByteArray(64)) }
        assertTrue("المقدمة تجعل الملف حديثاً", fresh.lastModified() > 0)

        val deleted = StartupTempSweeper(context).sweep()

        assertEquals(0, deleted)
        assertTrue("الملف الحديث بقي", fresh.exists())
    }

    @Test
    fun emptyCache_sweepsNothing() {
        val deleted = StartupTempSweeper(context).sweep()
        assertEquals(0, deleted)
    }

    @Test
    fun activeApkInDownloads_isNeverDeleted() {
        val downloads = File(context.getExternalFilesDir(null), "downloads")
            .apply { mkdirs() }
        val apk = File(downloads, "nateq.apk")
            .apply { writeBytes(ByteArray(10_000)) }

        StartupTempSweeper(context).sweep()

        assertTrue("ملف الـ APK الحالي بقي", apk.exists())
    }

    @Test
    fun staleOldNamedApkInDownloads_isSwept() {
        // بند د.3.9: بقايا الاسم القديم (lord_tts.apk) ملف APK قديم باسمٍ
        // آخر فيُكنس عند القدم — لا يُعامل معاملة النشط (nateq.apk).
        val downloads = File(context.getExternalFilesDir(null), "downloads")
            .apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        val stale = File(downloads, "lord_tts.apk")
            .apply {
                writeBytes(ByteArray(10_000))
                setLastModified(cutoff - 60 * 60 * 1000L)
            }

        val deleted = StartupTempSweeper(context).sweep()

        assertTrue(deleted >= 1)
        assertFalse("الاسم القديم اليتيم كُنس", stale.exists())
    }

    @Test
    fun partialAndEmptyDownloads_areNotDeleted() {
        val downloads = File(context.getExternalFilesDir(null), "downloads")
            .apply { mkdirs() }
        val empty = File(downloads, "empty.bin")
            .apply { writeBytes(ByteArray(0)) }
        val partial = File(downloads, "update.tmp")
            .apply { writeBytes(ByteArray(100)) }

        StartupTempSweeper(context).sweep()

        assertTrue("الملف الفارغ (تنزيل قيد البدء) بقي", empty.exists())
        assertTrue("الملف الجزئي بقي", partial.exists())
    }
}
