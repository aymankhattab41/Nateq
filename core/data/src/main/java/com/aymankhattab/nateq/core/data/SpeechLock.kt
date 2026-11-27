package com.aymankhattab.nateq.core.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock

/**
 * قفل نطق عابر بين عمليتَي النطق والتخليق:
 *
 * محركُ التخليق (`NateqTtsService` في `:tts`) يرفع العلم عبر [setSpeaking]
 * عند بدء `onSynthesizeText` ويخفضه في `finally`؛ متحدثُ الإعلانات
 * (`AnnouncementSpeaker` في العملية الرئيسية) يستجوب [isSpeaking] قبل
 * النطق ليؤجّل إعلانه حتى يهدأ القارئ — فيتسلسل الصوتان بدل تراكبها.
 *
 * بند 28: يحمل العلم ختماً زمنياً وعمر حياة نبضة قلب (Heartbeat) قصير؛
 * إن سقطت العملية أو تخلّفت النبضات يُعتبر ساقطاً ويُنطق الإعلان فوراً.
 */
object SpeechLock {

    const val SPEAKING_COLUMN = "speaking"
    const val TIMESTAMP_COLUMN = "timestamp"
    /** بند 28: عمر نبضة القلب الافتراضي لعلم النطق (2.5 ثانية). */
    const val HEARTBEAT_TTL_MS = 2500L

    private const val SPEAKING_TRUE = 1
    private const val SPEAKING_FALSE = 0

    private val uri: Uri
        get() = SettingsChangeProvider.speakingUri()

    /** رفع/خفض علم النطق عبر [SettingsChangeProvider] بختم زمني. */
    fun setSpeaking(
        context: Context,
        speaking: Boolean,
        timestampMs: Long = SystemClock.elapsedRealtime()
    ) {
        try {
            val value = if (speaking) SPEAKING_TRUE else SPEAKING_FALSE
            val values = ContentValues().apply {
                put(SPEAKING_COLUMN, value)
                put(TIMESTAMP_COLUMN, if (speaking) timestampMs else 0L)
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            android.util.Log.w(
                "NATEQ_TTS", "SpeechLock update failed", t
            )
        }
    }

    /** بند 28: تجديد نبضة القلب (heartbeat) لتمديد صلاحية القفل. */
    fun heartbeat(context: Context) {
        setSpeaking(context, true)
    }

    /**
     * هل التخليق جارٍ حالياً؟ يتحقق من العلم وصلاحية الختم الزمني.
     * إن انتهت المهلة يُعتبر العلم ساقطاً ويُعاد تصفيره فوراً.
     */
    fun isSpeaking(
        context: Context,
        ttlMs: Long = HEARTBEAT_TTL_MS
    ): Boolean {
        return try {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    uri,
                    arrayOf(SPEAKING_COLUMN, TIMESTAMP_COLUMN),
                    null,
                    null,
                    null
                )
                if (cursor != null && cursor.moveToFirst()) {
                    val flag = cursor.getInt(0) == SPEAKING_TRUE
                    if (!flag) return false
                    val ts = if (cursor.columnCount > 1) {
                        cursor.getLong(1)
                    } else {
                        0L
                    }
                    if (ts > 0L) {
                        val age = SystemClock.elapsedRealtime() - ts
                        if (age > ttlMs) {
                            // العلم عالق دون تجديد نبضة: يسقط فوراً
                            setSpeaking(context, false)
                            return false
                        }
                    }
                    true
                } else {
                    false
                }
            } finally {
                cursor?.close()
            }
        } catch (t: Throwable) {
            android.util.Log.w(
                "NATEQ_TTS", "SpeechLock query failed", t
            )
            false
        }
    }
}
