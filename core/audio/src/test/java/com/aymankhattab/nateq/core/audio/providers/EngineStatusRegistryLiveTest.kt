package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * اختبار الاشتقاق الحي لحالات المحركات وتجاهل حالات القرص القديمة
 * (ع٣١-4 + ع٣١-7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineStatusRegistryLiveTest {

    private lateinit var context: Context
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        filesDir = context.filesDir
        EngineStatusRegistry.setFilesDir(filesDir)
        EngineStatusRegistry.clear()
        EngineStatusRegistry.nowProvider = { 1_000L }
        EmbeddedStorage.resetBumpTimeForTest(0L)
        cleanupAllDirs()
    }

    @After
    fun tearDown() {
        EngineStatusRegistry.clear()
        EngineStatusRegistry.nowProvider = { System.currentTimeMillis() }
        EmbeddedStorage.resetBumpTimeForTest(0L)
        cleanupAllDirs()
    }

    private fun cleanupAllDirs() {
        EmbeddedStorage.Engine.entries.forEach { engine ->
            val dir = EmbeddedStorage.baseDir(context, engine)
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    @Test
    fun staleVoicesPresentOnDisk_isOverriddenByLiveEmptyInventory() {
        // 1. كتابة حالة VOICES_PRESENT في ملف القرص
        val staleDiskStatus = EngineStatusRegistry.EngineStatus(
            engineId = "vocalizer",
            state = EngineStatusRegistry.State.VOICES_PRESENT,
            updatedAt = 5_000L
        )
        EngineStatusRegistry.writeStatusFile(filesDir, staleDiskStatus)

        // إفراغ الكاش في الذاكرة لمحاكاة إقلاع جديد
        EngineStatusRegistry.statusesClearOnly()

        // 2. التحقق من أن القرص خالٍ من ملفات Vocalizer
        val inv = EmbeddedStorage.inventory(
            context,
            EmbeddedStorage.Engine.VOCALIZER
        )
        assertFalse(inv.isCompleteVoicePresent)

        // 3. عند قراءة الحالة عبر statusOf(context, ...)، يُشتق الجرد الحي
        // ولا تُعرض VOICES_PRESENT بل NOT_INSTALLED
        val status = EngineStatusRegistry.statusOf(context, "vocalizer")
        assertEquals(
            "حالة القرص القديمة VOICES_PRESENT لا تُعرض وتُستبدل بـ " +
                "NOT_INSTALLED",
            EngineStatusRegistry.State.NOT_INSTALLED,
            status.state
        )
    }
}
