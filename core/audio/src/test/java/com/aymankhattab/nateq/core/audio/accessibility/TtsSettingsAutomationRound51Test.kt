package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة شاملة للأتمتة الخفية المحدثة وتثبيت تبديل المحركات (الجولة 51).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TtsSettingsAutomationRound51Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testScreenIdentity_rejectsMainSettingsAndAcceptsTtsScreen() {
        // 1. شجرة تمثل شاشة الإعدادات الرئيسية
        val mainSettingsRoot = AccessibilityNodeInfo.obtain()
        mainSettingsRoot.className = "android.widget.FrameLayout"

        val netNode = AccessibilityNodeInfo.obtain()
        netNode.text = "الشبكة والإنترنت"
        shadowOf(mainSettingsRoot).addChild(netNode)

        val battNode = AccessibilityNodeInfo.obtain()
        battNode.text = "البطارية"
        shadowOf(mainSettingsRoot).addChild(battNode)

        val soundNode = AccessibilityNodeInfo.obtain()
        soundNode.text = "الصوت والاهتزاز"
        shadowOf(mainSettingsRoot).addChild(soundNode)

        // يجب رفض هذه الشاشة
        val isTts = TtsSettingsAutomation.isTtsSettingsScreen(
            context = context,
            root = mainSettingsRoot,
            settingsPkg = "com.android.settings"
        )
        assertFalse("يجب رفض شاشة الإعدادات الرئيسية", isTts)

        // 2. شجرة تمثل شاشة إعدادات النطق
        val ttsRoot = AccessibilityNodeInfo.obtain()
        ttsRoot.className = "android.widget.FrameLayout"

        val prefNode = AccessibilityNodeInfo.obtain()
        prefNode.text = "المحرك المفضل"
        shadowOf(ttsRoot).addChild(prefNode)

        val rateNode = AccessibilityNodeInfo.obtain()
        rateNode.text = "معدل سرعة الكلام"
        shadowOf(ttsRoot).addChild(rateNode)

        // يجب قبول شاشة إعدادات النطق
        val isRealTts = TtsSettingsAutomation.isTtsSettingsScreen(
            context = context,
            root = ttsRoot,
            settingsPkg = "com.android.settings"
        )
        assertTrue("يجب قبول شاشة إعدادات النطق الحقيقية", isRealTts)
    }

    @Test
    fun testIsTargetChecked_verifiesTargetRowAndRadio() {
        val row = AccessibilityNodeInfo.obtain()
        row.className = "android.widget.LinearLayout"

        val radio = AccessibilityNodeInfo.obtain()
        radio.className = "android.widget.RadioButton"
        radio.isCheckable = true
        radio.isChecked = true
        shadowOf(row).addChild(radio)

        val label = AccessibilityNodeInfo.obtain()
        label.className = "android.widget.TextView"
        label.text = "محرك ناطق Lord TTS"
        shadowOf(row).addChild(label)

        val candidates = listOf("Lord TTS", "محرك ناطق Lord TTS")
        assertTrue(
            "يجب التحقق من اختيار الراديو بنجاح",
            TtsSettingsAutomation.isTargetChecked(row, candidates)
        )

        radio.isChecked = false
        assertFalse(
            "عند عدم التحديد يجب أن يعيد false",
            TtsSettingsAutomation.isTargetChecked(row, candidates)
        )
    }

    @Test
    fun testFindScrollableContainer_locatesRecyclerViewWith12Engines() {
        val root = AccessibilityNodeInfo.obtain()
        root.className = "android.widget.FrameLayout"

        val recycler = AccessibilityNodeInfo.obtain()
        recycler.className = "androidx.recyclerview.widget.RecyclerView"
        recycler.isScrollable = true
        recycler.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        recycler.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        shadowOf(root).addChild(recycler)

        // إضافة 12 محركاً داخل القائمة
        for (i in 1..12) {
            val engineRow = AccessibilityNodeInfo.obtain()
            engineRow.text = "TTS Engine $i"
            shadowOf(recycler).addChild(engineRow)
        }

        val container = TtsSettingsAutomation.findScrollableContainer(root)
        assertNotNull(
            "يجب العثور على حاوية RecyclerView القابلة للتمرير",
            container
        )
        assertEquals(recycler, container)
    }

    @Test
    fun testEngineSwitchRecipe_saveReadAndFallback() {
        val repo = SettingsRepository.create(context)
        val model = "Pixel_8_Pro"
        val sdk = 35

        // في البداية لا توجد وصفة
        assertNull(repo.getEngineSwitchRecipe(model, sdk))

        // حفظ وصفة
        val recipe = TtsSettingsAutomation.EngineSwitchRecipe(
            routeAction = "com.android.settings.TTS_SETTINGS",
            locateMethod = "resource_title",
            listUiType = "page"
        )
        repo.setEngineSwitchRecipe(model, sdk, recipe.toJson())

        val readBack = repo.getEngineSwitchRecipe(model, sdk)
        assertNotNull(readBack)
        val parsed = TtsSettingsAutomation.EngineSwitchRecipe.fromJson(readBack)
        assertNotNull(parsed)
        assertEquals("com.android.settings.TTS_SETTINGS", parsed?.routeAction)
        assertEquals("resource_title", parsed?.locateMethod)
        assertEquals("page", parsed?.listUiType)

        // مسح الوصفة
        repo.setEngineSwitchRecipe(model, sdk, null)
        assertNull(repo.getEngineSwitchRecipe(model, sdk))
    }

    @Test
    fun testRecipeJsonSerialization() {
        val recipe = TtsSettingsAutomation.EngineSwitchRecipe(
            routeAction = "com.samsung.android.settings.TTS_SETTINGS",
            locateMethod = "current_engine_summary",
            listUiType = "dialog"
        )
        val json = recipe.toJson()
        val restored = TtsSettingsAutomation.EngineSwitchRecipe.fromJson(json)
        assertNotNull(restored)
        assertEquals(recipe.routeAction, restored?.routeAction)
        assertEquals(recipe.locateMethod, restored?.locateMethod)
        assertEquals(recipe.listUiType, restored?.listUiType)
    }

    @Test
    fun testResolveCandidateLabels_includesTargetAndInstalled() {
        val candidates = TtsSettingsAutomation.resolveTargetCandidateLabels(
            context = context,
            targetPackage = "com.google.android.tts"
        )
        assertTrue(candidates.contains("com.google.android.tts"))
    }
}
