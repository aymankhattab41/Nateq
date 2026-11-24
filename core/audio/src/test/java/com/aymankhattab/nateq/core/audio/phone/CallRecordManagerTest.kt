package com.aymankhattab.nateq.core.audio.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * اختبارات وحدة لمدير تسجيل المكالمات وتضخيم الصوت البرمجي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallRecordManagerTest {

    private lateinit var context: Context
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepo = SettingsRepository.create(context)
    }

    @Test
    fun boostPcmSamples_multiplierOne_leavesSamplesUnchanged() {
        val buffer = shortArrayOf(100, -200, 300, -400)
        val original = buffer.clone()

        CallRecordManager.boostPcmSamples(buffer, buffer.size, 1.0f)
        for (i in buffer.indices) {
            assertEquals(original[i], buffer[i])
        }
    }

    @Test
    fun boostPcmSamples_multiplierTwoPointFive_amplifiesCorrectly() {
        val buffer = shortArrayOf(100, -200, 300, -400)

        CallRecordManager.boostPcmSamples(buffer, buffer.size, 2.5f)
        assertEquals(250.toShort(), buffer[0])
        assertEquals((-500).toShort(), buffer[1])
        assertEquals(750.toShort(), buffer[2])
        assertEquals((-1000).toShort(), buffer[3])
    }

    @Test
    fun boostPcmSamples_preventsClippingBeyondShortBounds() {
        val buffer = shortArrayOf(20000, -25000)

        CallRecordManager.boostPcmSamples(buffer, buffer.size, 2.5f)
        assertEquals(Short.MAX_VALUE, buffer[0])
        assertEquals(Short.MIN_VALUE, buffer[1])
    }

    @Test
    fun shouldAutoRecord_whenDisabled_alwaysReturnsFalse() {
        settingsRepo.setPhoneRecordEnabled(false)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL
        )

        assertFalse(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = false
            )
        )
    }

    @Test
    fun shouldAutoRecord_whenManual_alwaysReturnsFalse() {
        settingsRepo.setPhoneRecordEnabled(true)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_MANUAL
        )

        assertFalse(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = false
            )
        )
    }

    @Test
    fun shouldAutoRecord_whenAutoAll_returnsTrueForKnownAndUnsaved() {
        settingsRepo.setPhoneRecordEnabled(true)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL
        )

        assertTrue(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = true
            )
        )
        assertTrue(
            CallRecordManager.shouldAutoRecord(
                context,
                "0987654321",
                isKnownContact = false
            )
        )
    }

    @Test
    fun shouldAutoRecord_whenAutoUnsaved_recordsOnlyUnsavedContacts() {
        settingsRepo.setPhoneRecordEnabled(true)
        settingsRepo.setPhoneRecordMode(
            SettingsRepository.PHONE_RECORD_MODE_AUTO_UNSAVED
        )

        assertFalse(
            CallRecordManager.shouldAutoRecord(
                context,
                "0123456789",
                isKnownContact = true
            )
        )
        assertTrue(
            CallRecordManager.shouldAutoRecord(
                context,
                "0987654321",
                isKnownContact = false
            )
        )
    }

    @Test
    fun recordingState_initiallyIdle_andStopWhenIdleReturnsNull() {
        assertEquals(
            RecordingState.IDLE,
            CallRecordManager.getRecordingState()
        )
        assertFalse(CallRecordManager.isRecording())

        val result = CallRecordManager.stopRecording(context)
        assertEquals(null, result)
        assertEquals(
            RecordingState.IDLE,
            CallRecordManager.getRecordingState()
        )
    }

    @Test
    fun startRecording_andStopRecording_handlesLifecycleProperly() {
        settingsRepo.setPhoneRecordEnabled(true)
        val started = CallRecordManager.startRecording(
            context,
            "01012345678",
            "اختبار"
        )
        if (started) {
            assertTrue(CallRecordManager.isRecording())
            val item = CallRecordManager.getCurrentItem()
            assertEquals("01012345678", item?.phoneNumber)
            assertEquals("اختبار", item?.callerName)

            // محاولة بدء جلسة ثانية ترفض
            val second = CallRecordManager.startRecording(
                context,
                "01087654321",
                "ثانٍ"
            )
            assertFalse(second)

            // إيقاف التسجيل فوراً دون تجميد
            val stopped = CallRecordManager.stopRecording(context)
            assertEquals("01012345678", stopped?.phoneNumber)
        }
    }

    private fun seedIndex(items: List<CallRecordItem>) {
        val dir = CallRecordManager.getRecordingsDir(context)
        val file = File(dir, "call_recordings_index.json")
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("phoneNumber", item.phoneNumber)
                put("callerName", item.callerName)
                put("timestamp", item.timestamp)
                put("durationSeconds", item.durationSeconds)
                put("filePath", item.filePath)
                put("fileSize", item.fileSize)
                put("uriString", item.uriString ?: "")
                put("isSilent", item.isSilent)
            }
            array.put(obj)
        }
        file.writeText(array.toString(), Charsets.UTF_8)
    }

    @Test
    fun deleteRecording_notFound_returnsNotFoundResult() {
        val res = CallRecordManager.deleteRecordingWithResult(
            context,
            "non_existent_id"
        )
        assertEquals(CallRecordManager.DeleteRecordResult.NotFound, res)
    }

    @Test
    fun deleteRecording_successfulFileDelete_updatesIndexAndReturnsSuccess() {
        val dummyFile = File(context.cacheDir, "test_rec_success.wav")
        dummyFile.writeBytes(ByteArray(100))
        assertTrue(dummyFile.exists())

        val item = CallRecordItem(
            id = "test_success_1",
            phoneNumber = "01000000001",
            callerName = "ناجح",
            timestamp = System.currentTimeMillis(),
            durationSeconds = 5L,
            filePath = dummyFile.absolutePath,
            fileSize = 100L
        )
        seedIndex(listOf(item))
        assertEquals(1, CallRecordManager.getRecordings(context).size)

        val res = CallRecordManager.deleteRecordingWithResult(
            context,
            "test_success_1"
        )
        assertEquals(CallRecordManager.DeleteRecordResult.Success, res)
        assertFalse(dummyFile.exists())
        assertEquals(0, CallRecordManager.getRecordings(context).size)
    }

    @Test
    fun deleteRecording_fileDeleteFails_keepsIndexAndReturnsFailed() {
        // إنشاء مجلد غير فارغ بنفس مسار الملف ليجعل File.delete() يعيد false
        val blockedDir = File(context.cacheDir, "blocked_rec_dir")
        blockedDir.mkdirs()
        val child = File(blockedDir, "child.wav")
        child.writeBytes(ByteArray(50))

        val item = CallRecordItem(
            id = "test_fail_1",
            phoneNumber = "01000000002",
            callerName = "فاشل",
            timestamp = System.currentTimeMillis(),
            durationSeconds = 10L,
            filePath = blockedDir.absolutePath,
            fileSize = 50L
        )
        seedIndex(listOf(item))
        assertEquals(1, CallRecordManager.getRecordings(context).size)

        val res = CallRecordManager.deleteRecordingWithResult(
            context,
            "test_fail_1"
        )
        assertTrue(res is CallRecordManager.DeleteRecordResult.Failed)
        // التأكد من عدم فقدان التسجيل من الفهرس عند فشل حذف الملف
        assertEquals(1, CallRecordManager.getRecordings(context).size)

        // تنظيف
        child.delete()
        blockedDir.delete()
    }
}
