package com.aymankhattab.nateq.core.audio.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.ProtectedApp
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
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

/**
 * اختبارات التطبيقات المحمية وإنستاباي (الجولة 48 - الأمر 7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ProtectedAppRound48Test {

    private val context =
        ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsRepository.create(context)

    @Before
    fun setUp() {
        ProtectedAppManager.resetForTesting()
        settings.setProtectedApps(emptyList())
        settings.setSuspendedProtectedApp(null)
    }

    @After
    fun tearDown() {
        ProtectedAppManager.resetForTesting()
        settings.setProtectedApps(emptyList())
        settings.setSuspendedProtectedApp(null)
    }

    @Test
    fun matchProtectedApp_matchesInstaPayByNameOrPackage() {
        settings.addProtectedApp(
            ProtectedApp(
                packageName = "com.instapay",
                label = "إنستاباي",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )

        val match1 = ProtectedAppManager.matchProtectedApp(context, "إنستاباي")
        assertNotNull(match1)
        assertEquals("com.instapay", match1!!.packageName)

        val match2 = ProtectedAppManager.matchProtectedApp(context, "انستاباي")
        assertNotNull(match2)
        assertEquals("com.instapay", match2!!.packageName)

        val match3 =
            ProtectedAppManager.matchProtectedApp(context, "com.instapay")
        assertNotNull(match3)
        assertEquals("com.instapay", match3!!.packageName)
    }

    @Test
    fun matchProtectedApp_ignoresZwnjInLauncherLabel() {
        // نقرة أيقونة إنستاباي في مشغّل سامسونج تحمل اسم «Insta‌Pay»
        // بمحرف ZWNJ غير مرئي، فلا تُطابَق «InstaPay» بلا تطبيعه.
        settings.addProtectedApp(
            ProtectedApp(
                packageName = "com.egyptianbanks.instapay",
                label = "InstaPay",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )

        val matched = ProtectedAppManager.matchProtectedApp(
            context,
            "Insta\u200CPay"
        )
        assertNotNull(matched)
        assertEquals(
            "com.egyptianbanks.instapay",
            matched?.packageName
        )
    }

    @Test
    fun nodeLabel_readsTextFromSourceNode_whenEventFieldsEmpty() {
        // صفُّ نتيجة البحث في المشغّل: الحدث يجيء بلا نصٍّ ولا وصف،
        // والاسم على العقدة المصدر — وبدون هذا النزول لا مطابقة ولا تعطيل.
        val node = AccessibilityNodeInfo.obtain()
        node.text = "InstaPay"

        assertEquals(
            "InstaPay",
            ProtectedAppManager.nodeLabel(node, depth = 0)
        )
    }

    @Test
    fun nodeLabel_walksRowChildren_whenRowTextEmpty() {
        // العقدة المصدر صفٌّ فارغ النص (المستطيل القابل للنقر) واسمه
        // على أبنائه — نزولٌ محدودٌ يجده بلا خروجٍ من الحدث. addChild
        // للمعقدة مُزيل من SDK 37 فتُستدعى عبر ظلّ Robolectric.
        val row = AccessibilityNodeInfo.obtain()
        val child = AccessibilityNodeInfo.obtain()
        child.text = "InstaPay"
        shadowOf(row).addChild(child)

        assertEquals(
            "InstaPay",
            ProtectedAppManager.nodeLabel(row, depth = 0)
        )
    }

    @Test
    fun enableAccessibilityServiceDirectly_retainsExistingServices() {
        val cr = context.contentResolver
        val talkbackService =
            "com.google.android.marvin.talkback/.TalkBackService"
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            talkbackService
        )

        val success =
            ProtectedAppManager.enableAccessibilityServiceDirectly(context)
        assertTrue(success)

        val updated = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        assertTrue(
            "Existing TalkBack service must be retained",
            updated.contains(talkbackService)
        )
        val lordService =
            "com.aymankhattab.nateq/.core.audio.accessibility" +
                ".NateqAccessibilityService"
        assertTrue(
            "Lord TTS service must be appended",
            updated.contains(lordService)
        )
    }

    @Test
    fun disableAccessibilityServiceDirectly_removesOnlyOurs() {
        // العقد المحرَس: التعطيل المباشر يُزيل خدمتنا من القائمة فوراً
        // (لا عبر disableSelf المتأخرة) ويُبقي خدمات الآخرين — وهو
        // الفارق الذي يكسب السباق مع انهيار إنستاباي في onCreate.
        val cr = context.contentResolver
        val talkbackService =
            "com.google.android.marvin.talkback/.TalkBackService"
        val ourService =
            "com.aymankhattab.nateq/.core.audio.accessibility" +
                ".NateqAccessibilityService"
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "$talkbackService:$ourService"
        )

        val success = ProtectedAppManager
            .disableAccessibilityServiceDirectly(context)
        assertTrue(success)

        val updated = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        assertFalse(
            "Our service must be removed immediately",
            updated.contains(ourService)
        )
        assertTrue(
            "Other services must be retained",
            updated.contains(talkbackService)
        )
    }

    @Test
    fun disableAccessibilityServiceDirectly_whenAbsent_isIdempotent() {
        // إعادةُ التعطيل عند غياب اسمنا لا تُفسد القائمة ولا تفشل.
        val cr = context.contentResolver
        val talkbackService =
            "com.google.android.marvin.talkback/.TalkBackService"
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            talkbackService
        )

        val success = ProtectedAppManager
            .disableAccessibilityServiceDirectly(context)
        assertTrue(success)

        val updated = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        assertEquals(talkbackService, updated)
    }

    @Test
    fun disableAccessibilityServiceDirectly_removesFullPackageForm() {
        // الكسرُ المحرَس: النظام يكتب خدمتنا أحياناً بالصيغة الكاملة
        // (`pkg/full.Class`) لا المختصرة، فمطابقةُ الصيغة الواحدة تُبقي
        // الاسمَ فتُظلّ الخدمة مفعّلة ويسقط إنستاباي في onCreate.
        val cr = context.contentResolver
        val fullForm =
            "com.aymankhattab.nateq/" +
                "com.aymankhattab.nateq.core.audio.accessibility" +
                ".NateqAccessibilityService"
        Settings.Secure.putString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            fullForm
        )

        val success = ProtectedAppManager
            .disableAccessibilityServiceDirectly(context)
        assertTrue(success)

        val updated = Settings.Secure.getString(
            cr,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        assertFalse(
            "Full-package form must also be removed",
            updated.contains("NateqAccessibilityService")
        )
    }

    @Test
    fun partialSuspend_togglesStateCorrectly() {
        assertFalse(ProtectedAppManager.isPartiallySuspended)

        val app = ProtectedApp(
            packageName = "com.banking.app",
            label = "بنك مصر",
            mode = ProtectedApp.MODE_PARTIAL_SUSPEND
        )
        ProtectedAppManager.handleAppEntry(context, null, app)

        assertTrue(ProtectedAppManager.isPartiallySuspended)
        assertEquals(
            "com.banking.app",
            ProtectedAppManager.partiallySuspendedPkg
        )

        ProtectedAppManager.handleAppExit(context, "com.banking.app", "بنك مصر")
        assertFalse(ProtectedAppManager.isPartiallySuspended)
    }

    @Test
    fun fullDisable_recordsSuspendedAppAndRestoresState() {
        val app = ProtectedApp(
            packageName = "com.instapay",
            label = "إنستاباي",
            mode = ProtectedApp.MODE_FULL_DISABLE
        )
        ProtectedAppManager.handleAppEntry(context, null, app)

        assertEquals("com.instapay", settings.getSuspendedProtectedApp())
        assertTrue(settings.getSuspendedProtectedAppTimestamp() > 0L)

        ProtectedAppManager.handleAppExit(context, "com.instapay", "إنستاباي")
        assertEquals(null, settings.getSuspendedProtectedApp())
    }

    @Test
    fun fullDisable_recentSuspension_isNotHandledTwice() {
        val app = ProtectedApp(
            packageName = "com.instapay",
            label = "إنستاباي",
            mode = ProtectedApp.MODE_FULL_DISABLE
        )
        ProtectedAppManager.handleAppEntry(context, null, app)
        val firstStamp = settings.getSuspendedProtectedAppTimestamp()
        assertTrue(firstStamp > 0L)

        ProtectedAppManager.handleAppEntry(context, null, app)

        assertEquals(
            "الحدث الثاني قبل فك الارتباط لا يعيد التعطيل ولا يعيد الإطلاق",
            firstStamp,
            settings.getSuspendedProtectedAppTimestamp()
        )
    }

    @Test
    fun fullDisable_staleSuspension_isHandledAgain() {
        // علامة قديمة تعني تعطيلًا لم يكتمل (تعذّر نشره)، فلا تمنع
        // معالجة جديدة وإلا بقيت الخدمة معطّلة للأبد.
        val stale = System.currentTimeMillis() - 60_000L
        settings.setSuspendedProtectedApp("com.instapay", stale)

        val app = ProtectedApp(
            packageName = "com.instapay",
            label = "إنستاباي",
            mode = ProtectedApp.MODE_FULL_DISABLE
        )
        ProtectedAppManager.handleAppEntry(context, null, app)

        assertTrue(
            "تُعالج العلامة القديمة من جديد وتُحدَّث علامتها",
            settings.getSuspendedProtectedAppTimestamp() > stale
        )
        assertEquals(
            "com.instapay",
            settings.getSuspendedProtectedApp()
        )
    }

    @Test
    fun requestSelfDisable_reachesReceiverOfSameApp() {
        // عقد «تشغيل عبر لورد»: لا مثيل الخدمة عند المستدعي، فالتعطيل
        // يمرّ ببثٍّ تستقبله الخدمة في processها.
        var receivedAction: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                receivedAction = intent?.action
            }
        }
        val filter = IntentFilter(ProtectedAppManager.ACTION_DISABLE_SELF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            ProtectedAppManager.requestSelfDisable(context)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                ProtectedAppManager.ACTION_DISABLE_SELF,
                receivedAction
            )
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun effectiveEventPackages_includeLauncherAndProtectedApps() {
        // عقد «لا تصل أحداثُ المشغّل بلا إدراجه»: قصيرةُ الأسماء تُسقط
        // نقرة الأيقونة فلم يعد للتعطيل المسبق سبيل.
        val apps = listOf(
            ProtectedApp(
                packageName = "com.egyptianbanks.instapay",
                label = "InstaPay"
            )
        )
        val packages = ProtectedAppManager.effectiveEventPackages(
            allowed = setOf("com.whatsapp"),
            protectedApps = apps,
            homeLauncher = "com.sec.android.app.launcher"
        )
        assertTrue(packages.contains("com.whatsapp"))
        assertTrue(packages.contains("com.sec.android.app.launcher"))
        assertTrue(packages.contains("com.egyptianbanks.instapay"))
    }

    @Test
    fun effectiveEventPackages_noProtectedApps_keepWhitelistOnly() {
        val packages = ProtectedAppManager.effectiveEventPackages(
            allowed = setOf("com.whatsapp"),
            protectedApps = emptyList(),
            homeLauncher = "com.sec.android.app.launcher"
        )
        assertEquals(setOf("com.whatsapp"), packages)
    }

    @Test
    fun launcherCandidateLabel_emptyTextList_fallsBackToDescription() {
        // getText() يعود بقائمة فارغة لا null، فلو اعتمدنا `?:` وحده
        // لضاع وصف الأيقونة وهو المصدر الحقيقي لاسم التطبيق.
        val event = AccessibilityEvent.obtain()
        event.contentDescription = "InstaPay"
        assertEquals(
            "InstaPay",
            ProtectedAppManager.launcherCandidateLabel(event)
        )

        event.contentDescription = null
        assertEquals(
            null,
            ProtectedAppManager.launcherCandidateLabel(event)
        )

        event.text.add("InstaPay")
        assertEquals(
            "InstaPay",
            ProtectedAppManager.launcherCandidateLabel(event)
        )
    }

    @Test
    fun proactiveWatch_defaultIsDisabled() {
        // العقد المحرَس: المراقبة الاستباقية معطّلة افتراضياً لأنه
        // تستهلك البطارية، فلا تُفعَّل من غير علم المستخدم.
        assertFalse(settings.isProactiveProtectedWatchEnabled())
    }

    @Test
    fun proactiveWatch_toggleRoundTrips() {
        settings.setProactiveProtectedWatchEnabled(true)
        assertTrue(settings.isProactiveProtectedWatchEnabled())
        settings.setProactiveProtectedWatchEnabled(false)
        assertFalse(settings.isProactiveProtectedWatchEnabled())
    }

    @Test
    fun proactiveWatch_resetsToDisabled_onImport() {
        // يُصفَّر عند الاستيراد لأنه حساس للبطارية: نسخةٌ مستوردة لا
        // تُفعّل استطلاعاً دورياً من غير علم المستخدم.
        settings.setProactiveProtectedWatchEnabled(true)
        assertTrue(settings.isProactiveProtectedWatchEnabled())

        val imported = settings.importSettings(
            mapOf("proactive_protected_watch_enabled" to true)
        )
        assertTrue(imported)
        assertFalse(
            "Imported settings must not enable battery-hungry watch",
            settings.isProactiveProtectedWatchEnabled()
        )
    }

    @Test
    fun pickForegroundProtectedApp_ignoresProtectedAppLeftBehind() {
        // الكسر المحرَس: لو اكتفينا بأن يظهر محميٌّ ببن الأحداث لاخترناه
        // بعد أن غادر المستخدم إلى تطبيق آخر، فيُعطَّل غيرُ الأمامي.
        // آخرُ حدثٍ (كروم) هو الأمام، فلا تطبيق محمي أماماً.
        val apps = listOf(
            ProtectedApp(
                packageName = "com.egyptianbanks.instapay",
                label = "InstaPay",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )
        val picked = ProtectedAppManager.pickForegroundProtectedApp(
            resumedPackages = listOf(
                "com.egyptianbanks.instapay",
                "com.android.chrome"
            ),
            protectedApps = apps,
            ownPackage = "com.aymankhattab.nateq"
        )
        assertEquals(null, picked)
    }

    @Test
    fun pickForegroundProtectedApp_ignoresOwnPackage() {
        // لا يجوز أن يُعدّ تطبيقنا نفسه تطبيقاً محمياً فيُعطّل نفسه
        // في حلقةٍ لا نهائية.
        val apps = listOf(
            ProtectedApp(
                packageName = "com.aymankhattab.nateq",
                label = "Lord TTS",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )
        val picked = ProtectedAppManager.pickForegroundProtectedApp(
            resumedPackages = listOf("com.aymankhattab.nateq"),
            protectedApps = apps,
            ownPackage = "com.aymankhattab.nateq"
        )
        assertEquals(null, picked)
    }

    @Test
    fun pickForegroundProtectedApp_returnsProtectedWhenForeground() {
        val apps = listOf(
            ProtectedApp(
                packageName = "com.egyptianbanks.instapay",
                label = "InstaPay",
                mode = ProtectedApp.MODE_FULL_DISABLE
            )
        )
        val picked = ProtectedAppManager.pickForegroundProtectedApp(
            resumedPackages = listOf("com.egyptianbanks.instapay"),
            protectedApps = apps,
            ownPackage = "com.aymankhattab.nateq"
        )
        assertNotNull(picked)
        assertEquals("com.egyptianbanks.instapay", picked?.packageName)
    }
}
