package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.util.Locale

/**
 * مسح نماذج Sherpa-ONNX / Piper / Coqui من القرص — خالصٌ بلا
 * مكتبة أصلية، يشترك فيه المزوّد (عرض الأصوات في العملية الأمامية)
 * والمضيف (`:engine_host` في ع٢٩-5) فلا تنسخ قاعدة المسح ولا
 * تختلف النتيجة بين الطرفين.
 */
object SherpaModelCatalog {

    /** نموذج صالح واحد عُثر عليه في أحد مجلدات البحث. */
    data class ModelInfo(
        val id: String,
        val displayName: String,
        val locale: Locale,
        val modelDir: File,
        val modelFile: File,
        val tokensFile: File,
        val dataDir: File?,
        val speakerCount: Int = 1
    )

    /** المسح: مجلدات البحث نفسها ثم مجلداتها الفرعية (عمق واحد). */
    fun discover(searchDirs: List<File>): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            models.addAll(inspectFolderForModels(dir))
            dir.listFiles()?.forEach { subDir ->
                if (subDir.isDirectory) {
                    models.addAll(inspectFolderForModels(subDir))
                }
            }
        }
        return models.distinctBy { it.id }
    }

    private fun inspectFolderForModels(folder: File): List<ModelInfo> {
        val files = folder.listFiles() ?: return emptyList()
        val onnxFiles = files.filter {
            it.isFile && it.extension.equals("onnx", ignoreCase = true)
        }
        if (onnxFiles.isEmpty()) return emptyList()

        val results = mutableListOf<ModelInfo>()

        for (onnxFile in onnxFiles) {
            val modelName = onnxFile.nameWithoutExtension
            // البحث عن ملف الرموز (tokens)
            val tokensFile = files.firstOrNull {
                it.isFile && (
                    it.name.equals("tokens.txt", ignoreCase = true) ||
                        it.name.equals(
                            "$modelName.tokens.txt",
                            ignoreCase = true
                        ) ||
                        it.name.equals(
                            "tokens_$modelName.txt",
                            ignoreCase = true
                        ) ||
                        it.name.equals(
                            "$modelName.txt",
                            ignoreCase = true
                        )
                    )
            } ?: File(folder, "tokens.txt")

            if (!tokensFile.exists()) continue

            val folderName = folder.name
            val rawName = if (
                onnxFiles.size == 1 && (
                    folderName.startsWith("vits-") ||
                        folderName.startsWith("piper-")
                    )
            ) {
                folderName
            } else {
                modelName
            }

            val cleanDisplayName = rawName
                .removePrefix("vits-piper-")
                .removePrefix("vits-coqui-")
                .removePrefix("vits-")
                .removePrefix("piper-")
                .removePrefix("coqui-")

            val espeakDataInFolder = File(folder, "espeak-ng-data")
            val dataDir = if (espeakDataInFolder.exists()) {
                espeakDataInFolder
            } else {
                null
            }

            results.add(
                ModelInfo(
                    id = "sherpa_${rawName.lowercase()}",
                    displayName = cleanDisplayName,
                    locale = parseLocaleFromName(cleanDisplayName),
                    modelDir = folder,
                    modelFile = onnxFile,
                    tokensFile = tokensFile,
                    dataDir = dataDir
                )
            )
        }
        return results
    }

    /**
     * لغة النموذج من اسمه: `ar_JO-kareem-medium` → ar-JO،
     * `en_US-lessac-high` → en-US، وما لا يطابق النمط يبقى بلا لغة.
     */
    private fun parseLocaleFromName(cleanName: String): Locale {
        val parts = cleanName.split("-", "_")
        if (parts.size >= 2) {
            val lang = parts[0].lowercase()
            val country = parts[1].uppercase()
            if (lang.length in 2..3 && country.length in 2..3) {
                return Locale.Builder()
                    .setLanguage(lang)
                    .setRegion(country)
                    .build()
            }
        }
        if (parts.isNotEmpty()) {
            val lang = parts[0].lowercase()
            if (lang.length in 2..3) {
                return Locale.Builder()
                    .setLanguage(lang)
                    .build()
            }
        }
        return Locale.ROOT
    }
}
