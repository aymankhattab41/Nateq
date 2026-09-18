package com.aymankhattab.nateq.core.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * قفل نطق عابر بين عمليتَي النطق والتخليق:
 *
 * محركُ التخليق (`NateqTtsService` في `:tts`) يرفع العلم عبر [setSpeaking]
 * عند بدء `onSynthesizeText` ويخفضه في `finally`؛ متحدثُ الإعلانات
 * (`AnnouncementSpeaker` في العملية الرئيسية) يستجوب [isSpeaking] قبل
 * النطق ليؤجّل إعلانه حتى يهدأ القارئ — فيتسلسل الصوتان بدل تراكبها.
 *
 * يمرّ كل التفاعل عبر [SettingsChangeProvider] (مسار `/speaking`) فيراه
 * العمليتان معاً، ويُبثّ انخفاضُه للـ [android.database.ContentObserver]
 * فوراً. فشل الاتصال بالمزوّد = لا قفل (عودة آمنة لا تعلّق الإعلانات).
 */
object SpeechLock {

    private const val SPEAKING_COLUMN = "speaking"
    private const val SPEAKING_TRUE = 1
    private const val SPEAKING_FALSE = 0

    private val uri: Uri
        get() = SettingsChangeProvider.speakingUri()

    /** رفع/خفض علم النطق عبر [SettingsChangeProvider]. أي فشل يُسجَّل
     *  بهدوء ولا يُرمى — لا يجوز أن يسقط التخليق بسبب خلل في البنية
     *  الداعمة (المزوّد قد يكون غير متاح في أول لحظات عملية :tts). */
    fun setSpeaking(context: Context, speaking: Boolean) {
        try {
            val value = if (speaking) SPEAKING_TRUE else SPEAKING_FALSE
            val values = ContentValues().apply {
                put(SPEAKING_COLUMN, value)
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            android.util.Log.w(
                "NATEQ_TTS", "SpeechLock update failed", t
            )
        }
    }

    /** هل التخليق جارٍ حالياً؟ فشل القراءة = لا قفل (عودة آمنة). */
    fun isSpeaking(context: Context): Boolean {
        return try {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    uri, arrayOf(SPEAKING_COLUMN), null, null, null
                )
                cursor != null && cursor.moveToFirst() &&
                    cursor.getInt(0) == SPEAKING_TRUE
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
