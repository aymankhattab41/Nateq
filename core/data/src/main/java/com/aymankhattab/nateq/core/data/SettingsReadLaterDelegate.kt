package com.aymankhattab.nateq.core.data

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * تفويض طابور «اقرأ لاحقاً» — تخزين دائم لقائمة النصوص المحفوظة
 * مع معالجة آمنة للنصوص الطويلة، ومعرفات فريدة غير متصادمة،
 * ومقارنة موحدة تمنع التكرار الزائف، وعمليات غير حاجبة للمسار الساخن.
 */
internal class SettingsReadLaterDelegate(
    private val repo: SettingsRepository
) {

    private val idCounter = AtomicLong(
        System.currentTimeMillis() * 1000L
    )

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReadLaterStorageIO").apply { isDaemon = true }
    }

    private val storageDir: File by lazy {
        File(repo.appContext.filesDir, "read_later_texts").apply {
            mkdirs()
        }
    }

    private fun generateUniqueId(): Long = idCounter.incrementAndGet()

    companion object {
        private val BIDI_AND_CONTROL_REGEX = Regex(
            "[\u200E\u200F\u061C\u202A-\u202E\u2066-\u2069\uFEFF]"
        )

        /**
         * توحيد صياغة النص لمقارنة التكرار والحفظ:
         * إزالة محارف bidi والتوجيه الخفية وتوحيد فواصل الأسطر والفراغات.
         */
        fun canonicalize(text: String): String {
            return text
                .replace(BIDI_AND_CONTROL_REGEX, "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
        }
    }

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
                val seenIds = mutableSetOf<Long>()

                var migrated = false
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val textFromObj = obj.optString("text", "").trim()
                    if (textFromObj.isEmpty()) continue

                    var itemId = obj.optLong("id", 0L)
                    val ts = obj.optLong("ts", 0L)

                    // ترحيل المعرفات المتصادمة أو الصفرية لمنع حذف عناصر
                    // تشترك في نفس الطابع الزمني
                    if (itemId <= 0L || itemId in seenIds) {
                        itemId = generateUniqueId()
                        migrated = true
                    }
                    seenIds.add(itemId)

                    // استعادة النص الكامل من التخزين المنفصل إن وجد
                    val fullTextFile = File(storageDir, "text_$itemId.txt")
                    val finalText = if (fullTextFile.exists()) {
                        runCatching {
                            fullTextFile.readText(Charsets.UTF_8)
                        }.getOrDefault(textFromObj)
                    } else {
                        textFromObj
                    }

                    out.add(
                        SettingsRepository.ReadLaterItem(
                            id = itemId,
                            text = finalText,
                            timestampMs = ts
                        )
                    )
                }
                if (migrated) {
                    saveReadLaterItems(out)
                }
                out
            }.getOrDefault(emptyList())
        }

    fun saveReadLaterText(
        text: String
    ): SettingsRepository.ReadLaterSaveResult = synchronized(repo) {
        val canonical = canonicalize(text)
        if (canonical.isEmpty()) {
            return SettingsRepository.ReadLaterSaveResult.EMPTY
        }

        val current = getReadLaterItems().toMutableList()
        val canonTarget = canonical

        // منع الحفظ المكرر عبر صياغة موحدة (canonicalization)
        if (current.any { canonicalize(it.text) == canonTarget }) {
            return SettingsRepository.ReadLaterSaveResult.DUPLICATE
        }

        val now = System.currentTimeMillis()
        val uniqueId = generateUniqueId()
        val isLongText =
            canonical.length > SettingsRepository.MAX_READ_LATER_TEXT_CHARS
        val policy = repo.getReadLaterLongTextPolicy()

        val itemText: String
        val resultStatus: SettingsRepository.ReadLaterSaveResult

        if (isLongText) {
            if (policy == SettingsRepository.READ_LATER_POLICY_TRUNCATE) {
                // سياسة القطع مع الإعلان الصريح
                itemText = canonical.take(
                    SettingsRepository.MAX_READ_LATER_TEXT_CHARS
                )
                resultStatus =
                    SettingsRepository.ReadLaterSaveResult.SAVED_TRUNCATED
            } else {
                // سياسة الحفظ الكامل في تخزين منفصل مناسب
                itemText = canonical
                saveExternalTextAsync(uniqueId, canonical)
                resultStatus = SettingsRepository.ReadLaterSaveResult.SAVED
            }
        } else {
            itemText = canonical
            resultStatus = SettingsRepository.ReadLaterSaveResult.SAVED
        }

        current.add(
            0,
            SettingsRepository.ReadLaterItem(
                id = uniqueId,
                text = itemText,
                timestampMs = now
            )
        )

        val capped = if (current.size >
            SettingsRepository.MAX_READ_LATER_ITEMS
        ) {
            val removed = current.drop(SettingsRepository.MAX_READ_LATER_ITEMS)
            for (rem in removed) {
                deleteExternalTextAsync(rem.id)
            }
            current.take(SettingsRepository.MAX_READ_LATER_ITEMS)
        } else {
            current
        }

        val saved = saveReadLaterItems(capped)
        return if (saved) {
            resultStatus
        } else {
            SettingsRepository.ReadLaterSaveResult.FAILED
        }
    }

    fun addReadLaterText(text: String): Boolean {
        val res = saveReadLaterText(text)
        return res == SettingsRepository.ReadLaterSaveResult.SAVED ||
            res == SettingsRepository.ReadLaterSaveResult.SAVED_TRUNCATED ||
            res == SettingsRepository.ReadLaterSaveResult.DUPLICATE
    }

    fun removeReadLaterItem(id: Long): Boolean = synchronized(repo) {
        val current = getReadLaterItems().filterNot { it.id == id }
        deleteExternalTextAsync(id)
        saveReadLaterItems(current)
    }

    fun clearReadLater(): Boolean = synchronized(repo) {
        ioExecutor.execute {
            runCatching {
                storageDir.listFiles()?.forEach { it.delete() }
            }
        }
        repo.prefs.edit()
            .remove(SettingsRepository.KEY_READ_LATER)
            .commit()
    }

    private fun saveExternalTextAsync(id: Long, text: String) {
        ioExecutor.execute {
            runCatching {
                storageDir.mkdirs()
                File(storageDir, "text_$id.txt").writeText(
                    text,
                    Charsets.UTF_8
                )
            }
        }
    }

    private fun deleteExternalTextAsync(id: Long) {
        ioExecutor.execute {
            runCatching {
                File(storageDir, "text_$id.txt").delete()
            }
        }
    }

    private fun saveReadLaterItems(
        items: List<SettingsRepository.ReadLaterItem>
    ): Boolean {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            // في التفضيلات نخزن ما لا يتجاوز سقف المعاينة لتجنب ثقل الشاشات
            val preview = if (item.text.length >
                SettingsRepository.MAX_READ_LATER_TEXT_CHARS
            ) {
                item.text.take(SettingsRepository.MAX_READ_LATER_TEXT_CHARS)
            } else {
                item.text
            }
            obj.put("text", preview)
            obj.put("ts", item.timestampMs)
            arr.put(obj)
        }
        return repo.prefs.edit().putString(
            SettingsRepository.KEY_READ_LATER, arr.toString()
        ).commit()
    }
}
