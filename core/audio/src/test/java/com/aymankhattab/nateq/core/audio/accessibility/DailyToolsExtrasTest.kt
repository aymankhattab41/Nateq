package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.audio.announcement.BatteryThresholdMonitor
import com.aymankhattab.nateq.core.audio.engine.DynamicRatePolicy
import com.aymankhattab.nateq.core.audio.engine.SpeechRole
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.PronunciationDictionary
import java.util.Calendar
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DailyToolsExtrasTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = SettingsRepository.create(context)
        BatteryThresholdMonitor.resetForTesting()
        RoleEarcons.resetForTesting()
        TableReader.resetForTesting()
    }

    // --- 1. Battery Threshold Tests ---

    @Test
    fun testParseThresholds() {
        val defaultList = listOf(80)
        assertEquals(defaultList, BatteryThresholdMonitor.parseThresholds(
            null,
            defaultList
        ))
        assertEquals(defaultList, BatteryThresholdMonitor.parseThresholds(
            "",
            defaultList
        ))

        val parsed = BatteryThresholdMonitor.parseThresholds(
            "10, 20, 50, 80, 95, 99, 105, -5, abc",
            defaultList
        )
        assertEquals(6, parsed.size)
        assertEquals(listOf(10, 20, 50, 80, 95, 99), parsed)
    }

    @Test
    fun testChargingCrossingUpWithHysteresis() {
        repo.setExtraEnabled("extra_battery_thresholds", true)

        // الحالة الأولية: 75% شحن
        val intent75 = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 75)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        assertFalse(
            BatteryThresholdMonitor.onBatteryChanged(context, intent75, repo)
        )

        // عبور 80% صعوداً -> تفعيل
        val intent80 = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 80)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        assertTrue(
            BatteryThresholdMonitor.onBatteryChanged(context, intent80, repo)
        )

        // تكرار نفس النسبة -> لا يُعاد بسبب التخلف والتكرار
        assertFalse(
            BatteryThresholdMonitor.onBatteryChanged(context, intent80, repo)
        )

        // 81% -> لا يعيد التفعيل
        val intent81 = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 81)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_CHARGING
            )
        }
        assertFalse(
            BatteryThresholdMonitor.onBatteryChanged(context, intent81, repo)
        )
    }

    @Test
    fun testDischargingCrossingDown() {
        repo.setExtraEnabled("extra_battery_thresholds", true)

        // الحالة الأولية: 20% تفريغ
        val intent20 = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 20)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_DISCHARGING
            )
        }
        assertFalse(
            BatteryThresholdMonitor.onBatteryChanged(context, intent20, repo)
        )

        // هبوط إلى 15% -> تفعيل
        val intent15 = Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 15)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_DISCHARGING
            )
        }
        assertTrue(
            BatteryThresholdMonitor.onBatteryChanged(context, intent15, repo)
        )
    }

    @Test
    fun testQuietHoursBlocksBatteryThresholdAlert() {
        repo.setExtraEnabled("extra_battery_thresholds", true)
        val calendar = Calendar.getInstance()
        val day = calendar.get(Calendar.DAY_OF_WEEK)
        repo.setDayQuietEnabled(day, true)
        repo.setQuietStartForDay(day, 22)
        repo.setQuietEndForDay(day, 7)

        // وقت داخل ساعات الهدوء: 23:00
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        BatteryThresholdMonitor.testCalendarProvider = { calendar }
        assertTrue(BatteryThresholdMonitor.isInQuietHours(repo, calendar))

        // وقت خارج ساعات الهدوء: 14:00
        calendar.set(Calendar.HOUR_OF_DAY, 14)
        assertFalse(BatteryThresholdMonitor.isInQuietHours(repo, calendar))
    }

    // --- 2. Role Earcons Tests ---

    @Test
    fun testRoleDetection() {
        val buttonNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.Button"
        }
        assertEquals(
            RoleEarcons.ElementRole.BUTTON,
            RoleEarcons.determineRole(buttonNode)
        )

        val inputNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.EditText"
        }
        assertEquals(
            RoleEarcons.ElementRole.INPUT,
            RoleEarcons.determineRole(inputNode)
        )

        val switchNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.Switch"
        }
        assertEquals(
            RoleEarcons.ElementRole.CHECKBOX_SWITCH,
            RoleEarcons.determineRole(switchNode)
        )

        val imageNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.ImageView"
            isClickable = false
        }
        assertEquals(
            RoleEarcons.ElementRole.IMAGE,
            RoleEarcons.determineRole(imageNode)
        )

        val linkNode = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.TextView"
            text = "https://example.com"
            isClickable = true
        }
        assertEquals(
            RoleEarcons.ElementRole.LINK,
            RoleEarcons.determineRole(linkNode)
        )
    }

    @Test
    fun testRoleEarconsDuplicateSuppression() {
        repo.setExtraEnabled("extra_role_earcons", true)
        val node = AccessibilityNodeInfo.obtain().apply {
            className = "android.widget.Button"
            text = "موافق"
        }

        assertTrue(
            RoleEarcons.onNodeFocused(context, "com.example.app", node)
        )
        // تكرار التركيز على نفس العقدة
        assertFalse(
            RoleEarcons.onNodeFocused(context, "com.example.app", node)
        )
    }

    // --- 3. Quick Dictionary Tests ---

    @Test
    fun testQuickDictionaryAddAndReject() {
        val dict = PronunciationDictionary.shared(context)
        // رفض مفتاح فارغ
        assertFalse(dict.addEntry("", "نطق"))
        assertFalse(dict.addEntry("   ", "نطق"))

        // إضافة صالحة
        val added = dict.addEntry("كلمةجديدة", "كَلِمَة جَدِيدَة")
        assertTrue(added)

        // تطبيق النطق في القاموس
        val applied = dict.apply("كلمةجديدة في جملة")
        assertTrue(applied.contains("كَلِمَة جَدِيدَة"))
    }

    // --- 4. Dynamic Rate Policy Tests ---

    @Test
    fun testDynamicRateCalculation() {
        // نص قصير <= 80 حرفاً: المعامل 1.00
        assertEquals(
            1.00f,
            DynamicRatePolicy.calculateMultiplier(
                50,
                SpeechRole.TEXT,
                true
            ),
            0.001f
        )

        // نص 300 حرفاً: المعامل 1.08
        assertEquals(
            1.08f,
            DynamicRatePolicy.calculateMultiplier(
                300,
                SpeechRole.TEXT,
                true
            ),
            0.001f
        )

        // نص 800 حرفاً: المعامل 1.15
        assertEquals(
            1.15f,
            DynamicRatePolicy.calculateMultiplier(
                800,
                SpeechRole.TEXT,
                true
            ),
            0.001f
        )

        // نص طويل جداً: سقف 1.20 (+20%)
        assertEquals(
            1.20f,
            DynamicRatePolicy.calculateMultiplier(
                3000,
                SpeechRole.TEXT,
                true
            ),
            0.001f
        )

        // ميزة معطلة: يرجع 1.00 دائماً
        assertEquals(
            1.00f,
            DynamicRatePolicy.calculateMultiplier(
                500,
                SpeechRole.TEXT,
                false
            ),
            0.001f
        )

        // أرقام أو إيموجي: لا تتأثر
        assertEquals(
            1.00f,
            DynamicRatePolicy.calculateMultiplier(
                500,
                SpeechRole.NUMBER,
                true
            ),
            0.001f
        )
        assertEquals(
            1.00f,
            DynamicRatePolicy.calculateMultiplier(
                500,
                SpeechRole.EMOJI,
                true
            ),
            0.001f
        )
    }

    // --- 5. Table Reader Tests ---

    @Test
    fun testFindColumnHeaderAndFormat() {
        val root = AccessibilityNodeInfo.obtain()
        val shadowRoot = shadowOf(root)

        val headerCell = AccessibilityNodeInfo.obtain().apply {
            text = "الاسم"
            val itemInfo =
                AccessibilityNodeInfo.CollectionItemInfo.obtain(
                    0, 1, 0, 1, true
                )
            collectionItemInfo = itemInfo
        }
        shadowRoot.addChild(headerCell)

        val header = TableReader.findColumnHeader(root, 0)
        assertEquals("الاسم", header)

        val formatted = TableReader.formatCellAnnouncement(
            context = context,
            columnHeader = "الاسم",
            cellContent = "أحمد",
            rowIndex = 2,
            includeHeader = true
        )
        assertTrue(formatted.contains("الاسم"))
        assertTrue(formatted.contains("أحمد"))
        assertTrue(formatted.contains("3"))
    }
}
