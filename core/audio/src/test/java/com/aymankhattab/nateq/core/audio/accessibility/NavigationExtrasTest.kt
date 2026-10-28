package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.audio.accessibility.ScreenGridAnnouncer.GridMode
import com.aymankhattab.nateq.core.audio.accessibility.ScreenRotor.RotorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationExtrasTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FocusMemory.clearForTesting()
        ScrollCuePlayer.resetLastPlayTimeForTesting()
        ScreenGridAnnouncer.resetLastZoneForTesting()
        LoadingDetector.resetForTesting()
        ScreenSearch.clearMemory()
        ArticleReader.stop()
    }

    // --- 1. Screen Rotor Tests ---

    @Test
    fun testScreenRotorClassifyButton() {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.Button"
            text = "موافق"
            isVisibleToUser = true
        }
        val item = ScreenRotor.classify(node)
        assertNotNull(item)
        assertEquals(RotorCategory.BUTTONS, item?.category)
        assertEquals("موافق", item?.text)
    }

    @Test
    fun testScreenRotorClassifyHeading() {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.TextView"
            text = "عنوان رئيسي"
            isHeading = true
            isVisibleToUser = true
        }
        val item = ScreenRotor.classify(node)
        assertNotNull(item)
        assertEquals(RotorCategory.HEADINGS, item?.category)
    }

    @Test
    fun testScreenRotorClassifyInputField() {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.EditText"
            text = "نص تجريبي"
            isEditable = true
            isVisibleToUser = true
        }
        val item = ScreenRotor.classify(node)
        assertNotNull(item)
        assertEquals(RotorCategory.INPUT_FIELDS, item?.category)
    }

    @Test
    fun testScreenRotorClassifyControl() {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.CheckBox"
            text = "خيار"
            isCheckable = true
            isVisibleToUser = true
        }
        val item = ScreenRotor.classify(node)
        assertNotNull(item)
        assertEquals(RotorCategory.CONTROLS, item?.category)
    }

    @Test
    fun testScreenRotorExcludesPassword() {
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.EditText"
            text = "كلمة_مرور_سرية"
            isPassword = true
            isVisibleToUser = true
        }
        val results = ScreenRotor.scan(node)
        for (list in results.values) {
            assertTrue("Password fields must never be scanned", list.isEmpty())
        }
    }

    // --- 2. Screen Search Tests ---

    @Test
    fun testScreenSearchNormalization() {
        val raw1 = "تَحْوِيلُ الأَمْوَالِ"
        val raw2 = "تحويل الاموال"
        assertEquals(
            ScreenSearch.normalize(raw1),
            ScreenSearch.normalize(raw2)
        )

        assertEquals("علي", ScreenSearch.normalize("عَلَى"))
        assertEquals("فاطمه", ScreenSearch.normalize("فَاطِمَةُ"))
        assertEquals("test", ScreenSearch.normalize("TEST"))
    }

    @Test
    fun testScreenSearchFindAndNavigate() {
        val root = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.LinearLayout"
            isVisibleToUser = true
        }
        val child = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.TextView"
            text = "تَحْوِيلُ المَبْلَغِ"
            isVisibleToUser = true
        }
        shadowOf(root).addChild(child)

        val count = ScreenSearch.search(context, root, "تحويل")
        assertEquals(1, count)
        assertTrue(ScreenSearch.hasResults())
        assertEquals("تحويل", ScreenSearch.getLastQuery())

        // التنقل الدوري
        assertTrue(ScreenSearch.navigateNext(context))
        assertTrue(ScreenSearch.navigatePrevious(context))
    }

    @Test
    fun testScreenSearchEmptyQuery() {
        val root = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
        }
        val count = ScreenSearch.search(context, root, "غير_موجود")
        assertEquals(0, count)
        assertFalse(ScreenSearch.hasResults())
    }

    // --- 3. Scroll Cue Player Tests ---

    @Test
    fun testScrollCueRatioCalculation() {
        // قائمة أكثر من 10 عناصر
        val ratio1 = ScrollCuePlayer.calculateRatio(
            fromIndex = 0,
            itemCount = 20
        )
        assertEquals(0.05f, ratio1, 0.01f)

        val ratioEnd = ScrollCuePlayer.calculateRatio(
            fromIndex = 19,
            itemCount = 20
        )
        assertEquals(1.0f, ratioEnd, 0.01f)

        // تحويل النسبة لفهرس نغمة
        assertEquals(0, ScrollCuePlayer.ratioToToneIndex(0.0f))
        assertEquals(11, ScrollCuePlayer.ratioToToneIndex(1.0f))
        assertEquals(5, ScrollCuePlayer.ratioToToneIndex(0.5f))
    }

    @Test
    fun testScrollCueRateLimiting() {
        val now = 1000L
        val played1 = ScrollCuePlayer.onScroll(
            ratio = 0.5f,
            isSpeaking = false,
            now = now
        )
        assertTrue(played1)

        // استدعاء فوري أقل من 150ms يجب كبحه
        val played2 = ScrollCuePlayer.onScroll(
            ratio = 0.6f,
            isSpeaking = false,
            now = now + 50L
        )
        assertFalse(played2)

        // بعد 160ms يسمح
        val played3 = ScrollCuePlayer.onScroll(
            ratio = 0.7f,
            isSpeaking = false,
            now = now + 160L
        )
        assertTrue(played3)
    }

    @Test
    fun testScrollCueSuppressedDuringSpeechExceptEdges() {
        val now = 1000L
        // في المنتصف أثناء الكلام: مكبوح
        val midPlayed = ScrollCuePlayer.onScroll(
            ratio = 0.5f,
            isSpeaking = true,
            now = now
        )
        assertFalse(midPlayed)

        // عند البداية (0%): مسموح
        val startPlayed = ScrollCuePlayer.onScroll(
            ratio = 0.0f,
            isSpeaking = true,
            now = now + 200L
        )
        assertTrue(startPlayed)

        // عند النهاية (100%): مسموح
        val endPlayed = ScrollCuePlayer.onScroll(
            ratio = 1.0f,
            isSpeaking = true,
            now = now + 400L
        )
        assertTrue(endPlayed)
    }

    // --- 4. Focus Memory Tests ---

    @Test
    fun testFocusMemoryRecordsAndExpires() {
        val node = AccessibilityNodeInfo.obtain().apply {
            viewIdResourceName = "com.test:id/btn_action"
            text = "زر رئيسي"
            isVisibleToUser = true
        }

        FocusMemory.onFocusEvent(
            packageName = "com.example.app",
            node = node,
            now = 1000L
        )

        // محاكاة انتهاء الصلاحية بعد 15 دقيقة
        val expiredTime = 1000L + (15 * 60 * 1000L)
        val root = AccessibilityNodeInfo.obtain()
        FocusMemory.onWindowStateChanged(
            context = context,
            packageName = "com.example.app",
            root = root,
            retentionMs = 10 * 60 * 1000L,
            now = expiredTime
        )

        // بعد الصلاحية تُمسح البصمة
        val fp = FocusMemory.getFingerprintForTesting("com.example.app")
        assertEquals(null, fp)
    }

    // --- 5. Screen Grid Announcer Tests ---

    @Test
    fun testScreenGridCalculationRtl() {
        val win = Rect(0, 0, 900, 1200)

        // أعلى اليمين في RTL (x في الثلث الأول، y في الثلث الأول)
        val elemTopRight = Rect(50, 50, 150, 150)
        val zone1 = ScreenGridAnnouncer.calculateZone(
            context = context,
            elementBounds = elemTopRight,
            windowBounds = win,
            isRtl = true
        )
        assertEquals(context.getString(R.string.grid_top_right), zone1)

        // المركز
        val elemCenter = Rect(400, 500, 500, 700)
        val zoneCenter = ScreenGridAnnouncer.calculateZone(
            context = context,
            elementBounds = elemCenter,
            windowBounds = win,
            isRtl = true
        )
        assertEquals(context.getString(R.string.grid_center), zoneCenter)
    }

    @Test
    fun testScreenGridOnChangeMode() {
        val zoneA = "أعلى اليمين"
        val zoneB = "أسفل اليسار"

        // أول نطق
        assertTrue(
            ScreenGridAnnouncer.shouldAnnounce(zoneA, GridMode.ON_CHANGE)
        )
        // نفس المنطقة: لا يُنطق
        assertFalse(
            ScreenGridAnnouncer.shouldAnnounce(zoneA, GridMode.ON_CHANGE)
        )
        // منطقة جديدة: يُنطق
        assertTrue(
            ScreenGridAnnouncer.shouldAnnounce(zoneB, GridMode.ON_CHANGE)
        )
    }

    // --- 6. Loading Detector Tests ---

    @Test
    fun testLoadingDetectorLifecycle() {
        val rootWithLoading = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.ProgressBar"
            isVisibleToUser = true
        }

        val now = 1000L
        LoadingDetector.onContentChanged(
            context = context,
            packageName = "com.example.app",
            root = rootWithLoading,
            now = now
        )
        assertTrue(LoadingDetector.isLoading())

        // بعد 2 ثانية، يختفي مؤشر التحميل
        val rootClean = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.TextView"
            text = "المحتوى جاهز"
            isVisibleToUser = true
        }

        LoadingDetector.onContentChanged(
            context = context,
            packageName = "com.example.app",
            root = rootClean,
            now = now + 2000L
        )
        assertFalse(LoadingDetector.isLoading())
    }

    // --- 7. Article Reader Tests ---

    @Test
    fun testArticleReaderFiltersAndSorts() {
        val root = AccessibilityNodeInfo.obtain().apply {
            isVisibleToUser = true
        }

        val shortText = AccessibilityNodeInfo.obtain().apply {
            text = "نص قصير"
            isVisibleToUser = true
        }
        val adText = AccessibilityNodeInfo.obtain().apply {
            text = "إعلان تجاري هام جداً لا تفوت الفرصة الآن واحصل على العرض"
            isVisibleToUser = true
        }
        val articleP1 = AccessibilityNodeInfo.obtain().apply {
            text = "هذا هو النص الرئيسي للمقال الإخباري الذي يتجاوز ثمانين " +
                "حرفاً لاختبار قدرة الخوارزمية على استخراج الفقرات بدقة تامة."
            isVisibleToUser = true
        }

        shadowOf(root).addChild(shortText)
        shadowOf(root).addChild(adText)
        shadowOf(root).addChild(articleP1)

        val paragraphs = ArticleReader.extractArticle(root, isRtl = true)
        assertEquals(1, paragraphs.size)
        assertTrue(paragraphs[0].text.startsWith("هذا هو النص الرئيسي"))
    }
}
