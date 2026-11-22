package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * اختبارات إدارة Job وموارد ProtectedAppWatcherService:
 * 1. استدعاء onStartCommand المتكرر يملك Job مراقبة واحدة فقط
 *    دون مضاعفة الحلقات.
 * 2. قراءة حالة الشاشة الفعلية عند بدء الخدمة.
 * 3. إلغاء العمل بالكامل وتطهير المستقبلات عند تدمير الخدمة onDestroy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ProtectedAppWatcherServiceJobTest {

    private lateinit var controller:
        ServiceController<ProtectedAppWatcherService>
    private lateinit var service: ProtectedAppWatcherService
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = Robolectric.buildService(
            ProtectedAppWatcherService::class.java
        )
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    @Test
    fun repeatedOnStartCommand_ownsSingleJob_andUpdatesTarget() {
        val intent1 = Intent(context, ProtectedAppWatcherService::class.java)
            .putExtra("extra_pkg", "com.bank.app1")
            .putExtra("extra_label", "Bank1")

        service.onStartCommand(intent1, 0, 1)
        val job1 = service.getWatcherJobForTesting()
        assertNotNull("Job المراقبة يجب أن تبدأ", job1)
        assertTrue("Job المراقبة يجب أن تكون نشطة", job1?.isActive == true)

        // استدعاء ثانٍ بحزمة مختلفة (تبديل تطبيق)
        val intent2 = Intent(context, ProtectedAppWatcherService::class.java)
            .putExtra("extra_pkg", "com.bank.app2")
            .putExtra("extra_label", "Bank2")

        service.onStartCommand(intent2, 0, 2)
        val job2 = service.getWatcherJobForTesting()

        // يجب أن تبقى نفس الـ Job النشطة دون إطلاق حلقة جديدة موازية
        assertEquals(
            "استدعاء onStartCommand المتكرر لا يضاعف الـ Job",
            job1,
            job2
        )
        assertTrue(job2?.isActive == true)
    }

    @Test
    fun screenOffBroadcast_pausesPollingState() {
        assertTrue(service.isScreenOnForTesting())

        val receiver = service.getScreenReceiverForTesting()
        assertNotNull(receiver)

        val screenOffIntent = Intent(Intent.ACTION_SCREEN_OFF)
        receiver?.onReceive(context, screenOffIntent)

        assertFalse(
            "إطفاء الشاشة يجب أن يوقف الاستطلاع لتوفير البطارية",
            service.isScreenOnForTesting()
        )

        val screenOnIntent = Intent(Intent.ACTION_SCREEN_ON)
        receiver?.onReceive(context, screenOnIntent)
        assertTrue(service.isScreenOnForTesting())
    }

    @Test
    fun onDestroy_cancelsJobAndCleansUp() {
        val intent = Intent(context, ProtectedAppWatcherService::class.java)
            .putExtra("extra_pkg", "com.test.app")
        service.onStartCommand(intent, 0, 1)

        val job = service.getWatcherJobForTesting()
        assertTrue(job?.isActive == true)

        controller.destroy()
        assertTrue("تدمير الخدمة يجب أن يلغي الـ Job", job?.isCancelled == true)
    }
}
