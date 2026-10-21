package com.aymankhattab.nateq.core.audio.feedback

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.ProtectedAppManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationVibrationTest {

    @Test
    fun notificationVibrationGate_isAlwaysFalse() {
        assertFalse(NotificationVibrationGate.allowed())
    }

    @Test
    fun sourceScan_vibrationOnlyInKeyboardAndDialpadTouch() {
        val candidates = listOf(
            File("."),
            File(".."),
            File("../..")
        )
        val root = candidates.firstOrNull {
            File(it, "settings.gradle.kts").exists()
        } ?: File(".")

        val allowedFileNames = setOf(
            "LordKeyboardService.kt",
            "LordKeyboardView.kt",
            "LordDialpadView.kt"
        )

        val vibrationTokens = listOf(
            "Vibrator",
            "VibratorManager",
            "vibrate(",
            "VibrationEffect"
        )

        val violations = mutableListOf<String>()

        root.walkTopDown()
            .filter { file ->
                file.isFile &&
                    (file.extension == "kt" || file.extension == "java") &&
                    file.invariantSeparatorsPath.contains("/src/main/java/")
            }
            .forEach { file ->
                val fileName = file.name
                if (fileName !in allowedFileNames) {
                    val text = file.readText()
                    for (token in vibrationTokens) {
                        if (text.contains(token)) {
                            violations.add(
                                "${file.path} contains token: $token"
                            )
                            break
                        }
                    }
                }
            }

        assertTrue(
            "No vibration calls allowed outside keyboard/dialpad: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun protectedAppNotificationChannel_vibrationDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nm = context.getSystemService(NotificationManager::class.java)

        // محاكاة وجود القناة القديمة للتحقق من حذفها
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val oldChannel = android.app.NotificationChannel(
                "protected_app_channel",
                "Old Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(oldChannel)
            assertTrue(
                nm.notificationChannels.any { it.id == "protected_app_channel" }
            )
        }

        // إطلاق إشعار إعادة التفعيل
        ProtectedAppManager.handleAppExit(
            context,
            "com.example.protected",
            "تطبيق محمي"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(
                ProtectedAppManager.CHANNEL_ID_PROTECTED
            )
            assertEquals("protected_app_channel_v2", channel.id)
            assertFalse(channel.shouldVibrate())
            assertNull(channel.vibrationPattern)

            // التأكد من حذف القناة القديمة
            assertNull(nm.getNotificationChannel("protected_app_channel"))
        }
    }
}
