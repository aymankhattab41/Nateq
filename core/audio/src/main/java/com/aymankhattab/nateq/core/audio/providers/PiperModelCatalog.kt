package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.util.Locale

/** نموذج صوت Piper / Sherpa-ONNX
 *  مع بيانات التنزيل والوصف */
data class PiperCatalogItem(
    val id: String,
    val modelDirName: String,
    val modelFileName: String,
    val tokensFileName: String = "tokens.txt",
    val displayNameAr: String,
    val displayNameEn: String,
    val locale: Locale,
    val languageCategory: String, // "ar", "en", "other"
    val sizeMb: Int,
    val quality: String, // "High", "Medium", "Low"
    val repo: String = "csukuangfj",
    val type: String = "vits-piper"
) {
    fun getOnnxUrl(): String {
        return "https://huggingface.co/$repo/$modelDirName" +
            "/resolve/main/$modelFileName"
    }

    fun getTokensUrl(): String {
        return "https://huggingface.co/$repo/$modelDirName" +
            "/resolve/main/$tokensFileName"
    }
}

/** كتالوج نماذج أصوات Piper المدعومة
 *  للتنزيل المباشر */
object PiperModelCatalog {

    val ITEMS: List<PiperCatalogItem> = listOf(
        // --- أصوات عربية ---
        PiperCatalogItem(
            id = "vits-piper-ar_JO-kareem-medium",
            modelDirName = "vits-piper-ar_JO-kareem-medium",
            modelFileName = "ar_JO-kareem-medium.onnx",
            displayNameAr = "كريم (أردني - جودة متوسطة)",
            displayNameEn = "Kareem (Jordanian - Medium)",
            locale = Locale.forLanguageTag("ar-JO"),
            languageCategory = "ar",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-ar_JO-kareem-low",
            modelDirName = "vits-piper-ar_JO-kareem-low",
            modelFileName = "ar_JO-kareem-low.onnx",
            displayNameAr = "كريم (أردني - خفيف وسريع)",
            displayNameEn = "Kareem (Jordanian - Low/Fast)",
            locale = Locale.forLanguageTag("ar-JO"),
            languageCategory = "ar",
            sizeMb = 18,
            quality = "Low"
        ),

        // --- أصوات إنجليزية ---
        PiperCatalogItem(
            id = "vits-piper-en_US-lessac-high",
            modelDirName = "vits-piper-en_US-lessac-high",
            modelFileName = "en_US-lessac-high.onnx",
            displayNameAr = "ليساك (أمريكي - جودة عالية)",
            displayNameEn = "Lessac (US - High)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            sizeMb = 110,
            quality = "High"
        ),
        PiperCatalogItem(
            id = "vits-piper-en_US-lessac-medium",
            modelDirName = "vits-piper-en_US-lessac-medium",
            modelFileName = "en_US-lessac-medium.onnx",
            displayNameAr = "ليساك (أمريكي - جودة متوسطة)",
            displayNameEn = "Lessac (US - Medium)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-en_US-amy-medium",
            modelDirName = "vits-piper-en_US-amy-medium",
            modelFileName = "en_US-amy-medium.onnx",
            displayNameAr = "إيمي (أمريكي - صوت نسائي)",
            displayNameEn = "Amy (US - Female)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-en_US-ryan-medium",
            modelDirName = "vits-piper-en_US-ryan-medium",
            modelFileName = "en_US-ryan-medium.onnx",
            displayNameAr = "رايان (أمريكي - صوت رجالي)",
            displayNameEn = "Ryan (US - Male)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-en_GB-jenny_dioco-medium",
            modelDirName = "vits-piper-en_GB-jenny_dioco-medium",
            modelFileName = "en_GB-jenny_dioco-medium.onnx",
            displayNameAr = "جيني (بريطاني - نسائي)",
            displayNameEn = "Jenny (British - Female)",
            locale = Locale.forLanguageTag("en-GB"),
            languageCategory = "en",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-en_GB-cori-medium",
            modelDirName = "vits-piper-en_GB-cori-medium",
            modelFileName = "en_GB-cori-medium.onnx",
            displayNameAr = "كوري (بريطاني - صوت واضح)",
            displayNameEn = "Cori (British - Clear)",
            locale = Locale.forLanguageTag("en-GB"),
            languageCategory = "en",
            sizeMb = 65,
            quality = "Medium"
        ),

        // --- لغات أخرى ---
        PiperCatalogItem(
            id = "vits-piper-fr_FR-siwis-medium",
            modelDirName = "vits-piper-fr_FR-siwis-medium",
            modelFileName = "fr_FR-siwis-medium.onnx",
            displayNameAr = "سيويس (فرنسي)",
            displayNameEn = "Siwis (French)",
            locale = Locale.forLanguageTag("fr-FR"),
            languageCategory = "other",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-de_DE-thorsten-medium",
            modelDirName = "vits-piper-de_DE-thorsten-medium",
            modelFileName = "de_DE-thorsten-medium.onnx",
            displayNameAr = "ثورستن (ألماني)",
            displayNameEn = "Thorsten (German)",
            locale = Locale.forLanguageTag("de-DE"),
            languageCategory = "other",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-es_ES-davefx-medium",
            modelDirName = "vits-piper-es_ES-davefx-medium",
            modelFileName = "es_ES-davefx-medium.onnx",
            displayNameAr = "ديف (إسباني)",
            displayNameEn = "Dave (Spanish)",
            locale = Locale.forLanguageTag("es-ES"),
            languageCategory = "other",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-tr_TR-dfki-medium",
            modelDirName = "vits-piper-tr_TR-dfki-medium",
            modelFileName = "tr_TR-dfki-medium.onnx",
            displayNameAr = "دي إف كي آي (تركي)",
            displayNameEn = "DFKI (Turkish)",
            locale = Locale.forLanguageTag("tr-TR"),
            languageCategory = "other",
            sizeMb = 65,
            quality = "Medium"
        ),
        PiperCatalogItem(
            id = "vits-piper-ru_RU-denis-medium",
            modelDirName = "vits-piper-ru_RU-denis-medium",
            modelFileName = "ru_RU-denis-medium.onnx",
            displayNameAr = "دينيس (روسي)",
            displayNameEn = "Denis (Russian)",
            locale = Locale.forLanguageTag("ru-RU"),
            languageCategory = "other",
            sizeMb = 65,
            quality = "Medium"
        )
    )

    /** التحقق من كون النموذج مثبتاً محلياً */
    fun isModelInstalled(modelDir: File, item: PiperCatalogItem): Boolean {
        val targetDir = File(modelDir, item.modelDirName)
        if (!targetDir.exists() || !targetDir.isDirectory) return false

        val onnx = File(targetDir, item.modelFileName)
        val tokens = File(targetDir, item.tokensFileName)
        return onnx.exists() && onnx.length() > 1024 &&
            tokens.exists() && tokens.length() > 10
    }
}
