package com.aymankhattab.nateq.settings.banking

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات جامع حالة الجهاز على إصداراتٍ حقيقية متعدّدة.
 *
 * العقود المحرَسة بأسمائها:
 *  - **قراءة مصدر التثبيت على 24 (المسار الاحتياطي) و30 و33 (المسار
 *    الجديد)**: لو عاد `getInstallSourceInfo` بلا فرعٍ احتياطي فإسقاط
 *    `NoSuchMethodError` على 24 يهزّ الشاشة، ويعكس كل ما بعده.
 *  - **غياب أي إعداد لا يُسقط الشاشة**: جهازٌ بلا أي خدمة إمكانية وصول،
 *    بلا مستمع إشعارات، بلا VPN، بلا أدوات جذر — كل بندٍ يُعاد بقيمةٍ
 *    بديلة لا باستثناء.
 *  - **خدمة وهمية مُفعَّلة تُبلَّغ لا تُبتلع**: إضافة مكوِّن وهمي إلى
 *    `ENABLED_ACCESSIBILITY_SERVICES` تظهر في النتائج ولا تختفي بصمت
 *    (كتمُ البند أخطر من بندٍ بحالة «تعذّر»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 33])
class BankingCompatProbeTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun installSource_isReadableOnEverySupportedSdk() {
        val source = InstallSourceReader.read(
            context.packageManager,
            context.packageName
        )
        // المهم عدم السقوط، ثم يعمل التصنيف على القراءة الفعلية.
        val classified =
            BankingCompatClassifier.classifyInstallSource(
                source.installingPackageName
            )
        assertTrue(classified in InstallSourceClass.entries)
        if (source.installingPackageName == null) {
            assertEquals(
                InstallSourceClass.SIDE_LOADED, classified
            )
        }
    }

    @Test
    fun installSource_ofAnotherPackage_doesNotThrow() {
        val source = InstallSourceReader.read(
            context.packageManager, "com.android.vending"
        )
        assertTrue(
            source.installingPackageName == null ||
                source.installingPackageName.isNotBlank()
        )
    }

    @Test
    fun emptyDeviceSettings_produceEveryItemWithoutThrowing() {
        // حالتا «لا شيء مُفعَّل» — أسوأ ما قد يأتيه جهازٌ نظيف.
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ""
        )
        val items = BankingCompatProbe.probe(context)

        assertTrue(items.isNotEmpty())
        assertTrue(
            items.map { it.id }.contains(
                BankingCheckIds.ACCESSIBILITY_SERVICE
            )
        )
        assertTrue(
            items.map { it.id }.contains(
                BankingCheckIds.NOTIFICATION_LISTENER
            )
        )
        assertTrue(
            items.map { it.id }.contains(BankingCheckIds.OVERLAY)
        )
        assertTrue(
            items.map { it.id }.contains(BankingCheckIds.INSTALL_SOURCE)
        )
        for (item in items) {
            assertTrue("لا عنوان فارغ: ${item.id}", item.label.isNotBlank())
            assertTrue("لا حالة فارغة: ${item.id}", item.detail.isNotBlank())
        }
    }

    @Test
    fun missingSettingsKeys_doNotThrow_onAnySdk() {
        // لا نضبط المفاتيح إطلاقاً — تنفيذ وصلٍ كامل بلا استثناء.
        val state = BankingCompatProbe.lordCapabilityState(context)
        // ناطق في اختبارات الوحدة ليس خدمةً مُفعَّلة ولا مُستميلاً.
        assertFalse(state.accessibilityServiceEnabled)
        val items = BankingCompatProbe.probe(context)
        assertEquals(11, items.size)
    }

    @Test
    fun fakeAccessibilityService_isReportedNotSilentlySwallowed() {
        val fake = ComponentName(
            "com.example.fake", "com.example.fake.FakeService"
        )
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            fake.flattenToString()
        )

        val items = BankingCompatProbe.probe(context)
        val a11y = items.filter {
            it.id == BankingCheckIds.ACCESSIBILITY_SERVICE
        }

        assertEquals(
            "يجب أن تُبلَّغ الخدمة الوهمية لا أن تُبتلع",
            1, a11y.size
        )
        assertTrue(a11y[0].label.contains("FakeService"))
        // وسومُ الخدمة لا تُقرأ من مكوِّن غير مُصرَّح → حالة صريحة.
        val unknownFlags = context.getString(
            com.aymankhattab.nateq.feature.settings.R
                .string.banking_item_a11y_flags_unknown
        )
        assertTrue(a11y[0].detail.contains(unknownFlags))
        // ومكوِّنٌ غير حزمتنا لا يُعلَّم كخدمة ناطق.
        assertFalse(a11y[0].detail.contains(context.packageName))
    }

    @Test
    fun lordServiceIsNeverEnabledByALookalikeComponent() {
        val lookalike = ComponentName(
            "com.aymankhattab.nateq",
            "com.aymankhattab.nateq.core.audio.accessibility" +
                ".NotTheRealService"
        )
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            lookalike.flattenToString()
        )
        assertFalse(
            BankingCompatProbe.isLordAccessibilityServiceEnabled(context)
        )
    }

    @Test
    fun multipleEnabledServices_areEachReportedSeparately() {
        val first = ComponentName("com.a", "com.a.ServiceA")
        val second = ComponentName("com.b", "com.b.ServiceB")
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            first.flattenToString() + ":" + second.flattenToString()
        )

        val a11yItems = BankingCompatProbe.probe(context).filter {
            it.id == BankingCheckIds.ACCESSIBILITY_SERVICE
        }

        assertEquals(2, a11yItems.size)
        assertTrue(a11yItems[0].label.contains("ServiceA"))
        assertTrue(a11yItems[1].label.contains("ServiceB"))
    }

    @Test
    fun adbState_isReportedAsHarmlessRegardlessOfItsValue() {
        Settings.Secure.putInt(
            context.contentResolver, "adb_enabled", 1
        )
        val items = BankingCompatProbe.probe(context)
        val adb = items.firstOrNull {
            it.id == BankingCheckIds.ADB_ENABLED
        }
        assertEquals(BankingRisk.LOW, adb?.risk)
    }
}
