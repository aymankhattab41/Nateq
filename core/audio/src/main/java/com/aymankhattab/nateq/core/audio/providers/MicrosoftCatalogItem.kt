package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.util.Locale

/**
 * بيانات صوت مايكروسوفت المدمج (Microsoft Embedded Speech).
 */
data class MicrosoftCatalogItem(
    val id: String,
    val voiceName: String,
    val displayNameAr: String,
    val displayNameEn: String,
    val locale: Locale,
    val languageCategory: String,
    val gender: String,
    val modelName: String,
    val downloadUrl: String,
    val sampleUrl: String? = null,
    val sizeBytes: Long,
    val archiveSha256: String,
    val topDirName: String,
    val quality: String = "Neural"
) {
    /** الحجم بالميجابايت للعرض والتحقق من المساحة */
    val sizeMb: Int
        get() = (sizeBytes / (1024L * 1024L)).toInt().coerceAtLeast(1)

    /** اسم الملف الدال على اكتمال التثبيت داخل مجلد الصوت */
    val indicatorFileName: String
        get() = "am_v5_decoder.bin"
}
