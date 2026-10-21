package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.regex.Pattern

/**
 * اختبارات منع استهلاك البطارية في الخدمات الأمامية (الجولة 50 - الأمر 2).
 * يفحص فترات الاستطلاع آلياً، ويتحقق من إيقاف الاستطلاع عند إطفاء الشاشة.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ForegroundServicePollingTest {

    @Test
    fun testNoForegroundServiceHasSubSecondPollingLoop() {
        val rootDir = File(".").canonicalFile
        val foregroundServiceFiles = listOf(
            "ProtectedAppWatcherService.kt",
            "AnnouncementSchedulerService.kt",
            "CallRecordingForegroundService.kt",
            "VoiceDownloadService.kt"
        )

        val delayPattern = Pattern.compile("""delay\(\s*(\d+)L?\s*\)""")
        val sleepPattern = Pattern.compile("""Thread\.sleep\(\s*(\d+)L?\s*\)""")

        var inspectedCount = 0
        rootDir.walkTopDown().forEach { file ->
            if (file.name in foregroundServiceFiles &&
                file.extension == "kt" &&
                !file.path.contains("build") &&
                !file.path.contains("test")
            ) {
                inspectedCount++
                val text = file.readText()

                // يفحص كل تأخير داخل حلقة أو متكرر
                val delayMatcher = delayPattern.matcher(text)
                while (delayMatcher.find()) {
                    val delayVal = delayMatcher.group(1)?.toLongOrNull() ?: 0L
                    assertTrue(
                        "File ${file.name} delay($delayVal ms) < 1000ms",
                        delayVal >= 1000L || delayVal == 0L
                    )
                }

                val sleepMatcher = sleepPattern.matcher(text)
                while (sleepMatcher.find()) {
                    val sleepVal = sleepMatcher.group(1)?.toLongOrNull() ?: 0L
                    assertTrue(
                        "File ${file.name} sleep($sleepVal ms) < 1000ms",
                        sleepVal >= 1000L || sleepVal == 0L
                    )
                }
            }
        }

        assertTrue(
            "Must inspect at least 4 foreground service files",
            inspectedCount >= 4
        )
    }

    @Test
    fun testProtectedAppWatcherServicePausesOnScreenOff() {
        val controller = Robolectric.buildService(
            ProtectedAppWatcherService::class.java
        ).create()
        val service = controller.get()

        assertTrue(
            "Initially screen should be ON",
            service.isScreenOnForTesting()
        )

        val receiver = service.getScreenReceiverForTesting()
        assertNotNull("Screen receiver must be registered", receiver)

        val context = ApplicationProvider.getApplicationContext<Context>()

        // إطفاء الشاشة: يوقف الاستطلاع
        receiver?.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        assertFalse(
            "Screen OFF must pause polling in ProtectedAppWatcherService",
            service.isScreenOnForTesting()
        )

        // إضاءة الشاشة: يستأنف الاستطلاع
        receiver?.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertTrue(
            "Screen ON must resume polling in ProtectedAppWatcherService",
            service.isScreenOnForTesting()
        )

        // فتح القفل: يستأنف الاستطلاع
        receiver?.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        assertFalse(service.isScreenOnForTesting())
        receiver?.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
        assertTrue(
            "ACTION_USER_PRESENT must resume polling",
            service.isScreenOnForTesting()
        )

        controller.destroy()
    }
}
