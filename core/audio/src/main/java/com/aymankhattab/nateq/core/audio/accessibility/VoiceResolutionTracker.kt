package com.aymankhattab.nateq.core.audio.accessibility

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * سجل حسم الصوت لخدمات الشاشة (ع٤٥): يتتبع آخر 50 حسماً لعرضها
 * في تقرير التشخيص مع تحديد الطبقة الفائزة (تطبيق/جهة/افتراضي).
 */
object VoiceResolutionTracker {

    private const val MAX_ENTRIES = 50

    data class Entry(
        val timestamp: Long = System.currentTimeMillis(),
        val packageName: String?,
        val senderName: String?,
        val winningLayer: String,
        val engine: String?,
        val voiceId: String?,
        val speechRate: Float?,
        val pitch: Float?,
        val volume: Float?,
        val source: String? = null,
        val reason: String? = null
    ) {
        fun format(): String {
            val time = SimpleDateFormat(
                "HH:mm:ss", Locale.US
            ).format(Date(timestamp))
            val pkg = packageName ?: "غير محدد"
            val src = source ?: "—"
            val sender = senderName ?: "—"
            val eng = engine ?: "الافتراضي"
            val voice = voiceId ?: "—"
            val rsn = if (!reason.isNullOrBlank()) " | سبب: $reason" else ""
            return "[$time] طبقة: $winningLayer | مصدر: $src | " +
                "تطبيق: $pkg | مرسل: $sender | محرك: $eng | " +
                "صوت: $voice$rsn"
        }
    }

    private val entries = ConcurrentLinkedQueue<Entry>()

    fun record(
        packageName: String?,
        senderName: String?,
        winningLayer: String,
        engine: String?,
        voiceId: String?,
        speechRate: Float?,
        pitch: Float?,
        volume: Float?,
        source: String? = null,
        reason: String? = null
    ) {
        entries.add(
            Entry(
                packageName = packageName,
                senderName = senderName,
                winningLayer = winningLayer,
                engine = engine,
                voiceId = voiceId,
                speechRate = speechRate,
                pitch = pitch,
                volume = volume,
                source = source,
                reason = reason
            )
        )
        while (entries.size > MAX_ENTRIES) {
            entries.poll()
        }
    }

    fun getRecent(): List<Entry> = entries.toList()

    fun clear() {
        entries.clear()
    }
}
