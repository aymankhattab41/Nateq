package com.aymankhattab.nateq.core.audio.providers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.nav.SettingsOpenRegistry

/**
 * خدمة أمامية بنوع dataSync لتنزيل ملفات ونماذج الأصوات المدمجة
 * دون أن تتوقف عند مغادرة الشاشة (ع٣٠-7).
 */
class VoiceDownloadService : Service() {

    companion object {
        private const val TAG = "VoiceDownloadService"
        const val CHANNEL_ID = "voice_download_channel"
        const val NOTIFICATION_ID = 2002

        const val ACTION_START =
            "com.aymankhattab.nateq.action.VOICE_DOWNLOAD_START"
        const val ACTION_UPDATE_PROGRESS =
            "com.aymankhattab.nateq.action.VOICE_DOWNLOAD_PROGRESS"
        const val ACTION_CANCEL =
            "com.aymankhattab.nateq.action.VOICE_DOWNLOAD_CANCEL"
        const val ACTION_STOP =
            "com.aymankhattab.nateq.action.VOICE_DOWNLOAD_STOP"

        const val EXTRA_VOICE_ID = "extra_voice_id"
        const val EXTRA_PERCENT = "extra_percent"
    }

    private var lastVoiceId: String = ""
    private var lastPercent: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                startForegroundWithNotification(
                    buildNotification(lastVoiceId, lastPercent)
                )
            }
            ACTION_UPDATE_PROGRESS -> {
                val voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: ""
                val percent = intent.getIntExtra(EXTRA_PERCENT, 0)
                lastVoiceId = voiceId
                lastPercent = percent
                updateNotification(voiceId, percent)
            }
            ACTION_CANCEL -> {
                val voiceId = intent.getStringExtra(EXTRA_VOICE_ID) ?: ""
                if (voiceId.isNotBlank()) {
                    VoiceDownloadManager.cancelDownload(this, voiceId)
                }
            }
            ACTION_STOP -> {
                if (!VoiceDownloadManager.hasActiveDownloads()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                ) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun updateNotification(voiceId: String, percent: Int) {
        val notification = buildNotification(voiceId, percent)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(
                    R.string.voice_download_channel_description
                )
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        voiceId: String,
        percent: Int
    ): Notification {
        val openSettings = PendingIntent.getActivity(
            this,
            0,
            SettingsOpenRegistry.resolve(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(
            this,
            VoiceDownloadService::class.java
        ).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_VOICE_ID, voiceId)
        }
        val cancelPending = PendingIntent.getService(
            this,
            voiceId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val voiceTitle = if (voiceId.isNotBlank()) {
            VoiceDownloadManager.getVoiceTitle(voiceId)
        } else {
            getString(R.string.voice_download_notification_title)
        }

        val contentText = getString(
            R.string.voice_download_notification_text,
            voiceTitle,
            percent
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(R.string.voice_download_notification_title)
            )
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_number_reading)
            .setContentIntent(openSettings)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setProgress(100, percent, false)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )

        if (voiceId.isNotBlank()) {
            builder.addAction(
                0,
                getString(R.string.voice_download_action_cancel),
                cancelPending
            )
        }

        return builder.build()
    }
}
