package com.aymankhattab.nateq.core.audio.phone

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * بيانات تسجيل المكالمة الصوتية (متوافقة مع MediaStore وملفات النظام).
 */
data class CallRecordItem(
    val id: String,
    val phoneNumber: String,
    val callerName: String,
    val timestamp: Long,
    var durationSeconds: Long,
    val filePath: String,
    var fileSize: Long,
    val uriString: String? = null,
    var isSilent: Boolean = false
)

/**
 * حالات دورة حياة جلسة تسجيل المكالمة الصريحة.
 */
enum class RecordingState {
    IDLE,
    RECORDING,
    STOPPING
}

/**
 * مدير تسجيل المكالمات الصوتي وتضخيم الصوت البرمجي للمكفوفين.
 */
object CallRecordManager {

    private const val TAG = "CallRecordManager"
    private const val INDEX_FILE_NAME = "call_recordings_index.json"
    const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private val SOURCES_TO_TRY = listOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC
    )

    private val stateLock = Any()
    private val indexLock = Any()

    @Volatile
    private var recordingState = RecordingState.IDLE

    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    private var recordingThread: Thread? = null
    private var currentItem: CallRecordItem? = null
    private var currentTarget: RecordingTarget? = null
    private var currentCall: Any? = null
    private var recordingStartTime = 0L

    /**
     * نتائج محاولة حذف التسجيل الصوتي.
     */
    sealed class DeleteRecordResult {
        object Success : DeleteRecordResult()
        object NotFound : DeleteRecordResult()
        data class PermissionDenied(val message: String) : DeleteRecordResult()
        data class RequiresConsent(
            val intentSender: android.content.IntentSender
        ) : DeleteRecordResult()
        data class Failed(val reason: String) : DeleteRecordResult()
    }

    fun isRecording(): Boolean = recordingState == RecordingState.RECORDING

    fun getRecordingState(): RecordingState = recordingState

    fun getCurrentItem(): CallRecordItem? = currentItem

    fun getCurrentCall(): Any? = currentCall

    fun getRecordingFolderDisplayName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Recordings/Lord TTS"
        } else {
            "Music/Lord TTS"
        }

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
     * بدء تسجيل المكالمة الحالية بجلسة واحدة صريحة وخدمة أمامية.
     */
    fun startRecording(
        context: Context,
        phoneNumber: String,
        callerName: String,
        call: Any? = null
    ): Boolean {
        val thread: Thread
        synchronized(stateLock) {
            if (recordingState != RecordingState.IDLE) {
                Log.w(
                    TAG,
                    "Cannot start recording: current state is $recordingState"
                )
                return false
            }
            if (recordingThread?.isAlive == true) {
                Log.w(
                    TAG,
                    "Cannot start recording: previous thread still active"
                )
                return false
            }

            migrateLegacyRecordings(context)

            val id = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis()
            val safeNumber = phoneNumber.replace(Regex("[^0-9+]"), "")

            val target = createRecordingTarget(context, safeNumber, timestamp)
            if (target == null) {
                Log.e(TAG, "Failed to create recording destination target")
                return false
            }

            val item = CallRecordItem(
                id = id,
                phoneNumber = phoneNumber,
                callerName = callerName,
                timestamp = timestamp,
                durationSeconds = 0L,
                filePath = target.filePath,
                fileSize = 0L,
                uriString = target.uriString,
                isSilent = false
            )

            currentItem = item
            currentTarget = target
            currentCall = call
            recordingStartTime = timestamp
            recordingState = RecordingState.RECORDING

            CallRecordingForegroundService.start(context)

            val repo = SettingsRepository.create(context)
            val boost = repo.isPhoneRecordBoostVolumeEnabled()
            val gain = if (boost) 2.5f else 1.0f

            thread = Thread {
                recordAudioLoop(context, target, item, gain)
            }.apply {
                name = "LordCallRecordThread"
            }
            recordingThread = thread
            thread.start()
        }
        return true
    }

    private data class RecordingTarget(
        val uri: Uri?,
        val file: File?,
        val tempFile: File,
        val uriString: String,
        val filePath: String
    )

    private fun createRecordingTarget(
        context: Context,
        safeNumber: String,
        timestamp: Long
    ): RecordingTarget? {
        val nameNumber = if (safeNumber.isEmpty()) "غير_معروف" else safeNumber
        val fileName = "مكالمة_${nameNumber}_$timestamp.wav"
        val tempFile = File(context.cacheDir, "rec_tmp_$timestamp.wav")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Recordings/Lord TTS/"
            } else {
                "Music/Lord TTS/"
            }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = try {
                context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    values
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting into MediaStore: ${e.message}")
                null
            }

            if (uri != null) {
                return RecordingTarget(
                    uri = uri,
                    file = null,
                    tempFile = tempFile,
                    uriString = uri.toString(),
                    filePath = ""
                )
            }
        }

        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC
            ),
            "Lord TTS"
        )
        if (!publicDir.exists()) {
            publicDir.mkdirs()
        }
        val targetFile = File(publicDir, fileName)
        return RecordingTarget(
            uri = null,
            file = targetFile,
            tempFile = tempFile,
            uriString = Uri.fromFile(targetFile).toString(),
            filePath = targetFile.absolutePath
        )
    }

    private fun recordAudioLoop(
        context: Context,
        target: RecordingTarget,
        item: CallRecordItem,
        gain: Float
    ) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(4096)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

        val raf = try {
            RandomAccessFile(target.tempFile, "rw")
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open RandomAccessFile: ${e.message}")
            null
        }

        if (raf == null) {
            synchronized(stateLock) {
                recordingState = RecordingState.IDLE
                currentItem = null
                currentTarget = null
                currentCall = null
                recordingThread = null
            }
            CallRecordingForegroundService.stop(context)
            return
        }

        val initialHeader = buildWavHeader(0L, SAMPLE_RATE, 1, 16)
        try {
            raf.seek(0)
            raf.write(initialHeader)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "فشل كتابة رأس wav الأولي: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }

        val shortBuffer = ShortArray(bufferSize / 2)
        val byteBuffer = ByteArray(bufferSize)
        var totalAudioLen = 0L
        var lastHeaderUpdate = System.currentTimeMillis()
        var activeRecord: AudioRecord? = null
        var sourceIndex = 0
        var sourceConfirmed = false
        var checkedSamples = 0L
        var sumSquares = 0.0
        var consecutiveZeros = 0
        var consecutiveErrors = 0

        try {
            while (recordingState == RecordingState.RECORDING) {
                if (activeRecord == null) {
                    if (sourceIndex >= SOURCES_TO_TRY.size) {
                        Log.e(TAG, "All audio sources failed or silent")
                        item.isSilent = true
                        speakAudioBlockedWarning(context)
                        break
                    }
                    val src = SOURCES_TO_TRY[sourceIndex]
                    Log.i(TAG, "Trying audio source: $src")
                    activeRecord = try {
                        AudioRecord(
                            src,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSize
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to create source $src: ${e.message}")
                        null
                    }

                    if (activeRecord == null ||
                        activeRecord.state != AudioRecord.STATE_INITIALIZED
                    ) {
                        activeRecord?.release()
                        activeRecord = null
                        sourceIndex++
                        continue
                    }

                    try {
                        activeRecord.startRecording()
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot start source $src: ${e.message}")
                        activeRecord.release()
                        activeRecord = null
                        sourceIndex++
                        continue
                    }

                    synchronized(stateLock) {
                        activeAudioRecord = activeRecord
                    }

                    checkedSamples = 0L
                    sumSquares = 0.0
                    sourceConfirmed = false
                    consecutiveZeros = 0
                    consecutiveErrors = 0
                }

                val readCount = activeRecord.read(
                    shortBuffer,
                    0,
                    shortBuffer.size
                )

                if (recordingState != RecordingState.RECORDING) {
                    break
                }

                if (readCount > 0) {
                    consecutiveZeros = 0
                    consecutiveErrors = 0

                    if (!sourceConfirmed) {
                        for (i in 0 until readCount) {
                            val v = shortBuffer[i].toDouble()
                            sumSquares += v * v
                        }
                        checkedSamples += readCount
                        if (checkedSamples >= SAMPLE_RATE * 3) {
                            val rms = sqrt(sumSquares / checkedSamples)
                            Log.i(
                                TAG,
                                "RMS for source: $rms (verifies mic input only)"
                            )
                            if (rms < 1.0) {
                                Log.w(
                                    TAG,
                                    "Source returned silence " +
                                        "(RMS=$rms), fallback"
                                )
                                try {
                                    activeRecord.stop()
                                } catch (e: Exception) {
                                    Log.w(
                                        TAG,
                                        "فشل إيقاف التسجيل الصامت: " +
                                            "${e.javaClass.simpleName}: " +
                                            "${e.message}"
                                    )
                                }
                                try {
                                    activeRecord.release()
                                } catch (e: Exception) {
                                    Log.w(
                                        TAG,
                                        "فشل تحرير مسجل الصوت الصامت: " +
                                            "${e.javaClass.simpleName}: " +
                                            "${e.message}"
                                    )
                                }
                                synchronized(stateLock) {
                                    activeAudioRecord = null
                                }
                                activeRecord = null
                                sourceIndex++
                                continue
                            } else {
                                sourceConfirmed = true
                            }
                        }
                    }

                    boostPcmSamples(shortBuffer, readCount, gain)

                    var byteIdx = 0
                    for (i in 0 until readCount) {
                        val s = shortBuffer[i].toInt()
                        byteBuffer[byteIdx++] = (s and 0xFF).toByte()
                        byteBuffer[byteIdx++] = ((s shr 8) and 0xFF).toByte()
                    }

                    raf.write(byteBuffer, 0, byteIdx)
                    totalAudioLen += byteIdx

                    val now = System.currentTimeMillis()
                    if (now - lastHeaderUpdate >= 2000L) {
                        val currentPos = raf.filePointer
                        raf.seek(0)
                        raf.write(
                            buildWavHeader(totalAudioLen, SAMPLE_RATE, 1, 16)
                        )
                        raf.seek(currentPos)
                        lastHeaderUpdate = now
                    }
                } else if (readCount == 0) {
                    consecutiveZeros++
                    if (consecutiveZeros > 5) {
                        try {
                            Thread.sleep(20L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                    if (consecutiveZeros >= 50) {
                        Log.w(
                            TAG,
                            "Repeated zero reads from AudioRecord, breaking"
                        )
                        break
                    }
                } else {
                    Log.w(
                        TAG,
                        "AudioRecord read returned error code: $readCount"
                    )
                    if (readCount == AudioRecord.ERROR_DEAD_OBJECT) {
                        Log.e(
                            TAG,
                            "AudioRecord ERROR_DEAD_OBJECT, aborting source"
                        )
                        break
                    }
                    consecutiveErrors++
                    if (consecutiveErrors >= 5) {
                        Log.e(
                            TAG,
                            "Exceeded max consecutive errors: $readCount"
                        )
                        break
                    }
                    try {
                        Thread.sleep(20L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during recording loop: ${e.message}")
        } finally {
            synchronized(stateLock) {
                activeAudioRecord = null
            }
            try {
                activeRecord?.stop()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "فشل إيقاف مسجل الصوت في finally: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
            try {
                activeRecord?.release()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "فشل تحرير مسجل الصوت في finally: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }

            try {
                if (totalAudioLen == 0L && raf.length() > 44L) {
                    totalAudioLen = raf.length() - 44L
                }
                raf.seek(0)
                raf.write(buildWavHeader(totalAudioLen, SAMPLE_RATE, 1, 16))
                raf.close()
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "فشل إنهاء كتابة ملف التسجيل: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }

            var copySucceeded = false
            try {
                if (target.tempFile.exists() &&
                    target.tempFile.length() > 44L
                ) {
                    if (target.uri != null) {
                        context.contentResolver.openOutputStream(
                            target.uri
                        )?.use { outStream ->
                            FileInputStream(target.tempFile).use { inpStream ->
                                inpStream.copyTo(outStream)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val updateVals = ContentValues().apply {
                                put(MediaStore.Audio.Media.IS_PENDING, 0)
                            }
                            context.contentResolver.update(
                                target.uri,
                                updateVals,
                                null,
                                null
                            )
                        }
                        copySucceeded = true
                    } else if (target.file != null) {
                        target.tempFile.copyTo(target.file, overwrite = true)
                        copySucceeded = true
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Error copying temp record to target: ${e.message}"
                )
            } finally {
                try {
                    target.tempFile.delete()
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "فشل حذف ملف التسجيل المؤقت: " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }

            var actualSize = 0L
            if (copySucceeded) {
                if (target.file != null && target.file.exists()) {
                    actualSize = target.file.length()
                } else if (target.uri != null) {
                    try {
                        context.contentResolver.openFileDescriptor(
                            target.uri,
                            "r"
                        )?.use {
                            actualSize = it.statSize
                        }
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "فشل قراءة حجم ملف التسجيل: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }

            if (copySucceeded && actualSize > 44L) {
                val durationMs =
                    System.currentTimeMillis() - recordingStartTime
                item.durationSeconds =
                    (durationMs / 1000L).coerceAtLeast(1L)
                item.fileSize = actualSize
                saveRecordToHistory(context, item)
                Log.i(
                    TAG,
                    "Recording saved: ${item.id}, size=$actualSize"
                )
            } else {
                Log.w(
                    TAG,
                    "Recording failed or empty: " +
                        "copySucceeded=$copySucceeded, " +
                        "size=$actualSize"
                )
                if (target.uri != null) {
                    try {
                        context.contentResolver.delete(target.uri, null, null)
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "فشل حذف التسجيل الفارغ من الوسائط: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                } else if (target.file != null && target.file.exists()) {
                    try {
                        target.file.delete()
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "فشل حذف ملف التسجيل الفارغ: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
            }

            CallRecordingForegroundService.stop(context)

            try {
                val appCtx = context.applicationContext
                CoroutineScope(Dispatchers.Main).launch {
                    com.aymankhattab.nateq.core.audio.accessibility
                        .TtsSettingsAutomation
                        .checkAndRunPendingSwitch(appCtx)
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "فشل فحص التبديل المؤجل في نهاية التسجيل: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }

            synchronized(stateLock) {
                recordingState = RecordingState.IDLE
                currentItem = null
                currentTarget = null
                currentCall = null
                recordingThread = null
            }
        }
    }

    private fun speakAudioBlockedWarning(context: Context) {
        val warning =
            "لا يسمح النظام بتسجيل صوت المكالمة، " +
                "فعّل مكبر الصوت ثم أعد المحاولة"
        try {
            val audioService =
                Class.forName(
                    "com.aymankhattab.nateq.core.audio.announcement" +
                        ".AnnouncementSpeaker"
                )
            val method = audioService.getMethod(
                "speakImmediate",
                Context::class.java,
                String::class.java
            )
            method.invoke(null, context, warning)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "فشل نطق تحذير حظر الصوت عبر الانعكاس: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * إيقاف التسجيل الحالي فوراً وفك قراءة AudioRecord دون تجميد main.
     */
    fun stopRecording(context: Context): CallRecordItem? {
        val item: CallRecordItem?
        val threadToWait: Thread?

        synchronized(stateLock) {
            if (recordingState != RecordingState.RECORDING) {
                return null
            }
            recordingState = RecordingState.STOPPING
            item = currentItem
            threadToWait = recordingThread

            try {
                activeAudioRecord?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping active AudioRecord", e)
            }
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                threadToWait?.join(5000L)
            } catch (e: InterruptedException) {
                Log.w(
                    TAG,
                    "مقاطعة انتظار خيط التسجيل: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        return item
    }

    fun calculateRms(buffer: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

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
        synchronized(indexLock) {
            try {
                val text = indexFile.readText(Charsets.UTF_8)
                val jsonArray = JSONArray(text)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val uriString = obj.optString("uriString", "")
                    val filePath = obj.optString("filePath", "")
                    val isSilent = obj.optBoolean("isSilent", false)

                    var exists = false
                    if (uriString.isNotEmpty()) {
                        exists = try {
                            context.contentResolver.openFileDescriptor(
                                Uri.parse(uriString),
                                "r"
                            )?.use { true } ?: false
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (!exists && filePath.isNotEmpty()) {
                        exists = File(filePath).exists()
                    }

                    if (exists) {
                        list.add(
                            CallRecordItem(
                                id = obj.getString("id"),
                                phoneNumber = obj.getString("phoneNumber"),
                                callerName = obj.optString("callerName", ""),
                                timestamp = obj.getLong("timestamp"),
                                durationSeconds =
                                    obj.getLong("durationSeconds"),
                                filePath = filePath,
                                fileSize = obj.optLong("fileSize", 0L),
                                uriString = if (uriString.isNotEmpty()) {
                                    uriString
                                } else {
                                    null
                                },
                                isSilent = isSilent
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "فشل قراءة سجل التسجيلات من الملف: " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        list.sortByDescending { it.timestamp }
        return list
    }

    fun deleteRecording(context: Context, id: String): Boolean {
        return deleteRecordingWithResult(context, id) ==
            DeleteRecordResult.Success
    }

    /**
     * حذف التسجيل والتحقق من النتيجة الفعلية لحذف الملف من وسيط التخزين
     * قبل مسح القيد من الفهرس، والتعامل مع طلب الموافقة من المزود.
     */
    fun deleteRecordingWithResult(
        context: Context,
        id: String
    ): DeleteRecordResult {
        synchronized(indexLock) {
            val all = getRecordings(context).toMutableList()
            val target = all.find { it.id == id }
                ?: return DeleteRecordResult.NotFound

            var fileDeletedOrAbsent = false

            if (!target.uriString.isNullOrEmpty()) {
                val uri = Uri.parse(target.uriString)
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        fileDeletedOrAbsent = true
                    } else {
                        val exists = try {
                            context.contentResolver.openFileDescriptor(
                                uri,
                                "r"
                            )?.use { true } ?: false
                        } catch (_: Exception) {
                            false
                        }
                        if (!exists) {
                            fileDeletedOrAbsent = true
                        } else {
                            return DeleteRecordResult.Failed(
                                "تعذر حذف الملف: المزود أعاد 0 صفوف"
                            )
                        }
                    }
                } catch (secEx: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        secEx is android.app.RecoverableSecurityException
                    ) {
                        return DeleteRecordResult.RequiresConsent(
                            secEx.userAction.actionIntent.intentSender
                        )
                    }
                    return DeleteRecordResult.PermissionDenied(
                        secEx.message ?: "إذن الحذف مرفوض"
                    )
                } catch (e: Exception) {
                    return DeleteRecordResult.Failed(
                        e.message ?: "خطأ أثناء حذف الملف"
                    )
                }
            }

            if (target.filePath.isNotEmpty()) {
                val file = File(target.filePath)
                if (file.exists()) {
                    val ok = file.delete()
                    if (ok) {
                        fileDeletedOrAbsent = true
                    } else if (!fileDeletedOrAbsent) {
                        return DeleteRecordResult.Failed(
                            "تعذر حذف ملف التسجيل من القرص"
                        )
                    }
                } else {
                    fileDeletedOrAbsent = true
                }
            }

            if (target.uriString.isNullOrEmpty() && target.filePath.isEmpty()) {
                fileDeletedOrAbsent = true
            }

            if (!fileDeletedOrAbsent) {
                return DeleteRecordResult.Failed("لم يتم حذف الملف")
            }

            all.removeAll { it.id == id }
            val indexSaved = saveAllRecordings(context, all)
            if (!indexSaved) {
                return DeleteRecordResult.Failed(
                    "تم حذف الملف ولكن فشل تحديث الفهرس"
                )
            }
            return DeleteRecordResult.Success
        }
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
        synchronized(indexLock) {
            val all = getRecordings(context).toMutableList()
            all.removeAll { it.id == item.id }
            all.add(0, item)
            saveAllRecordings(context, all)
        }
    }

    private fun saveAllRecordings(
        context: Context,
        items: List<CallRecordItem>
    ): Boolean {
        synchronized(indexLock) {
            val dir = getRecordingsDir(context)
            val indexFile = File(dir, INDEX_FILE_NAME)
            val tempFile = File(dir, "$INDEX_FILE_NAME.tmp")
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
                    put("uriString", item.uriString ?: "")
                    put("isSilent", item.isSilent)
                }
                jsonArray.put(obj)
            }

            return try {
                tempFile.writeText(jsonArray.toString(), Charsets.UTF_8)
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (indexFile.exists()) {
                        indexFile.delete()
                    }
                    if (!tempFile.renameTo(indexFile)) {
                        tempFile.copyTo(indexFile, overwrite = true)
                        tempFile.delete()
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed atomic index save: ${e.message}")
                try {
                    tempFile.delete()
                } catch (delEx: Exception) {
                    Log.w(
                        TAG,
                        "فشل حذف الملف المؤقت لفهرس التسجيلات: " +
                            "${delEx.javaClass.simpleName}: ${delEx.message}"
                    )
                }
                false
            }
        }
    }

    fun getRecordingsDir(context: Context): File {
        val external = context.getExternalFilesDir("call_recordings")
        val dir = external ?: File(context.filesDir, "call_recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * ترحيل التسجيلات القديمة المخزنة في getExternalFilesDir إلى MediaStore.
     */
    fun migrateLegacyRecordings(context: Context) {
        val ext = context.getExternalFilesDir("call_recordings")
        val internal = File(context.filesDir, "call_recordings")
        val candidateDirs = listOfNotNull(ext, internal).distinct()

        for (legacyDir in candidateDirs) {
            val files = legacyDir.listFiles { f ->
                f.extension.equals("wav", ignoreCase = true)
            } ?: continue

            for (oldFile in files) {
                try {
                    val target = createRecordingTarget(
                        context,
                        "قديم",
                        oldFile.lastModified()
                    ) ?: continue

                    val outStream = if (target.uri != null) {
                        context.contentResolver.openOutputStream(target.uri)
                    } else if (target.file != null) {
                        FileOutputStream(target.file)
                    } else {
                        null
                    }

                    if (outStream != null) {
                        FileInputStream(oldFile).use { input ->
                            outStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            target.uri != null
                        ) {
                            val vals = ContentValues().apply {
                                put(MediaStore.Audio.Media.IS_PENDING, 0)
                            }
                            context.contentResolver.update(
                                target.uri,
                                vals,
                                null,
                                null
                            )
                        }
                        val durSec =
                            (oldFile.length() / (SAMPLE_RATE * 2))
                                .coerceAtLeast(1L)
                        val migratedItem = CallRecordItem(
                            id = UUID.randomUUID().toString(),
                            phoneNumber = "غير معروف",
                            callerName = "تسجيل قديم",
                            timestamp = oldFile.lastModified(),
                            durationSeconds = durSec,
                            filePath = target.filePath,
                            fileSize = oldFile.length(),
                            uriString = target.uriString
                        )
                        saveRecordToHistory(context, migratedItem)
                        oldFile.delete()
                    }
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Legacy migration error for ${oldFile.name}: " +
                            "${e.message}"
                    )
                }
            }
        }
    }

    fun buildWavHeader(
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
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

        return header
    }
}
