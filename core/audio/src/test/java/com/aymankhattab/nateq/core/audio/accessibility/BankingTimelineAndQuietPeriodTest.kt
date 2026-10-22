package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات الجولة 50 — الأمر 6:
 * الخط الزمني وفترة الصمت التام لاستجابة إنستاباي وتطبيقات البنوك.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class BankingTimelineAndQuietPeriodTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProtectedAppTimelineTracker.clear()
    }

    @Test
    fun timelineTracker_recordsAllEventsInOrder() {
        val pkg = "com.instapay.app"

        ProtectedAppTimelineTracker.recordLauncherClick(pkg)
        ProtectedAppTimelineTracker.recordLordDetected(pkg)
        ProtectedAppTimelineTracker.recordDisableSelfCalled(pkg)
        ProtectedAppTimelineTracker.recordDisabledConfirmed(pkg, 120L)
        ProtectedAppTimelineTracker.recordAppWindowAppeared(pkg)
        ProtectedAppTimelineTracker.recordBiometricWindowAppeared(
            "com.android.systemui"
        )
        ProtectedAppTimelineTracker.recordBiometricFinished(
            "com.android.systemui", true
        )

        val events = ProtectedAppTimelineTracker.getEvents()
        assertEquals(7, events.size)

        assertEquals("النقر على أيقونة الإطلاق", events[0].eventName)
        assertEquals("اكتشاف Lord للتطبيق المحمي", events[1].eventName)
        assertEquals("استدعاء disableSelf", events[2].eventName)
        assertEquals("تأكيد التوقف الفعلي للخدمة", events[3].eventName)
        assertEquals("ظهور أول نافذة للتطبيق", events[4].eventName)
        assertEquals("ظهور نافذة البصمة", events[5].eventName)
        assertEquals("نتيجة البصمة (نجاح)", events[6].eventName)

        val report = ProtectedAppTimelineTracker.getTimelineReport()
        assertTrue(report.contains("الخط الزمني لاستجابة التطبيقات المحمية"))
        assertTrue(report.contains(pkg))
        assertTrue(report.contains("120ms"))
    }

    @Test
    fun quietPeriod_blocksQueriesAndSpeech() {
        val pkg = "com.instapay.app"
        ProtectedAppTimelineTracker.startQuietPeriod(pkg)

        assertTrue(
            "يجب أن تكون فترة الصمت نشطة",
            ProtectedAppTimelineTracker.isInQuietPeriod()
        )
        assertTrue(
            "حزمة التطبيق تعتبر هادئة",
            ProtectedAppTimelineTracker.isQuietPackage(pkg)
        )
        assertTrue(
            "حزمة البصمة تعتبر هادئة",
            ProtectedAppTimelineTracker.isBiometricPackage(
                "com.android.systemui"
            )
        )
        assertTrue(
            ProtectedAppTimelineTracker.isQuietPackage("com.android.systemui")
        )

        // النطق ممنوع أثناء فترة الصمت
        assertFalse(
            "A11ySpeechCoordinator.canSpeak يجب أن يعيد false في فترة الصمت",
            A11ySpeechCoordinator.canSpeak(context, isInterruptAllowed = true)
        )

        // خدمة إمكانية الوصول: استعلام العقد والنوافذ يرجع null/empty في الصمت
        val controller = Robolectric.buildService(
            NateqAccessibilityService::class.java
        )
        val service = controller.create().get()

        assertNull(
            "rootInActiveWindow يجب أن يكون null في فترة الصمت",
            service.rootInActiveWindow
        )
        assertTrue(
            "windows يجب أن تكون فارغة في فترة الصمت",
            service.windows.isEmpty()
        )

        // إنهاء فترة الصمت
        ProtectedAppTimelineTracker.endQuietPeriod()
        assertFalse(ProtectedAppTimelineTracker.isInQuietPeriod())
    }
}
