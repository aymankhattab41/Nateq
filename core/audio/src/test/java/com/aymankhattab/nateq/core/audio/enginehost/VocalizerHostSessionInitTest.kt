package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات تهيئة [VocalizerHostSession] ورسائل الفشل
 * الصريحة (ع٣٠-3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VocalizerHostSessionInitTest {

    private lateinit var context: Context
    private lateinit var session: VocalizerHostSession

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        session = VocalizerHostSession(context)
    }

    @Test
    fun init_withBlankDataDir_returnsNoDataDirFailure() {
        val res = session.init("   ")
        assertFalse(res.ok)
        assertEquals("NO_DATA_DIR", res.code)
    }

    @Test
    fun init_whenNativeEngineNotCreated_returnsNativeCreateFailed() {
        // في Robolectric لا تُحمّل المكتبة فيرمي
        // createNativeEngine خطأ أو يبقى المؤشر 0.
        val res = session.init("/dummy/dir")
        assertFalse(res.ok)
        assertEquals("NATIVE_CREATE_FAILED", res.code)
        val msg = res.message ?: ""
        assertTrue(
            "رسالة الخطأ يجب أن توضح سبب الفشل: $msg",
            msg.contains("UnsatisfiedLinkError") ||
                msg.contains("mNativeEnginePtr")
        )
    }

    @Test
    fun getCandidatePaths_ordersDirSlashAndCanonicalCorrectly() {
        val testDir = context.filesDir.resolve("VocalizerEx2")
        testDir.mkdirs()

        val paths = VocalizerHostSession.getCandidatePaths(testDir.path)
        assertTrue(paths.isNotEmpty())
        assertEquals(testDir.path, paths[0])
        val expectedSlash = if (testDir.path.endsWith("/")) {
            testDir.path
        } else {
            "${testDir.path}/"
        }
        assertTrue(paths.contains(expectedSlash))
        assertTrue(paths.contains(testDir.canonicalPath))
    }
}
