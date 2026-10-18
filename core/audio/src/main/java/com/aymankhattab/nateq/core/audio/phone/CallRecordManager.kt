package com.aymankhattab.nateq.core.audio.phone

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * بيانات تسجيل المكالمة الصوتية.
 */
data class CallRecordItem(
    val id: String,
    val phoneNumber: String,
    val callerName: String,
    val timestamp: Long,
    var durationSeconds: Long,
    val filePath: String,
    var fileSize: Long
)

/**
 * مدير تسجيل المكالمات وتضخيم الصوت البرمجي للمكفوفين.
 */
object CallRecordManager {

    private const val INDEX_FILE_NAME = "call_recordings_index.json"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var isRecordingActive = false
    private var recordingThread: Thread? = null
    private var currentItem: CallRecordItem? = null
    private var recordingStartTime = 0L

    fun isRecording(): Boolean = isRecordingActive

    fun getCurrentItem(): CallRecordItem? = currentItem

    /**
     * التحقق مما إذا كانت المكالمة تستحق التسجيل التلقائي.
     */
    fun shouldAutoRecord(
        context: Context,
        phoneNumber: String,
        isKnownContact: Boolean
    ): Boolean {
        val repo = SettingsRepository.create(context)
        if (!repo.isPhoneRecordEnabled()) return false

        return when (repo.getPhoneRecordMode()) {
            SettingsRepository.PHONE_RECORD_MODE_AUTO_ALL -> true
            SettingsRepository.PHONE_RECORD_MODE_AUTO_UNSAVED -> !isKnownContact
            else -> false
        }
    }

