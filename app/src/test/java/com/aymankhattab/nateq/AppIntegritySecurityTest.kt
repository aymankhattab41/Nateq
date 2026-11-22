package com.aymankhattab.nateq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * اختبارات أمان AppIntegrity ونطاق FileProvider:
 * 1. رفض توقيع PLACEHOLDER_SHA256 أو التوقيع الفارغ في checkSignature.
 * 2. تقييد نطاق مسارات FileProvider ومنع كشف جذر الكاش path=".".
 * 3. تشخيص Play Integrity كحالة غير حاجبة ولا تدعي تصديقاً دون خادم.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppIntegritySecurityTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun checkSignature_placeholderSignature_returnsFalse() {
        val originalExpected = AppIntegrity.expectedSignatureSha256
        try {
            AppIntegrity.expectedSignatureSha256 =
                AppIntegrity.PLACEHOLDER_SHA256
            assertFalse(AppIntegrity.checkSignature(context))

            AppIntegrity.expectedSignatureSha256 = ""
            assertFalse(AppIntegrity.checkSignature(context))

            AppIntegrity.expectedSignatureSha256 = "   "
            assertFalse(AppIntegrity.checkSignature(context))
        } finally {
            AppIntegrity.expectedSignatureSha256 = originalExpected
        }
    }

    @Test
    fun playIntegrityDiagnostic_hasTokenReceivedUnverified() {
        val diag =
            AppIntegrity.PlayIntegrityDiagnostic.TOKEN_RECEIVED_UNVERIFIED
        assertTrue(diag.labelAr.contains("بدون تصديق خادمي"))
    }

    @Test
    fun filePathsXml_noRootCacheExposure() {
        val filePathsFile = File(
            "src/main/res/xml/file_paths.xml"
        ).takeIf { it.exists() }
            ?: File("app/src/main/res/xml/file_paths.xml")

        assertTrue(
            "ملف file_paths.xml يجب أن يكون موجوداً",
            filePathsFile.exists()
        )

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(filePathsFile)
        val paths = doc.getElementsByTagName("paths").item(0) as Element

        val childNodes = paths.childNodes
        var hasDiagnosticsExport = false
        var hasRootPath = false

        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node is Element) {
                val pathAttr = node.getAttribute("path")
                if (pathAttr == "." || pathAttr == "./" || pathAttr.isEmpty()) {
                    hasRootPath = true
                }
                if (node.tagName == "cache-path" &&
                    node.getAttribute("name") == "diagnostics_export"
                ) {
                    hasDiagnosticsExport = true
                    assertEquals("diagnostics_export/", pathAttr)
                }
            }
        }

        assertFalse(
            "يحظر كلياً وجود جذر الكاش العام path=\".\" في file_paths.xml",
            hasRootPath
        )
        assertTrue(
            "يجب وجود مجلد diagnostics_export المخصص في file_paths.xml",
            hasDiagnosticsExport
        )
    }
}
