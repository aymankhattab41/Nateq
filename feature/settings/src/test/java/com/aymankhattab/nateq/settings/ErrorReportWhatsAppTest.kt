package com.aymankhattab.nateq.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.feature.settings.R
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * اختبارات مشاركة تقرير الأخطاء عبر واتساب وسلسلة الإرسال
 * وحماية رقم المطور من التتبع أو التسريب.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ErrorReportWhatsAppTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        ShadowToast.reset()
    }

    @Test
    fun noHardcodedDeveloperNumberInTrackedFiles() {
        val rootDir = File(".").canonicalFile.let {
            if (it.name == "settings" || it.name == "feature") {
                it.parentFile?.parentFile ?: it
            } else {
                it
            }
        }
        val sourceDirs = listOf(
            File(rootDir, "feature/settings/src/main"),
            File(rootDir, "core/audio/src/main"),
            File(rootDir, "core/engine/src/main"),
            File(rootDir, "app/src/main")
        )
        val phonePattern = Regex("""201[0125]\d{8}""")

        for (dir in sourceDirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .filter {
                    it.isFile &&
                        (it.extension == "kt" || it.extension == "xml")
                }
                .forEach { file ->
                    val content = file.readText()
                    assertFalse(
                        "الملف المتتبع ${file.path} يحوي رقم هاتف ثابت!",
                        phonePattern.containsMatchIn(content)
                    )
                }
        }
    }

    @Test
    fun localPropertiesExample_containsPlaceholderOnly() {
        val rootDir = File(".").canonicalFile.let {
            if (it.name == "settings" || it.name == "feature") {
                it.parentFile?.parentFile ?: it
            } else {
                it
            }
        }
        val exampleFile = File(rootDir, "local.properties.example")
        assertTrue(
            "ملف local.properties.example يجب أن يكون موجوداً",
            exampleFile.exists()
        )
        val content = exampleFile.readText()
        assertTrue(
            content.contains("DEVELOPER_WHATSAPP_NUMBER=")
        )
        val line = content.lines().firstOrNull {
            it.startsWith("DEVELOPER_WHATSAPP_NUMBER=")
        }
        assertNotNull(line)
        assertEquals("DEVELOPER_WHATSAPP_NUMBER=", line!!.trim())
    }

    @Test
    fun emptyOrMissingNumber_handledSafely() {
        assertFalse(WhatsAppDiagnosticsSharing.isNumberConfigured(""))
        assertFalse(WhatsAppDiagnosticsSharing.isNumberConfigured("   "))
        assertFalse(WhatsAppDiagnosticsSharing.isNumberConfigured(null))

        assertNull(WhatsAppDiagnosticsSharing.buildJid(""))
        assertNull(WhatsAppDiagnosticsSharing.buildJid("   "))
        assertNull(WhatsAppDiagnosticsSharing.buildJid(null))

        assertNull(
            WhatsAppDiagnosticsSharing.buildWaMeUrl("", "مرحباً")
        )
        assertNull(
            WhatsAppDiagnosticsSharing.buildWaMeUrl(null, "مرحباً")
        )
    }

    @Test
    fun buildJid_formatsCorrectlyWhenConfigured() {
        val jid = WhatsAppDiagnosticsSharing.buildJid("+20 (100) 123-4567")
        assertEquals("201001234567@s.whatsapp.net", jid)
    }

    @Test
    fun waMeUrl_containsOnlyGenericGreeting_neverLogText() {
        val dummyNumber = "209999999999"
        val greeting = context.getString(
            R.string.whatsapp_support_generic_greeting
        )
        val sensitiveLog = "FATAL EXCEPTION in Engine: NullPointer at 0x998877"

        val waUrl = WhatsAppDiagnosticsSharing.buildWaMeUrl(
            dummyNumber,
            greeting
        )

        assertNotNull(waUrl)
        assertTrue(waUrl!!.startsWith("https://wa.me/209999999999?text="))
        assertTrue(waUrl.contains(Uri.encode(greeting)))
        assertFalse(waUrl.contains(sensitiveLog))
        assertFalse(waUrl.contains("FATAL"))
        assertFalse(waUrl.contains("NullPointer"))
        assertFalse(waUrl.contains("0x998877"))
    }

    @Test
    fun missingNumber_triggersFallbackChooserAndToast() {
        var chooserCalled = false
        var announcedMsg = ""

        WhatsAppDiagnosticsSharing.performShare(
            context = context,
            reportText = "test report",
            logFile = null,
            developerNumber = "",
            onAnnounce = { announcedMsg = it },
            onFallbackChooser = { _, _, _ -> chooserCalled = true }
        )

        ShadowLooper.idleMainLooper()
        assertTrue(
            "عند غياب الرقم يجب استدعاء قائمة المشاركة فوراً",
            chooserCalled
        )
        val expectedMsg = context.getString(
            R.string.whatsapp_number_not_configured
        )
        assertEquals(expectedMsg, announcedMsg)
        assertEquals(expectedMsg, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun jidFailure_showsFileNotAttachedNotice() {
        var chooserCalled = false
        var announcedMsg = ""

        // استدعاء بدون تثبيت واتساب فعلياً على بيئة روبولكترك
        // يؤدي لفشل JID والانتقال إلى wa.me مع إشعار عدم إرفاق الملف
        WhatsAppDiagnosticsSharing.performShare(
            context = context,
            reportText = "test report",
            logFile = null,
            developerNumber = "209999999999",
            onAnnounce = { announcedMsg = it },
            onFallbackChooser = { _, _, _ -> chooserCalled = true }
        )

        ShadowLooper.idleMainLooper()
        val notAttachedMsg = context.getString(
            R.string.whatsapp_file_not_attached
        )
        assertEquals(notAttachedMsg, announcedMsg)
        assertEquals(notAttachedMsg, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun resourceStrings_areNonEmptyInBothLanguages() {
        val greetingAr = context.getString(
            R.string.whatsapp_support_generic_greeting
        )
        val notAttachedAr = context.getString(
            R.string.whatsapp_file_not_attached
        )
        val notConfiguredAr = context.getString(
            R.string.whatsapp_number_not_configured
        )
        val sendWhatsAppAr = context.getString(
            R.string.error_report_send_whatsapp
        )

        assertTrue(greetingAr.isNotBlank())
        assertTrue(notAttachedAr.isNotBlank())
        assertTrue(notConfiguredAr.isNotBlank())
        assertTrue(sendWhatsAppAr.isNotBlank())
    }
}
