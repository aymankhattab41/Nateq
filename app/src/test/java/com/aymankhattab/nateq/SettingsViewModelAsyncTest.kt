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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * نسخ احتياطي) التي تجري على خيط IO في نطاق viewModelScope — بحقن
 * Dispatchers.Main عبر kotlinx-coroutines-test، وبمحتوى SAF مُسجَّل
 * عبر ShadowContentResolver.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelAsyncTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var dict: PronunciationDictionary
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context)
        dict = PronunciationDictionary(context)
        vm = SettingsViewModel(settings, dict)
        // حقن Main dispatcher حتى يُنشأ viewModelScope بلا Main حقيقي
        Dispatchers.setMain(StandardTestDispatcher())
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

    private suspend inline fun <reified T : SettingsOperation>
        awaitOperation(): T =
        vm.operationEvents.first { it is T } as T

    @Test
    fun exportDict_writesJsonToUri() = runTest {
        dict.addEntry("ص", "صفحة")
        val uri = Uri.parse("content://nateq.test/export.json")
        val out = registerOutput(uri)

        vm.exportDict(uri, context.contentResolver)
        val event = awaitOperation<SettingsOperation.DictExported>()

        assertTrue(event.ok)
        assertTrue(
            out.toByteArray().toString(Charsets.UTF_8).contains("صفحة")
        )
    }

    @Test
    fun exportDict_ioFailure_emitsFailure() = runTest {
        val uri = Uri.parse("content://nateq.test/blocked.json")
        shadowOf(context.contentResolver).registerOutputStream(
            uri, ThrowingOutputStream()
        )

        vm.exportDict(uri, context.contentResolver)
        val event = awaitOperation<SettingsOperation.DictExported>()

        assertFalse(event.ok)
    }

    @Test
    fun importDict_merge_keepsExistingAndAddsNew() = runTest {
        dict.addEntry("قديم", "مقابل")
        val json = """{"ص":"صفحة","د.":"دكتور"}"""

        vm.importDict(json, merge = true)
        val event = awaitOperation<SettingsOperation.DictImported>()

        assertTrue(event.ok)
        assertEquals("صفحة", dict.getAllEntries()["ص"])
        assertEquals("مقابل", dict.getAllEntries()["قديم"])
    }

    @Test
    fun restoreBackup_readsFile_andEmitsRestored() = runTest {
        settings.setTimeAnnouncementInterval(15)
        val json = vm.buildBackupJson()
        val uri = Uri.parse("content://nateq.test/backup.json")
        registerInput(uri, json)

        vm.restoreBackup(uri, context.contentResolver)
        val event = awaitOperation<SettingsOperation.Restored>()

        assertTrue(event.ok)
        assertEquals(15, settings.getTimeAnnouncementInterval())
    }

    @Test
    fun exportBackup_writesJsonToUri() = runTest {
        settings.setTimeAnnouncementInterval(15)
        dict.addEntry("HTTP", "إتش تي تي بي")
        val uri = Uri.parse("content://nateq.test/backup_export.json")
        val out = registerOutput(uri)

        vm.exportBackup(uri, context.contentResolver)
        val event = awaitOperation<SettingsOperation.BackedUp>()

        assertTrue(event.ok)
        val written = out.toByteArray().toString(Charsets.UTF_8)
        assertTrue(written.contains("\"version\":1"))
        assertTrue(written.contains("إتش تي تي بي"))
    }
}
