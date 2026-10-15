package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * مدير تنزيل الأصوات المدمجة في الخلفية مع دعم الاستئناف والإلغاء (ع٣٠-7).
 */
object VoiceDownloadManager {

    private const val TAG = "VoiceDownloadManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val percent: Int,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : DownloadState()
        object Success : DownloadState()
        data class Error(val error: DownloadError) : DownloadState()
        object Cancelled : DownloadState()
    }

    fun interface DownloadListener {
        fun onStateChanged(voiceId: String, state: DownloadState)
    }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeStates = ConcurrentHashMap<String, DownloadState>()
    private val voiceTitles = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArraySet<DownloadListener>()

    /**
     * آخر نسبة أُعلنت لكل صوت — يمنع عاصفة التحديثات: نداءُ التقدّم
     * يقع لكل قطعة 32KB (~1400 لملف 45MB)، وبلا خنقٍ تُغرق الخدمة
     * الأمامية وSystemUI فتتجمّد الواجهة ويتأخّر التنزيل.
     */
    private val lastProgress = ConcurrentHashMap<String, Int>()

    fun addListener(listener: DownloadListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: DownloadListener) {
        listeners.remove(listener)
    }

    fun isDownloading(voiceId: String): Boolean =
        activeJobs.containsKey(voiceId)

    fun getState(voiceId: String): DownloadState =
        activeStates[voiceId] ?: DownloadState.Idle

    fun getVoiceTitle(voiceId: String): String =
        voiceTitles[voiceId] ?: voiceId

    fun hasActiveDownloads(): Boolean = activeJobs.isNotEmpty()

    /**
     * فحص وجود تنزيل جزئي متبقٍ وحجمه بالبايت.
     */
    fun getPartialBytes(context: Context, voiceId: String): Long {
        val dir = DownloadHttpHelper.getDownloadsDir(context)
        val file = File(dir, "vocalizer_${voiceId}.zip.part")
        return if (file.exists() && file.length() > 0) file.length() else 0L
    }

    /**
     * حذف الملفات المؤقتة الجزئية لصوت معين.
     */
    fun deletePartial(context: Context, voiceId: String) {
        val dir = DownloadHttpHelper.getDownloadsDir(context)
        val f = File(dir, "vocalizer_${voiceId}.zip.part")
        if (f.exists()) f.delete()
    }

    private fun updateState(voiceId: String, state: DownloadState) {
        activeStates[voiceId] = state
        listeners.forEach {
            try {
                it.onStateChanged(voiceId, state)
            } catch (t: Throwable) {
                Log.w(TAG, "Listener error for $voiceId", t)
            }
        }
    }

    /**
     * ينشر تقدّمًا مخفّفًا: لا شيء إن لم تتغيّر النسبة الصحيحة. هكذا
     * ينزل عدد إشعارات الخدمة ورسائل الواجهة من ~1400 إلى ≤100 لكل تنزيل.
     */
    internal fun publishProgress(
        context: Context,
        voiceId: String,
        percent: Int
    ) {
        if (lastProgress[voiceId] == percent) return
        lastProgress[voiceId] = percent
        updateState(
            voiceId,
            DownloadState.Downloading(percent, 0L, -1L)
        )
        notifyServiceProgress(context, voiceId, percent)
    }

    /**
     * بدء تنزيل صوت Vocalizer في الخلفية عبر الخدمة الأمامية.
     */
    fun startVocalizerDownload(
        context: Context,
        item: VocalizerCatalogItem
    ) {
        if (isDownloading(item.id)) return
        val appContext = context.applicationContext
        voiceTitles[item.id] = item.displayNameAr

        ensureServiceRunning(appContext)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            lastProgress[item.id] = 0
            updateState(
                item.id,
                DownloadState.Downloading(0, 0L, -1L)
            )
            notifyServiceProgress(appContext, item.id, 0)

            val result = VocalizerDownloader.downloadVoice(appContext, item) {
                percent ->
                publishProgress(appContext, item.id, percent)
            }

            activeJobs.remove(item.id)
            lastProgress.remove(item.id)

            if (result.isSuccess) {
                DownloadLog.clearError(item.id)
                updateState(item.id, DownloadState.Success)
            } else {
                val ex = result.exceptionOrNull()
                val err = if (ex is DownloadError) ex else {
                    DownloadError(
                        DownloadError.NETWORK,
                        ex?.message ?: "Download failed"
                    )
                }
                DownloadLog.recordError(
                    voiceId = item.id,
                    engineId = VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
                    errorCode = err.code,
                    errorMessage = err.details
                )
                updateState(item.id, DownloadState.Error(err))
            }
            checkAllCompleted(appContext)
        }
        // التسجيل قبل الإطلاق يمنع سباقًا: لو انتهى الـ job فورًا (فشل
        // سريع) لأزال نفسه من activeJobs قبل تسجيله فيبقى معلّقًا.
        activeJobs[item.id] = job
        job.start()
    }

    /**
     * إلغاء تنزيل جاري لصوت معين.
     */
    fun cancelDownload(context: Context, voiceId: String) {
        val job = activeJobs.remove(voiceId)
        if (job != null) {
            job.cancel()
            updateState(voiceId, DownloadState.Cancelled)
        }
        // الإلغاء يعني التخلّي عن الجزء الناقص أيضاً، فلا يبقى
        // معلّقاً في الواجهة كـ«استئناف التنزيل».
        deletePartial(context, voiceId)
        checkAllCompleted(context.applicationContext)
    }

    private fun ensureServiceRunning(context: Context) {
        try {
            val intent = Intent(
                context,
                VoiceDownloadService::class.java
            ).apply {
                action = VoiceDownloadService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed starting VoiceDownloadService", t)
        }
    }

    private fun notifyServiceProgress(
        context: Context,
        voiceId: String,
        percent: Int
    ) {
        try {
            val intent = Intent(
                context,
                VoiceDownloadService::class.java
            ).apply {
                action = VoiceDownloadService.ACTION_UPDATE_PROGRESS
                putExtra(VoiceDownloadService.EXTRA_VOICE_ID, voiceId)
                putExtra(VoiceDownloadService.EXTRA_PERCENT, percent)
            }
            context.startService(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed notifying service progress", t)
        }
    }

    private fun checkAllCompleted(context: Context) {
        if (!hasActiveDownloads()) {
            try {
                val intent = Intent(
                    context,
                    VoiceDownloadService::class.java
                ).apply {
                    action = VoiceDownloadService.ACTION_STOP
                }
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed stopping VoiceDownloadService", t)
            }
        }
    }
}
