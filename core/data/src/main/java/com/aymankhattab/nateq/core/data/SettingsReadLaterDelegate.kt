package com.aymankhattab.nateq.core.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * تفويض طابور «اقرأ لاحقاً» — تخزينٌ دائم لقائمة النصوص المحفوظة
 * للقراءة لاحقاً.
 */
internal class SettingsReadLaterDelegate(
    private val repo: SettingsRepository
) {

    fun getReadLaterItems(): List<SettingsRepository.ReadLaterItem> =
        synchronized(repo) {
            val raw = repo.prefs.getString(
                SettingsRepository.KEY_READ_LATER, null
            ) ?: return emptyList()
            runCatching {
                val arr = JSONArray(raw)
                val out = ArrayList<SettingsRepository.ReadLaterItem>(
                    arr.length()
                )
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val text = obj.optString("text", "").trim()
                    if (text.isEmpty()) continue
                    out.add(
                        SettingsRepository.ReadLaterItem(
                            id = obj.optLong("id", 0L),
                            text = text,
                            timestampMs = obj.optLong("ts", 0L)
                        )
                    )
                }
                out
            }.getOrDefault(emptyList())
        }

    fun saveReadLaterText(
        text: String
    ): SettingsRepository.ReadLaterSaveResult = synchronized(repo) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return SettingsRepository.ReadLaterSaveResult.EMPTY
        }
        val current = getReadLaterItems().toMutableList()
        // منع الحفظ المكرر للنص نفسه (يتحمل إعادة تسليم النية/الدوران).
        if (current.any { it.text == trimmed }) {
            return SettingsRepository.ReadLaterSaveResult.DUPLICATE
        }
        val now = System.currentTimeMillis()
        current.add(
            0,
            SettingsRepository.ReadLaterItem(
                id = now,
                text = trimmed.take(
                    SettingsRepository.MAX_READ_LATER_TEXT_CHARS
                ),
                timestampMs = now
            )
        )
        val capped = if (current.size >
            SettingsRepository.MAX_READ_LATER_ITEMS
        ) {
            current.take(SettingsRepository.MAX_READ_LATER_ITEMS)
        } else {
            current
        }
        val saved = saveReadLaterItems(capped)
        if (saved) {
            SettingsRepository.ReadLaterSaveResult.SAVED
        } else {
            SettingsRepository.ReadLaterSaveResult.FAILED
        }
    }

    fun addReadLaterText(text: String): Boolean {
        val res = saveReadLaterText(text)
        return res == SettingsRepository.ReadLaterSaveResult.SAVED ||
            res == SettingsRepository.ReadLaterSaveResult.DUPLICATE
    }

    fun removeReadLaterItem(id: Long): Boolean = synchronized(repo) {
        val current = getReadLaterItems().filterNot { it.id == id }
        saveReadLaterItems(current)
    }

    fun clearReadLater(): Boolean = synchronized(repo) {
        repo.prefs.edit()
            .remove(SettingsRepository.KEY_READ_LATER)
            .commit()
    }

    private fun saveReadLaterItems(
        items: List<SettingsRepository.ReadLaterItem>
    ): Boolean {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("text", item.text)
            obj.put("ts", item.timestampMs)
            arr.put(obj)
        }
        return repo.prefs.edit().putString(
            SettingsRepository.KEY_READ_LATER, arr.toString()
        ).commit()
    }
}
