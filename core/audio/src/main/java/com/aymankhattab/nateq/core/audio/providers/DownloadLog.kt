package com.aymankhattab.nateq.core.audio.providers

import java.util.concurrent.ConcurrentHashMap

/**
 * سجل أخطاء التنزيل لحفظ آخر خطأ لكل صوت ومحرك (ع٣٠-6).
 */
object DownloadLog {

    data class Entry(
        val voiceId: String,
        val engineId: String,
        val errorCode: String,
        val errorMessage: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun recordError(
        voiceId: String,
        engineId: String,
        errorCode: String,
        errorMessage: String
    ) {
        entries[voiceId] = Entry(
            voiceId = voiceId,
            engineId = engineId,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
    }

    fun clearError(voiceId: String) {
        entries.remove(voiceId)
    }

    fun getError(voiceId: String): Entry? = entries[voiceId]

    fun getAllErrorsForEngine(engineId: String): List<Entry> {
        return entries.values
            .filter { it.engineId == engineId }
            .sortedByDescending { it.timestamp }
    }

    fun clear() {
        entries.clear()
    }
}
