package com.aymankhattab.nateq.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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

    private fun parseExistingMaxId(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching {
            val arr = JSONArray(raw)
            var maxId = 0L
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                if (id > maxId) maxId = id
            }
            maxId
        }.getOrDefault(0L)
    }

    private val idCounter: AtomicLong by lazy {
        val raw = repo.prefs.getString(
            SettingsRepository.KEY_READ_LATER, null
        )
        val maxExisting = parseExistingMaxId(raw)
        val initial = maxOf(
            System.currentTimeMillis() * 1000L,
            maxExisting + 1L
        )
        AtomicLong(initial)
    }

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReadLaterStorageIO").apply { isDaemon = true }
    }

    internal val storageDir: File by lazy {
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

    /**
     * استرجاع عناصر اقرأ لاحقاً. افتراضياً لا يقرأ ملفات النصوص
     * الكاملة من القرص تجنباً لتعطيل خيط الواجهة.
     */
    fun getReadLaterItems(
        loadFullText: Boolean = false
    ): List<SettingsRepository.ReadLaterItem> = synchronized(repo) {
        val raw = repo.prefs.getString(
            SettingsRepository.KEY_READ_LATER, null
        ) ?: return emptyList()

        runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<SettingsRepository.ReadLaterItem>(arr.length())
            val seenIds = mutableSetOf<Long>()

            var migrated = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val textFromObj = obj.optString("text", "").trim()
                if (textFromObj.isEmpty()) continue

                var itemId = obj.optLong("id", 0L)
                val ts = obj.optLong("ts", 0L)
                val isTruncated = obj.optBoolean("trunc", false)
                val hasFull = obj.optBoolean("hasFull", false)
                val fullLen = obj.optInt(
                    "len",
                    if (isTruncated) {
                        SettingsRepository.MAX_READ_LATER_TEXT_CHARS
                    } else {
                        textFromObj.length
                    }
                )

                if (itemId <= 0L || itemId in seenIds) {
                    itemId = generateUniqueId()
                    migrated = true
                }
                seenIds.add(itemId)

                val itemText = if (loadFullText) {
                    val fullTextFile = File(storageDir, "text_$itemId.txt")
                    if (fullTextFile.exists()) {
                        runCatching {
                            fullTextFile.readText(StandardCharsets.UTF_8)
                        }.getOrDefault(textFromObj)
                    } else {
                        textFromObj
                    }
                } else {
                    textFromObj
                }

                out.add(
                    SettingsRepository.ReadLaterItem(
                        id = itemId,
                        text = itemText,
                        timestampMs = ts,
                        isTruncated = isTruncated,
                        hasFullText = hasFull || (!isTruncated &&
                            File(storageDir, "text_$itemId.txt").exists()),
                        fullTextLength = fullLen
                    )
                )
            }
            if (migrated) {
                saveReadLaterItems(out)
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * استرجاع النص الكامل لعنصر محدد، من القرص إن وجد أو من المعاينة.
     */
    fun getReadLaterFullText(id: Long): String? = synchronized(repo) {
        val file = File(storageDir, "text_$id.txt")
        if (file.exists()) {
            val text = runCatching {
                file.readText(StandardCharsets.UTF_8)
            }.getOrNull()
            if (!text.isNullOrEmpty()) return text
        }
        val items = getReadLaterItems(loadFullText = false)
        return items.firstOrNull { it.id == id }?.text
    }

    fun saveReadLaterText(
        text: String
    ): SettingsRepository.ReadLaterSaveResult = synchronized(repo) {
        val canonical = canonicalize(text)
        if (canonical.isEmpty()) {
            return SettingsRepository.ReadLaterSaveResult.EMPTY
        }

        val current = getReadLaterItems(loadFullText = false).toMutableList()
        val canonTarget = canonical
        val targetLen = canonical.length
        val targetPreview = if (
            targetLen > SettingsRepository.MAX_READ_LATER_TEXT_CHARS
        ) {
            canonical.take(SettingsRepository.MAX_READ_LATER_TEXT_CHARS)
        } else {
            canonical
        }

        // فحص التكرار الخفيف: مقارنة الطول والمعاينة أولاً بلا I/O
        val isDuplicate = current.any { item ->
            if (item.fullTextLength != targetLen) return@any false
            if (item.text != targetPreview) return@any false
            if (targetLen <= SettingsRepository.MAX_READ_LATER_TEXT_CHARS) {
                true
            } else {
                val full = getReadLaterFullText(item.id)
                full != null && canonicalize(full) == canonTarget
            }
        }

        if (isDuplicate) {
            return SettingsRepository.ReadLaterSaveResult.DUPLICATE
        }

        val now = System.currentTimeMillis()
        val uniqueId = generateUniqueId()
        val isLongText =
            canonical.length > SettingsRepository.MAX_READ_LATER_TEXT_CHARS
        val policy = repo.getReadLaterLongTextPolicy()

        val itemText: String
        val resultStatus: SettingsRepository.ReadLaterSaveResult
        val hasFull: Boolean
        val isTruncated: Boolean
        val fullLen: Int

        if (isLongText) {
            if (policy == SettingsRepository.READ_LATER_POLICY_TRUNCATE) {
                itemText = canonical.take(
                    SettingsRepository.MAX_READ_LATER_TEXT_CHARS
                )
                resultStatus =
                    SettingsRepository.ReadLaterSaveResult.SAVED_TRUNCATED
                hasFull = false
                isTruncated = true
                fullLen = SettingsRepository.MAX_READ_LATER_TEXT_CHARS
            } else {
                // حفظ ذري للنص الكامل أولاً قبل تحديث الفهرس
                val fileSaved = saveExternalTextAtomic(uniqueId, canonical)
                if (!fileSaved) {
                    return SettingsRepository.ReadLaterSaveResult.FAILED
                }
                itemText = canonical.take(
                    SettingsRepository.MAX_READ_LATER_TEXT_CHARS
                )
                resultStatus = SettingsRepository.ReadLaterSaveResult.SAVED
                hasFull = true
                isTruncated = false
                fullLen = canonical.length
            }
        } else {
            itemText = canonical
            resultStatus = SettingsRepository.ReadLaterSaveResult.SAVED
            hasFull = false
            isTruncated = false
            fullLen = canonical.length
        }

        current.add(
            0,
            SettingsRepository.ReadLaterItem(
                id = uniqueId,
                text = itemText,
                timestampMs = now,
                isTruncated = isTruncated,
                hasFullText = hasFull,
                fullTextLength = fullLen
            )
        )

        val evictedItems = if (
            current.size > SettingsRepository.MAX_READ_LATER_ITEMS
        ) {
            current.drop(SettingsRepository.MAX_READ_LATER_ITEMS)
        } else {
            emptyList()
        }

        val capped = current.take(SettingsRepository.MAX_READ_LATER_ITEMS)
        val saved = saveReadLaterItems(capped)

        return if (saved) {
            if (evictedItems.isNotEmpty()) {
                ioExecutor.execute {
                    for (rem in evictedItems) {
                        deleteExternalText(rem.id)
                    }
                }
            }
            resultStatus
        } else {
            // التراجع عند فشل حفظ الفهرس
            if (hasFull) {
                deleteExternalText(uniqueId)
            }
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
        val current = getReadLaterItems(loadFullText = false)
        val filtered = current.filterNot { it.id == id }
        val saved = saveReadLaterItems(filtered)
        if (saved) {
            ioExecutor.execute {
                deleteExternalText(id)
            }
            true
        } else {
            false
        }
    }

    fun clearReadLater(): Boolean = synchronized(repo) {
        val ok = repo.prefs.edit()
            .remove(SettingsRepository.KEY_READ_LATER)
            .commit()
        if (ok) {
            ioExecutor.execute {
                runCatching {
                    storageDir.listFiles()?.forEach { it.delete() }
                }
            }
            true
        } else {
            false
        }
    }

    /**
     * حفظ ذري لملف النص عبر ملف مؤقت وتبديل موثوق.
     */
    fun saveExternalTextAtomic(id: Long, text: String): Boolean {
        return try {
            storageDir.mkdirs()
            val tmpFile = File(storageDir, "text_$id.tmp")
            val targetFile = File(storageDir, "text_$id.txt")

            FileOutputStream(tmpFile).use { fos ->
                val writer = OutputStreamWriter(fos, StandardCharsets.UTF_8)
                writer.write(text)
                writer.flush()
                runCatching { fos.fd.sync() }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tmpFile.renameTo(targetFile)
            if (!renamed) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            targetFile.exists() && targetFile.length() > 0L
        } catch (_: Throwable) {
            runCatching { File(storageDir, "text_$id.tmp").delete() }
            false
        }
    }

    private fun deleteExternalText(id: Long) {
        runCatching {
            File(storageDir, "text_$id.txt").delete()
            File(storageDir, "text_$id.tmp").delete()
        }
    }

    /**
     * تنظيف الملفات اليتيمة المملوكة للتطبيق التي لا توجد في الفهرس.
     */
    fun cleanOrphanFiles() {
        ioExecutor.execute {
            runCatching {
                val activeIds = getReadLaterItems(loadFullText = false)
                    .map { it.id }.toSet()
                val files = storageDir.listFiles() ?: return@runCatching
                for (file in files) {
                    val name = file.name
                    if (name.endsWith(".tmp")) {
                        file.delete()
                        continue
                    }
                    if (name.startsWith("text_") && name.endsWith(".txt")) {
                        val idStr = name.substringAfter("text_")
                            .substringBefore(".txt")
                        val id = idStr.toLongOrNull()
                        if (id != null && id !in activeIds) {
                            file.delete()
                        }
                    }
                }
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
            val preview = if (
                item.text.length > SettingsRepository.MAX_READ_LATER_TEXT_CHARS
            ) {
                item.text.take(SettingsRepository.MAX_READ_LATER_TEXT_CHARS)
            } else {
                item.text
            }
            obj.put("text", preview)
            obj.put("ts", item.timestampMs)
            obj.put("trunc", item.isTruncated)
            obj.put("hasFull", item.hasFullText)
            obj.put("len", item.fullTextLength)
            arr.put(obj)
        }
        return repo.prefs.edit().putString(
            SettingsRepository.KEY_READ_LATER, arr.toString()
        ).commit()
    }
}
