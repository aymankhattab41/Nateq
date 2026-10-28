package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.audio.accessibility.LinkInspector.LinkWarning
import com.aymankhattab.nateq.core.data.SettingsRepository
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyExtrasTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = SettingsRepository.create(context)
        PrivacyIndicatorMonitor.resetForTesting()
        ScreenCurtain.resetForTesting()
    }

    // --- 1. Password Shield Tests ---

    @Test
    fun testPasswordShieldMuteWhenEnabled() {
        repo.setExtraEnabled("extra_password_shield", true)
        assertTrue(
            PasswordShield.shouldMuteTypingEcho(context, isPassword = true)
        )
        assertFalse(
            PasswordShield.shouldMuteTypingEcho(context, isPassword = false)
        )

        repo.setExtraEnabled("extra_password_shield", false)
        assertFalse(
            PasswordShield.shouldMuteTypingEcho(context, isPassword = true)
        )
    }

    @Test
    fun testPasswordShieldAnnounceCount() {
        val nodeWithText = AccessibilityNodeInfo.obtain().apply {
            text = "secret123"
            isPassword = true
        }
        assertTrue(
            PasswordShield.announceCharacterCount(context, nodeWithText)
        )

        val emptyNode = AccessibilityNodeInfo.obtain().apply {
            text = ""
            isPassword = true
        }
        assertTrue(
            PasswordShield.announceCharacterCount(context, emptyNode)
        )

        assertFalse(
            PasswordShield.announceCharacterCount(context, null)
        )
    }

    // --- 2. Link Inspector Tests ---

    @Test
    fun testLinkInspectorExtractHost() {
        assertEquals(
            "example.com",
            LinkInspector.extractHost("https://example.com/login")
        )
        assertEquals(
            "sub.domain.org",
            LinkInspector.extractHost("http://sub.domain.org:8080/path?q=1")
        )
        assertEquals(
            "www.site.net",
            LinkInspector.extractHost("www.site.net")
        )
    }

    @Test
    fun testLinkInspectorShortenerWarning() {
        val res = LinkInspector.inspect("https://bit.ly/secure-login")
        assertNotNull(res)
        assertEquals("bit.ly", res!!.host)
        assertEquals(LinkWarning.SHORTENED, res.warning)
    }

    @Test
    fun testLinkInspectorNumericIpWarning() {
        val res = LinkInspector.inspect("http://192.168.1.1/admin")
        assertNotNull(res)
        assertEquals("192.168.1.1", res!!.host)
        assertEquals(LinkWarning.NUMERIC_IP, res.warning)
    }

    @Test
    fun testLinkInspectorPunycodeWarning() {
        val res = LinkInspector.inspect("https://xn--pple-43d.com")
        assertNotNull(res)
        assertEquals(LinkWarning.SUSPICIOUS_PUNYCODE, res!!.warning)
    }

    @Test
    fun testLinkInspectorTextMismatchWarning() {
        val res = LinkInspector.inspect(
            linkUrl = "https://phishing-site.xyz/login",
            displayText = "انقر هنا لتسجيل الدخول إلى https://paypal.com بأمان"
        )
        assertNotNull(res)
        assertEquals(LinkWarning.TEXT_MISMATCH, res!!.warning)
    }

    @Test
    fun testLinkInspectorNormalLink() {
        val res = LinkInspector.inspect("https://google.com/search")
        assertNotNull(res)
        assertEquals("google.com", res!!.host)
        assertEquals(LinkWarning.NONE, res.warning)
    }

    // --- 3. Screen Curtain Tests ---

    @Test
    fun testScreenCurtainInitialState() {
        assertFalse(ScreenCurtain.isActive())
    }
}