    /**
     * بدء تسجيل المكالمة الحالية.
     */
    @Synchronized
    fun startRecording(
        context: Context,
        phoneNumber: String,
        callerName: String
    ): Boolean {
        if (isRecordingActive) return false

        val dir = getRecordingsDir(context)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val safeNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val fileName = "rec_${safeNumber}_$timestamp.wav"
        val outputFile = File(dir, fileName)

        val item = CallRecordItem(
            id = id,
            phoneNumber = phoneNumber,
            callerName = callerName,
            timestamp = timestamp,
            durationSeconds = 0L,
            filePath = outputFile.absolutePath,
            fileSize = 0L
        )

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        val recordSource = try {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } catch (_: Exception) {
            MediaRecorder.AudioSource.MIC
        }

        val audioRecord = try {
            AudioRecord(
                recordSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (_: Exception) {
            null
        }

        if (audioRecord == null ||
            audioRecord.state != AudioRecord.STATE_INITIALIZED
        ) {
            audioRecord?.release()
            return false
        }

        val repo = SettingsRepository.create(context)
        val boostEnabled = repo.isPhoneRecordBoostVolumeEnabled()
        // مضاعف تضخيم الصوت البرمجي للطرفين
        val gainMultiplier = if (boostEnabled) 2.5f else 1.0f

        currentItem = item
        recordingStartTime = System.currentTimeMillis()
        isRecordingActive = true

        recordingThread = Thread {
            recordAudioLoop(audioRecord, outputFile, bufferSize, gainMultiplier)
        }.apply {
            name = "LordCallRecordThread"
            start()
        }

        return true
    }

    private fun recordAudioLoop(
        record: AudioRecord,
        outputFile: File,
        bufferSize: Int,
        gain: Float
    ) {
        val fos = FileOutputStream(outputFile)
        // كتابة ترويسة WAV مؤقتة
        writeWavHeader(fos, 0L, SAMPLE_RATE, 1, 16)

        var totalAudioLen = 0L
        val shortBuffer = ShortArray(bufferSize / 2)
        val byteBuffer = ByteArray(bufferSize)

        try {
            record.startRecording()
            while (isRecordingActive) {
                val readShorts = record.read(
                    shortBuffer,
                    0,
                    shortBuffer.size
                )
                if (readShorts > 0) {
                    boostPcmSamples(shortBuffer, readShorts, gain)

                    var byteIdx = 0
                    for (i in 0 until readShorts) {
                        val s = shortBuffer[i].toInt()
                        byteBuffer[byteIdx++] = (s and 0xFF).toByte()
                        byteBuffer[byteIdx++] = ((s shr 8) and 0xFF).toByte()
                    }

                    fos.write(byteBuffer, 0, byteIdx)
                    totalAudioLen += byteIdx
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {}
            record.release()
            try {
                fos.close()
            } catch (_: Exception) {}

            // تحديث ترويسة WAV بالأحجام الحقيقية
            updateWavHeader(outputFile, totalAudioLen, SAMPLE_RATE, 1, 16)
        }
    }

    /**
     * إيقاف التسجيل الحالي وتحديث السجل.
     */
    @Synchronized
    fun stopRecording(context: Context): CallRecordItem? {
        if (!isRecordingActive) return null

        isRecordingActive = false
        try {
            recordingThread?.join(1500L)
        } catch (_: Exception) {}
        recordingThread = null

        val item = currentItem ?: return null
        val durationMs = System.currentTimeMillis() - recordingStartTime
        item.durationSeconds = (durationMs / 1000L).coerceAtLeast(1L)

        val file = File(item.filePath)
        if (file.exists()) {
            item.fileSize = file.length()
            saveRecordToHistory(context, item)
        } else {
            currentItem = null
            return null
        }

        currentItem = null
        return item
    }

    /**
     * تضخيم عينات الصوت البرمجية مع حماية ضد التشويش والقص (Clipping).
     */
    fun boostPcmSamples(
        buffer: ShortArray,
        count: Int,
        multiplier: Float
    ) {
        if (multiplier == 1.0f) return

        for (i in 0 until count) {
            val sample = buffer[i].toFloat() * multiplier
            buffer[i] = sample.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    fun getRecordings(context: Context): List<CallRecordItem> {
        val indexFile = File(getRecordingsDir(context), INDEX_FILE_NAME)
        if (!indexFile.exists()) return emptyList()

        val list = mutableListOf<CallRecordItem>()
        try {
            val text = indexFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(text)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val filePath = obj.getString("filePath")
                if (File(filePath).exists()) {
                    list.add(
                        CallRecordItem(
                            id = obj.getString("id"),
                            phoneNumber = obj.getString("phoneNumber"),
                            callerName = obj.optString("callerName", ""),
                            timestamp = obj.getLong("timestamp"),
                            durationSeconds = obj.getLong("durationSeconds"),
                            filePath = filePath,
                            fileSize = obj.optLong("fileSize", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        list.sortByDescending { it.timestamp }
        return list
    }

    @Synchronized
    fun deleteRecording(context: Context, id: String): Boolean {
        val all = getRecordings(context).toMutableList()
        val target = all.find { it.id == id } ?: return false

        val file = File(target.filePath)
        if (file.exists()) {
            file.delete()
        }

        all.removeAll { it.id == id }
        saveAllRecordings(context, all)
        return true
    }

    fun getRecordingsForNumber(
        context: Context,
        phoneNumber: String
    ): List<CallRecordItem> {
        val clean = phoneNumber.replace(Regex("[^0-9+]"), "")
        return getRecordings(context).filter {
            it.phoneNumber.replace(Regex("[^0-9+]"), "") == clean
        }
    }

    private fun saveRecordToHistory(context: Context, item: CallRecordItem) {
        val all = getRecordings(context).toMutableList()
        all.removeAll { it.id == item.id }
        all.add(0, item)
        saveAllRecordings(context, all)
    }

    private fun saveAllRecordings(
        context: Context,
        items: List<CallRecordItem>
    ) {
        val dir = getRecordingsDir(context)
        val indexFile = File(dir, INDEX_FILE_NAME)
        val jsonArray = JSONArray()

        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("phoneNumber", item.phoneNumber)
                put("callerName", item.callerName)
                put("timestamp", item.timestamp)
                put("durationSeconds", item.durationSeconds)
                put("filePath", item.filePath)
                put("fileSize", item.fileSize)
            }
            jsonArray.put(obj)
        }

        try {
            indexFile.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun getRecordingsDir(context: Context): File {
        val external = context.getExternalFilesDir("call_recordings")
        return external ?: File(context.filesDir, "call_recordings")
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        out.write(header, 0, 44)
    }

    private fun updateWavHeader(
        file: File,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        if (!file.exists()) return
        val raf = RandomAccessFile(file, "rw")
        try {
            val totalDataLen = totalAudioLen + 36
            raf.seek(4)
            raf.write(
                byteArrayOf(
                    (totalDataLen and 0xff).toByte(),
                    ((totalDataLen shr 8) and 0xff).toByte(),
                    ((totalDataLen shr 16) and 0xff).toByte(),
                    ((totalDataLen shr 24) and 0xff).toByte()
                )
            )
            raf.seek(40)
            raf.write(
                byteArrayOf(
                    (totalAudioLen and 0xff).toByte(),
                    ((totalAudioLen shr 8) and 0xff).toByte(),
                    ((totalAudioLen shr 16) and 0xff).toByte(),
                    ((totalAudioLen shr 24) and 0xff).toByte()
                )
            )
        } catch (_: Exception) {
        } finally {
            raf.close()
        }
    }
}
