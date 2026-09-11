package com.aymankhattab.nateq

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import com.aymankhattab.nateq.engine.PronunciationDictionary
import com.aymankhattab.nateq.settings.SettingsViewModel
import com.aymankhattab.nateq.settings.SettingsViewModel.SettingsOperation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * اختبارات العمليات غير المتزامنة في SettingsViewModel (تصدير/استيراد/
 * نسخ احتياطي) — تجري كلها على جدولة coroutines-test واحدة حتمية
 * (بلا خيوط IO حقيقية تُعلّق مع Robolectric). لأن العملية والحدث مختلفان،
 * يُشترك المُجمِّع أولاً عبر async ثم تُشغَّل العملية ثم advanceUntilIdle —
 * فيُسلم SharedFlow (replay=0) القيمة للمُجمِّع القائم ولا تُفقد.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelAsyncTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var dict: PronunciationDictionary
    private lateinit var vm: SettingsViewModel
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var mainDispatcher: TestDispatcher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
        dict = PronunciationDictionary(context)
        // جدولة واحدة للاختبار: حقنها في Main وفي عمليات الـ VM معاً
        scheduler = TestCoroutineScheduler()
        mainDispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(mainDispatcher)
        vm = SettingsViewModel(settings, dict, mainDispatcher)
        context.getSharedPreferences("nateq_settings", 0)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun registerOutput(uri: Uri): ByteArrayOutputStream =
        ByteArrayOutputStream().also {
            shadowOf(context.contentResolver).registerOutputStream(uri, it)
        }

    private fun registerInput(uri: Uri, text: String) {
        shadowOf(context.contentResolver).registerInputStream(
            uri, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        )
    }

    private class ThrowingOutputStream : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("write blocked")
    }

    /** الشكل الحتمي: اشتراك أولاً، ثم تشغيل العملية، ثم دفع الجدولة. */
    private suspend fun TestScope.runOperation(
        trigger: () -> Unit,
        predicate: (SettingsOperation) -> Boolean
    ): SettingsOperation {
        val event = async<SettingsOperation> {
            vm.operationEvents.first(predicate)
        }
        trigger()
        scheduler.advanceUntilIdle()
        return event.await()
    }

    @Test
    fun exportDict_writesJsonToUri() = runTest(scheduler) {
        dict.addEntry("ص", "صفحة")
        val uri = Uri.parse("content://nateq.test/export.json")
        val out = registerOutput(uri)

        val event = runOperation(
            { vm.exportDict(uri, context.contentResolver) },
            { it is SettingsOperation.DictExported }
        )

        assertTrue((event as SettingsOperation.DictExported).ok)
        assertTrue(
            out.toByteArray().toString(Charsets.UTF_8).contains("صفحة")
        )
    }

    @Test
    fun exportDict_ioFailure_emitsFailure() = runTest(scheduler) {
        val uri = Uri.parse("content://nateq.test/blocked.json")
        shadowOf(context.contentResolver).registerOutputStream(
            uri, ThrowingOutputStream()
        )

        val event = runOperation(
            { vm.exportDict(uri, context.contentResolver) },
            { it is SettingsOperation.DictExported }
        )

        assertFalse((event as SettingsOperation.DictExported).ok)
    }

    @Test
    fun importDict_merge_keepsExistingAndAddsNew() = runTest(scheduler) {
        dict.addEntry("قديم", "مقابل")
        val json = """{"ص":"صفحة","د.":"دكتور"}"""

        val event = runOperation(
            { vm.importDict(json, merge = true) },
            { it is SettingsOperation.DictImported }
        )

        assertTrue((event as SettingsOperation.DictImported).ok)
        assertEquals("صفحة", dict.getAllEntries()["ص"])
        assertEquals("مقابل", dict.getAllEntries()["قديم"])
    }

    @Test
    fun restoreBackup_readsFile_andEmitsRestored() = runTest(scheduler) {
        settings.setTimeAnnouncementInterval(15)
        val json = vm.buildBackupJson()
        val uri = Uri.parse("content://nateq.test/backup.json")
        registerInput(uri, json)

        val event = runOperation(
            { vm.restoreBackup(uri, context.contentResolver) },
            { it is SettingsOperation.Restored }
        )

        assertTrue((event as SettingsOperation.Restored).ok)
        assertEquals(15, settings.getTimeAnnouncementInterval())
    }

    @Test
    fun restoreCallersOnlyBackup_emitsCallersOnlyFlag() = runTest(scheduler) {
        settings.setCustomCallerNames(mapOf("0555" to "أحمد"))
        val json = vm.buildBackupJson()
        val uri = Uri.parse("content://nateq.test/backup_callers.json")
        registerInput(uri, json)

        val event = runOperation(
            { vm.restoreBackup(uri, context.contentResolver) },
            { it is SettingsOperation.Restored }
        ) as SettingsOperation.Restored

        assertTrue(event.ok)
        assertTrue(
            "نسخة أسماء متصلين فقط تُعلَّم callersOnly",
            event.callersOnly
        )
    }

    @Test
    fun restoreFullBackup_emitsNotCallersOnly() = runTest(scheduler) {
        settings.setTimeAnnouncementInterval(15)
        settings.setCustomCallerNames(mapOf("0555" to "أحمد"))
        val json = vm.buildBackupJson()
        val uri = Uri.parse("content://nateq.test/backup_full.json")
        registerInput(uri, json)

        val event = runOperation(
            { vm.restoreBackup(uri, context.contentResolver) },
            { it is SettingsOperation.Restored }
        ) as SettingsOperation.Restored

        assertTrue(event.ok)
        assertFalse(event.callersOnly)
    }

    @Test
    fun exportBackup_writesJsonToUri() = runTest(scheduler) {
        settings.setTimeAnnouncementInterval(15)
        dict.addEntry("HTTP", "إتش تي تي بي")
        val uri = Uri.parse("content://nateq.test/backup_export.json")
        val out = registerOutput(uri)

        val event = runOperation(
            { vm.exportBackup(uri, context.contentResolver) },
            { it is SettingsOperation.BackedUp }
        )

        assertTrue((event as SettingsOperation.BackedUp).ok)
        val written = out.toByteArray().toString(Charsets.UTF_8)
        assertTrue(written.contains("\"version\":1"))
        assertTrue(written.contains("إتش تي تي بي"))
    }
}