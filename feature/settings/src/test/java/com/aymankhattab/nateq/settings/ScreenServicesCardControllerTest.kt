package com.aymankhattab.nateq.settings

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.accessibility.A11yStateStore
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class ScreenServicesCardControllerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var controller: ScreenServicesCardController

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository.create(context)
        controller = ScreenServicesCardController(context, settings)
    }

    @Test
    fun testInitialStatusText() {
        val statusText = controller.getServiceStatusText()
        assertNotNull(statusText)
        assertTrue(statusText.isNotBlank())
    }

    @Test
    fun testPauseAndResume() {
        val store = A11yStateStore.getInstance(context)
        assertNotNull(store)

        controller.resumeService()
        assertFalse(controller.isServicePaused())

        store?.setPaused(true, 0L, 1)
        assertTrue(controller.isServicePaused())

        controller.resumeService()
        assertFalse(controller.isServicePaused())
    }

    /**
     * حارس ضد التراجع: كان updateUiState() ينادي setup() فينادي setup()
     * بدوره updateUiState() — تكرار لا نهائي يُسقط الشاشة بـ
     * StackOverflowError. الاختبار يمرّ الآن فقط إن انكسرت الحلقة.
     * الجذر عمداً FrameLayout فارغ فكل العروض null (المسارات آمنة).
     */
    @Test
    fun setupThenUpdateUiState_doesNotRecurseIntoSetup() {
        val root = FrameLayout(context)
        controller.setup(root)
        controller.updateUiState()
    }

    /**
     * حارس بوابة اختصارات المفاتيح: مفتاح [switch_a11y_key_shortcuts] كان
     * غائباً عن التخطيط فتظل FEATURE_KEY_SHORTCUTS مطفأةً بلا وسيلة تفعيل
     * (المفاتيح الفيزيائية لا تعمل إطلاقاً). يفشل هذا إن غاب TAG عن التخطيط.
     */
    @Test
    fun keyShortcutsSwitch_isPresentInLayout() {
        val parser = context.resources.getXml(
            com.aymankhattab.nateq.feature.settings.R.layout
                .fragment_voice_selection
        )
        var found = false
        var event = parser.eventType
        while (event != android.content.res.XmlResourceParser.END_DOCUMENT) {
            if (event == android.content.res.XmlResourceParser.START_TAG) {
                for (i in 0 until parser.attributeCount) {
                    val isTag = parser.getAttributeName(i) == "tag"
                    val value = parser.getAttributeValue(i)
                    if (isTag && value == "switch_a11y_key_shortcuts") {
                        found = true
                    }
                }
            }
            event = parser.next()
        }
        assertTrue(
            "مفتاح switch_a11y_key_shortcuts غائب عن fragment_voice_selection",
            found
        )
    }
}
