package com.aymankhattab.nateq.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ReadLaterProcessTextActivityTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository.resetInstanceForTesting()
        context.getSharedPreferences("nateq_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        repo.unregister()
        SettingsRepository.resetInstanceForTesting()
    }

    @Test
    fun processText_savesText_andFinishesWithoutReturningReplacement() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "نص محدد للحفظ")
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        val controller = Robolectric.buildActivity(
            ReadLaterProcessTextActivity::class.java,
            intent
        ).create()
        val activity = controller.get()

        assertTrue(activity.isFinishing)
        val shadow = org.robolectric.Shadows.shadowOf(activity)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        // عدم إرجاع نص بديل حتى لا يُعدّل النص في التطبيق المصدر
        assertNull(shadow.resultIntent?.getCharSequenceExtra(
            Intent.EXTRA_PROCESS_TEXT
        ))

        val items = repo.getReadLaterItems()
        assertEquals(1, items.size)
        assertEquals("نص محدد للحفظ", items[0].text)

        val latestToast = ShadowToast.getTextOfLatestToast()
        assertTrue(
            latestToast?.contains("تم الحفظ") == true ||
                latestToast?.contains("Saved") == true
        )
    }

    @Test
    fun processText_duplicateText_showsDuplicateToastAndDoesNotDuplicate() {
        repo.addReadLaterText("نص مكرر")

        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "نص مكرر")
        }
        val controller = Robolectric.buildActivity(
            ReadLaterProcessTextActivity::class.java,
            intent
        ).create()
        val activity = controller.get()

        assertTrue(activity.isFinishing)
        val items = repo.getReadLaterItems()
        assertEquals(1, items.size)

        val latestToast = ShadowToast.getTextOfLatestToast()
        assertTrue(
            latestToast?.contains("موجود مسبقاً") == true ||
                latestToast?.contains("already saved") == true
        )
    }

    @Test
    fun processText_emptyText_showsEmptyToastAndDoesNotSave() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "   ")
        }
        val controller = Robolectric.buildActivity(
            ReadLaterProcessTextActivity::class.java,
            intent
        ).create()
        val activity = controller.get()

        assertTrue(activity.isFinishing)
        val items = repo.getReadLaterItems()
        assertTrue(items.isEmpty())

        val latestToast = ShadowToast.getTextOfLatestToast()
        assertTrue(
            latestToast?.contains("فارغ") == true ||
                latestToast?.contains("empty") == true
        )
    }
}
