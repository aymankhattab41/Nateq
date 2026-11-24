package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 30, 35, 37])
class UnlabeledElementStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var store: UnlabeledElementStore

    @Before
    fun setUp() {
        context.getSharedPreferences("nateq_unlabeled_elements", 0)
            .edit()
            .clear()
            .commit()
        store = UnlabeledElementStore.getInstance(context)
        store.replaceAllCustomLabels(emptyMap())
    }

    @Test
    fun `save and retrieve custom label`() {
        val pkg = "com.example.app"
        val viewId = "submit_button"
        assertNull(store.getCustomLabel(pkg, viewId))

        store.setCustomLabel(pkg, viewId, "زر الإرسال")
        assertEquals("زر الإرسال", store.getCustomLabel(pkg, viewId))

        val all = store.getAllCustomLabels()
        assertEquals(1, all.size)
        assertEquals("زر الإرسال", all["$pkg:$viewId"])

        store.setCustomLabel(pkg, viewId, null)
        assertNull(store.getCustomLabel(pkg, viewId))
        assertTrue(store.getAllCustomLabels().isEmpty())
    }

    @Test
    fun `replaceAllCustomLabels replaces existing and triggers update`() {
        store.setCustomLabel("app1", "btn", "زر 1")

        val newMap = mapOf(
            "app2:btn" to "زر 2",
            "app3:btn" to "زر 3"
        )
        store.replaceAllCustomLabels(newMap)

        assertNull(store.getCustomLabel("app1", "btn"))
        assertEquals("زر 2", store.getCustomLabel("app2", "btn"))
        assertEquals("زر 3", store.getCustomLabel("app3", "btn"))
        assertEquals(2, store.getAllCustomLabels().size)
    }

    @Test
    fun `setLastFocusedElement ignores nateq own package`() {
        // تسجيل عنصر من تطبيق خارجي ينجح
        store.setLastFocusedElement(
            packageName = "com.thirdparty.app",
            viewIdResourceName = "com.thirdparty.app:id/unlabeled_icon"
        )
        val snapshot = store.getLastFocusedElement()
        assertNotNull(snapshot)
        assertEquals("com.thirdparty.app", snapshot?.first)

        // تسجيل عنصر من تطبيق ناطق نفسه يتم تجاهله
        store.setLastFocusedElement(
            packageName = context.packageName,
            viewIdResourceName = "${context.packageName}:id/some_view"
        )
        // يجب أن تظل اللقطة السابقة للتطبيق الخارجي دون استبدال بواجهة ناطق
        val afterNateq = store.getLastFocusedElement()
        assertEquals("com.thirdparty.app", afterNateq?.first)
    }

    @Test
    fun `getLastFocusedElement honors maxAgeMs TTL`() {
        store.setLastFocusedElement(
            packageName = "com.another.app",
            viewIdResourceName = "com.another.app:id/star"
        )

        // صلاحية غير منتهية
        assertNotNull(store.getLastFocusedElement(maxAgeMs = 5000L))

        // محاكاة انتهاء الصلاحية بمطالبة عمر 0
        assertNull(store.getLastFocusedElement(maxAgeMs = -1L))
    }

    @Test
    fun `temporary focused snapshot is never exported as permanent label`() {
        store.setLastFocusedElement(
            packageName = "com.some.app",
            viewIdResourceName = "com.some.app:id/temp_btn"
        )

        // اللقطة موجودة مؤقتاً
        assertNotNull(store.getLastFocusedElement())

        // لكنها ليست ضمن التسميات المحفوظة الدائمة
        val permanentLabels = store.getAllCustomLabels()
        assertTrue(permanentLabels.isEmpty())
    }
}
