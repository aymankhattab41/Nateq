package com.aymankhattab.nateq.settings.banking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * حارسُ تصنيفِ بنود فحص توافق التطبيقات المصرفية.
 *
 * الأسماء تحمل العقدَ الذي يحرسونه — لا الحالة الطبيعية فقط:
 *  - **ADB ليس خطراً**: رُصد أن إنستاباي يعمل مع تفعيل خيارات المطوّر
 *    وتصحيح USB واللاسلكي؛ فإن عاد ADB ليُصنَّف خطراً فشل الاختبار
 *    ويُضلِّل المستخدم في عزل السبب.
 *  - **قارئ الشاشة ليس خطراً**: رُصد أن إنستاباي يعمل مع قارئ شاشةٍ
 *    مُفعَّل وحده، فأي إعادة تصنيف لكل خدمة إمكانية وصول خطراً = فشل.
 *  - **لا حزمٍ مُستثنى**: لا اسماً خاصاً بقارئ شاشةٍ ولا بناطق.
 */
class BankingCompatClassifierTest {

    @Test
    fun adbItems_areNeverClassifiedAsBlocking_theirWorksWithInstaPay() {
        assertEquals(
            BankingRisk.LOW,
            BankingCompatClassifier.riskFor(BankingCheckIds.ADB_ENABLED)
        )
        assertEquals(
            BankingRisk.LOW,
            BankingCompatClassifier.riskFor(BankingCheckIds.ADB_WIFI)
        )
    }

    @Test
    fun accessibilityTool_isNotBlocking_butNonToolIs() {
        assertEquals(
            BankingRisk.LOW,
            BankingCompatClassifier.accessibilityServiceRisk(true)
        )
        assertEquals(
            BankingRisk.HIGH,
            BankingCompatClassifier.accessibilityServiceRisk(false)
        )
    }

    @Test
    fun topSuspects_areClassifiedBlocking() {
        val suspects = listOf(
            BankingCheckIds.NOTIFICATION_LISTENER,
            BankingCheckIds.OVERLAY,
            BankingCheckIds.VPN,
            BankingCheckIds.SU_BINARY,
            BankingCheckIds.SHIZUKU
        )
        for (id in suspects) {
            assertEquals(
                "البند $id يجب أن يكون خطراً",
                BankingRisk.HIGH,
                BankingCompatClassifier.riskFor(id)
            )
        }
    }

    @Test
    fun informationalItems_areClassifiedHarmless() {
        val harmless = listOf(
            BankingCheckIds.DEVICE_ADMIN,
            BankingCheckIds.DIALER_ROLE
        )
        for (id in harmless) {
            assertEquals(
                "البند $id يجب أن يكون غير مؤثر",
                BankingRisk.LOW,
                BankingCompatClassifier.riskFor(id)
            )
        }
    }

    @Test
    fun fullRiskTable_isExactlyWhatWeProveNotMore() {
        val table = mapOf(
            BankingCheckIds.ACCESSIBILITY_SERVICE to BankingRisk.LOW,
            BankingCheckIds.ADB_ENABLED to BankingRisk.LOW,
            BankingCheckIds.ADB_WIFI to BankingRisk.LOW,
            BankingCheckIds.DEVICE_ADMIN to BankingRisk.LOW,
            BankingCheckIds.DIALER_ROLE to BankingRisk.LOW,
            BankingCheckIds.INSTALL_SOURCE to BankingRisk.LOW,
            BankingCheckIds.NOTIFICATION_LISTENER to BankingRisk.HIGH,
            BankingCheckIds.OVERLAY to BankingRisk.HIGH,
            BankingCheckIds.VPN to BankingRisk.HIGH,
            BankingCheckIds.SU_BINARY to BankingRisk.HIGH,
            BankingCheckIds.SHIZUKU to BankingRisk.HIGH
        )
        for ((id, expected) in table) {
            assertEquals(
                "الجدول لمُحمَّل: $id",
                expected,
                BankingCompatClassifier.riskFor(id)
            )
        }
    }

    @Test
    fun unknownInstallSource_isTreatedAsOutsideAnyStore() {
        assertEquals(
            InstallSourceClass.SIDE_LOADED,
            BankingCompatClassifier.classifyInstallSource(null)
        )
        assertEquals(
            InstallSourceClass.SIDE_LOADED,
            BankingCompatClassifier.classifyInstallSource("")
        )
        assertEquals(
            InstallSourceClass.SIDE_LOADED,
            BankingCompatClassifier.classifyInstallSource("   ")
        )
    }

    @Test
    fun playStoreInstaller_isTheOnlyTrustedSource() {
        assertEquals(
            InstallSourceClass.PLAY_STORE,
            BankingCompatClassifier.classifyInstallSource(
                "com.android.vending"
            )
        )
        assertEquals(
            InstallSourceClass.SIDE_LOADED,
            BankingCompatClassifier.classifyInstallSource(
                "com.other.installer"
            )
        )
    }
}
