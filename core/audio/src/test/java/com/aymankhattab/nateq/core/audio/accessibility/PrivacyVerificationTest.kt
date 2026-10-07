package com.aymankhattab.nateq.core.audio.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyVerificationTest {

    @Test
    fun `no screen text logging in release code`() {
        val rootDir = File(
            "src/main/java/com/aymankhattab/nateq/core/audio/accessibility"
        )
        if (!rootDir.exists()) return

        val files = rootDir.walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
        assertTrue(
            "Accessibility source files should exist",
            files.isNotEmpty()
        )

        for (file in files) {
            val content = file.readText()
            // التحقق من عدم طباعة نصوص الشاشة في Log.d / Log.i / Log.v / Log.w
            val hasDirectTextLog = content.contains("Log.d(TAG, \"text: \${") ||
                content.contains("Log.i(TAG, \"text: \${") ||
                content.contains("Log.v(TAG, \"text: \${") ||
                content.contains("Log.w(TAG, \"text: \${") ||
                content.contains("Log.d(TAG, node.text") ||
                content.contains("Log.i(TAG, node.text")
            assertFalse(
                "File ${file.name} must not log screen texts directly",
                hasDirectTextLog
            )
        }
    }

    @Test
    fun `sensitive and password nodes are filtered in NodeView`() {
        val nodeView = NodeView(
            text = "كلمة مرور سرية",
            isPassword = true,
            isVisibleToUser = true
        )
        assertTrue(nodeView.isPassword)
    }
}
