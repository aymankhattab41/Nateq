package com.aymankhattab.nateq.core.data

/**
 * تفويض طابور «اقرأ لاحقاً» — مُستخرج من [SettingsRepository] ضمن تفكيك (14)
 * دون تغيير سلوك.
 */
internal class SettingsReadLaterDelegate(
    private val repo: SettingsRepository
) {

    fun getReadLaterItems(): List<SettingsRepository.ReadLaterItem> = synchronized(repo) {
        val raw = repo.prefs.getString(SettingsRepository.KEY_READ_LATER, null) ?: return emptyList()
        runCatching {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<SettingsRepository.ReadLaterItem>(arr.length())
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

    fun addReadLaterText(text: String): Boolean = synchronized(repo) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val current = getReadLaterItems().toMutableList()
        current.add(
            0,
            SettingsRepository.ReadLaterItem(
                id = System.currentTimeMillis(),
                text = trimmed.take(SettingsRepository.MAX_READ_LATER_TEXT_CHARS),
                timestampMs = System.currentTimeMillis()
            )
        )
        val capped = if (current.size > SettingsRepository.MAX_READ_LATER_ITEMS) {
            current.take(SettingsRepository.MAX_READ_LATER_ITEMS)
        } else {
            current
        }
        saveReadLaterItems(capped)
    }

    fun removeReadLaterItem(id: Long): Boolean = synchronized(repo) {
        val current = getReadLaterItems().filterNot { it.id == id }
        saveReadLaterItems(current)
    }

    fun clearReadLater(): Boolean = synchronized(repo) {
        repo.prefs.edit().remove(SettingsRepository.KEY_READ_LATER).commit()
    }

    private fun saveReadLaterItems(
        items: List<SettingsRepository.ReadLaterItem>
    ): Boolean {
        val arr = org.json.JSONArray()
        for (item in items) {
            val obj = org.json.JSONObject()
            obj.put("id", item.id)
            obj.put("text", item.text)
            obj.put("ts", item.timestampMs)
            arr.put(obj)
        }
        return repo.prefs.edit().putString(SettingsRepository.KEY_READ_LATER, arr.toString()).commit()
    }
}
