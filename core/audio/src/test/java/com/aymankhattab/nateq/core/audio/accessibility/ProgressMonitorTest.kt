package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class ProgressMonitorTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ProgressMonitor.clearAll()
    }

    @Test
    fun `announces progress when reaching 25 percent increments`() {
        val trackerId = "com.test.app/dl"

        // 10% - لا يُعلن (لم يصل 25%)
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 10,
            max = 100
        )
        assertNull(ProgressMonitor.getLastAnnounced(trackerId))

        // 25% - يُعلن
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 25,
            max = 100
        )
        assertEquals(25, ProgressMonitor.getLastAnnounced(trackerId))

        // 30% - لا يُعلن (لم يصل 50%)
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 30,
            max = 100
        )
        assertEquals(25, ProgressMonitor.getLastAnnounced(trackerId))

        // 55% - يُعلن 50%
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 55,
            max = 100
        )
        assertEquals(50, ProgressMonitor.getLastAnnounced(trackerId))

        // 75% - يُعلن 75%
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 75,
            max = 100
        )
        assertEquals(75, ProgressMonitor.getLastAnnounced(trackerId))

        // 100% - يُعلن 100% ثم يُحذف التتبع
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 100,
            max = 100
        )
        // عند الاكتمال يُحذف المتتبع لمنع تراكم الذاكرة
        assertNull(ProgressMonitor.getLastAnnounced(trackerId))
    }

    @Test
    fun `regression or restart resets tracking and re-announces`() {
        val trackerId = "com.test.app/task"

        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 60,
            max = 100
        )
        assertEquals(50, ProgressMonitor.getLastAnnounced(trackerId))

        // تراجع أو إعادة بدء (مثلاً إعادة محاولة التحميل من 10%)
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 10,
            max = 100
        )
        assertNull(ProgressMonitor.getLastAnnounced(trackerId))

        // الوصول إلى 25% مجدداً يُعلن
        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 25,
            max = 100
        )
        assertEquals(25, ProgressMonitor.getLastAnnounced(trackerId))
    }

    @Test
    fun `invalid max is safely ignored`() {
        val trackerId = "com.test.app/invalid"

        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 50,
            max = 0
        )
        assertNull(ProgressMonitor.getLastAnnounced(trackerId))

        ProgressMonitor.onProgressChanged(
            context = context,
            trackerKey = trackerId,
            current = 50,
            max = -10
        )
        assertNull(ProgressMonitor.getLastAnnounced(trackerId))
    }

    @Test
    fun `distinct tracker IDs are tracked independently`() {
        val app1 = "com.app.one/dl"
        val app2 = "com.app.two/dl"

        ProgressMonitor.onProgressChanged(context, app1, 25, 100)
        ProgressMonitor.onProgressChanged(context, app2, 50, 100)

        assertEquals(25, ProgressMonitor.getLastAnnounced(app1))
        assertEquals(50, ProgressMonitor.getLastAnnounced(app2))
    }
}
