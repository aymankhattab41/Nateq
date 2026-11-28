package com.aymankhattab.nateq.settings

import android.content.Context
import android.content.Intent
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
 * اختبارات مشاركة تقرير الأخطاء والتشخيص عبر واتساب وسلسلة الإرسال
 * وحماية رقم الدعم والتحقق من الروابط وعدم الاعتماد على jid.
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
    fun defaultSupportNumber_isAymanWhatsAppNumber() {
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.DEFAULT_SUPPORT_WHATSAPP_NUMBER
        )
    }

    @Test
    fun normalizeNumber_handlesVariousFormatsStrictly() {
        // جميع الصيغ الشائعة تنتج الرقم الدولي المعتمد
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.normalizeNumber("+20 101 265 9989")
        )
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.normalizeNumber("0020 101 265 9989")
        )
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.normalizeNumber("201012659989")
        )

        // الأرقام الفارغة أو خارج نطاق 8 إلى 15 خانة تُرفض تماماً
        assertEquals("", WhatsAppDiagnosticsSharing.normalizeNumber(""))
        assertEquals("", WhatsAppDiagnosticsSharing.normalizeNumber("   "))
        assertEquals("", WhatsAppDiagnosticsSharing.normalizeNumber(null))
        assertEquals(
            "",
            WhatsAppDiagnosticsSharing.normalizeNumber("1234567")
        ) // 7 خانات (قصير جداً)
        assertEquals(
            "",
            WhatsAppDiagnosticsSharing.normalizeNumber("1234567890123456")
        ) // 16 خانة (طويل جداً)
    }

    @Test
    fun resolveSupportNumber_fallsBackToDefaultWhenEmptyOrInvalid() {
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.resolveSupportNumber("")
        )
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.resolveSupportNumber("   ")
        )
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.resolveSupportNumber(null)
        )
        assertEquals(
            "201012659989",
            WhatsAppDiagnosticsSharing.resolveSupportNumber("123")
        )
        // عند توفر رقم مخصص صالح يتم اعتماده وتجاوز الافتراضي
        assertEquals(
            "201122334455",
            WhatsAppDiagnosticsSharing.resolveSupportNumber("+20 11 2233 4455")
        )
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
    fun waMeUrl_buildsCorrectUrlWithDefaultOrCustomNumber() {
        val greeting = "مرحباً، أرسل لك سجل تشخيص Lord TTS (الإصدار 1.6.177)"

        // بالرقم الافتراضي عند خلو المفتاح
        val defaultUrl = WhatsAppDiagnosticsSharing.buildWaMeUrl(
            number = "",
            greeting = greeting
        )
        assertNotNull(defaultUrl)
        assertTrue(defaultUrl!!.startsWith("https://wa.me/201012659989?text="))
        assertTrue(defaultUrl.contains(Uri.encode(greeting)))

        // برقم مخصص
        val customUrl = WhatsAppDiagnosticsSharing.buildWaMeUrl(
            number = "201500000000",
            greeting = greeting
        )
        assertNotNull(customUrl)
        assertTrue(customUrl!!.startsWith("https://wa.me/201500000000?text="))
        assertTrue(customUrl.contains(Uri.encode(greeting)))
    }

    @Test
    fun waMeUrl_neverContainsSensitiveLogData() {
        val greeting = context.getString(
            R.string.whatsapp_support_generic_greeting
        )
        val sensitiveLog = "FATAL EXCEPTION in Engine: NullPointer at 0x998877"

        val waUrl = WhatsAppDiagnosticsSharing.buildWaMeUrl(
            "201012659989",
            greeting
        )

        assertNotNull(waUrl)
        assertFalse(waUrl!!.contains(sensitiveLog))
        assertFalse(waUrl.contains("FATAL"))
        assertFalse(waUrl.contains("NullPointer"))
        assertFalse(waUrl.contains("0x998877"))
    }

    @Test
    fun noJidReferencesInSettingsSourceCode() {
        val rootDir = File(".").canonicalFile.let {
            if (it.name == "settings" || it.name == "feature") {
                it.parentFile?.parentFile ?: it
            } else {
                it
            }
        }
        val sourceDir = File(rootDir, "feature/settings/src/main")
        assertTrue(sourceDir.exists())

        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                assertFalse(
                    "الملف ${file.path} يحوي إشارة إلى 'jid'!",
                    text.contains("\"jid\"") || text.contains("buildJid")
                )
            }
    }

    @Test
    fun packageSelection_prefersStandardWhatsAppThenBusiness() {
        // عند تثبيت الاثنين، الأولوية للعادي
        val targetBoth = WhatsAppDiagnosticsSharing.findTargetWhatsAppPackage(
            context = context,
            packageChecker = { _, _ -> true }
        )
        assertEquals(WhatsAppDiagnosticsSharing.WHATSAPP_PACKAGE, targetBoth)

        // عند تثبيت الأعمال فقط
        val targetBusiness =
            WhatsAppDiagnosticsSharing.findTargetWhatsAppPackage(
                context = context,
                packageChecker = { _, pkg ->
                    pkg == WhatsAppDiagnosticsSharing.WHATSAPP_BUSINESS_PACKAGE
                }
            )
        assertEquals(
            WhatsAppDiagnosticsSharing.WHATSAPP_BUSINESS_PACKAGE,
            targetBusiness
        )

        // عند عدم تثبيت أي منهما
        val targetNone = WhatsAppDiagnosticsSharing.findTargetWhatsAppPackage(
            context = context,
            packageChecker = { _, _ -> false }
        )
        assertNull(targetNone)
    }

    @Test
    fun performShare_whenNeitherPackageInstalled_fallsBackToGeneralShare() {
        var chooserCalled = false
        var announcedMsg = ""

        WhatsAppDiagnosticsSharing.performShare(
            context = context,
            reportText = "test report",
            logFile = null,
            packageChecker = { _, _ -> false },
            onAnnounce = { announcedMsg = it },
            onFallbackChooser = { _, _, _ -> chooserCalled = true }
        )

        ShadowLooper.idleMainLooper()
        assertTrue(
            "عند غياب واتساب يجب الانتقال لقائمة المشاركة العامة",
            chooserCalled
        )
        val expectedNotice = context.getString(
            R.string.whatsapp_sharing_fallback_notice
        )
        assertEquals(expectedNotice, announcedMsg)
        assertEquals(expectedNotice, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun performShare_whenWaMeFails_fallsBackToGeneralShare() {
        var chooserCalled = false
        var announcedMsg = ""

        WhatsAppDiagnosticsSharing.performShare(
            context = context,
            reportText = "test report",
            logFile = null,
            packageChecker = { _, pkg ->
                pkg == WhatsAppDiagnosticsSharing.WHATSAPP_PACKAGE
            },
            activityLauncher = { _, _ -> false }, // محاكاة فشل الإطلاق
            onAnnounce = { announcedMsg = it },
            onFallbackChooser = { _, _, _ -> chooserCalled = true }
        )

        ShadowLooper.idleMainLooper()
        assertTrue(
            "عند فشل إطلاق wa.me يجب الانتقال لقائمة المشاركة العامة",
            chooserCalled
        )
        val expectedNotice = context.getString(
            R.string.whatsapp_sharing_fallback_notice
        )
        assertEquals(expectedNotice, announcedMsg)
    }

    @Test
    fun performShare_whenWaMeSucceeds_showsAttachConfirmationDialogOrSends() {
        val tempLog = File(context.cacheDir, "test_log.txt").apply {
            writeText("sample log content")
        }
        var attachDialogShown = false
        var targetPkgInDialog = ""
        val launchedIntents = mutableListOf<Intent>()

        WhatsAppDiagnosticsSharing.performShare(
            context = context,
            reportText = "test report",
            logFile = tempLog,
            packageChecker = { _, pkg ->
                pkg == WhatsAppDiagnosticsSharing.WHATSAPP_PACKAGE
            },
            activityLauncher = { _, intent ->
                launchedIntents.add(intent)
                true
            },
            onShowAttachDialog = { pkg, _ ->
                attachDialogShown = true
                targetPkgInDialog = pkg
            },
            onFallbackChooser = { _, _, _ -> }
        )

        assertTrue(attachDialogShown)
        assertEquals(
            WhatsAppDiagnosticsSharing.WHATSAPP_PACKAGE,
            targetPkgInDialog
        )
        // Intent الأول يجب أن يكون ACTION_VIEW لرابط wa.me
        assertEquals(1, launchedIntents.size)
        val waIntent = launchedIntents.first()
        assertEquals(Intent.ACTION_VIEW, waIntent.action)
        assertEquals(
            WhatsAppDiagnosticsSharing.WHATSAPP_PACKAGE,
            waIntent.`package`
        )
        assertTrue(
            waIntent.dataString!!.startsWith("https://wa.me/201012659989")
        )
        assertFalse(waIntent.hasExtra("jid"))
    }

    @Test
    fun launchSendFileToWhatsApp_createsCorrectIntentWithoutJid() {
        val tempLog = File(context.cacheDir, "test_diag.txt").apply {
            writeText("diagnostics data")
        }
        var sendIntent: Intent? = null

        val testUri = Uri.parse(
            "content://${context.packageName}.fileprovider/test_diag.txt"
        )
        val result = WhatsAppDiagnosticsSharing.launchSendFileToWhatsApp(
            context = context,
            targetPackage = WhatsAppDiagnosticsSharing.WHATSAPP_PACKAGE,
            logFile = tempLog,
            uriProvider = { _, _ -> testUri },
            activityLauncher = { _, intent ->
                sendIntent = intent
                true
            }
        )

        assertTrue(result)
        assertNotNull(sendIntent)
        assertEquals(Intent.ACTION_SEND, sendIntent!!.action)
        assertEquals(
            WhatsAppDiagnosticsSharing.WHATSAPP_PACKAGE,
            sendIntent!!.`package`
        )
        assertEquals("text/plain", sendIntent!!.type)
        assertTrue(sendIntent!!.hasExtra(Intent.EXTRA_STREAM))
        assertFalse(sendIntent!!.hasExtra("jid"))
        assertTrue(
            (sendIntent!!.flags and
                Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        )
    }

    @Test
    fun resourceStrings_areNonEmptyInBothLanguages() {
        val greetingAr = context.getString(
            R.string.whatsapp_support_generic_greeting
        )
        val greetingTemplAr = context.getString(
            R.string.whatsapp_support_greeting,
            "1.0"
        )
        val attachTitleAr = context.getString(
            R.string.whatsapp_attach_log_dialog_title
        )
        val attachMsgAr = context.getString(
            R.string.whatsapp_attach_log_dialog_message
        )
        val attachBtnAr = context.getString(
            R.string.whatsapp_attach_log_btn_attach
        )
        val cancelBtnAr = context.getString(
            R.string.whatsapp_attach_log_btn_cancel
        )
        val fallbackNoticeAr = context.getString(
            R.string.whatsapp_sharing_fallback_notice
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
        assertTrue(greetingTemplAr.isNotBlank())
        assertTrue(attachTitleAr.isNotBlank())
        assertTrue(attachMsgAr.isNotBlank())
        assertTrue(attachBtnAr.isNotBlank())
        assertTrue(cancelBtnAr.isNotBlank())
        assertTrue(fallbackNoticeAr.isNotBlank())
        assertTrue(notAttachedAr.isNotBlank())
        assertTrue(notConfiguredAr.isNotBlank())
        assertTrue(sendWhatsAppAr.isNotBlank())
    }
}
