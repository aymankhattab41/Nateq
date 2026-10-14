package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.util.Locale

/**
 * بيانات صوت Vocalizer Expressive 2
 * للتحميل والتثبيت المباشر.
 */
data class VocalizerCatalogItem(
    val id: String,
    val voiceName: String,
    val displayNameAr: String,
    val displayNameEn: String,
    val locale: Locale,
    val languageCategory: String,
    val modelId: String,
    val headerFileName: String,
    val downloadUrl: String,
    val sampleUrl: String,
    val sizeMb: Int,
    val quality: String
) {
    /** اسم الملف النموذجي الدال على التثبيت */
    val indicatorFileName: String
        get() = headerFileName
}

/**
 * كتالوج أصوات Vocalizer Expressive 2
 * المباشرة المستخرجة من مستودعات Nuance / CodeFactory.
 */
object VocalizerModelCatalog {

    private const val BASE_DATA =
        "https://downloads.codefactoryglobal.com/downloads/vedata/3.7.1/"
    private const val BASE_SAMPLE =
        "https://downloads.codefactoryglobal.com/downloads/vedata/3.7.1/" +
            "samples/"

    val ITEMS: List<VocalizerCatalogItem> = listOf(
        // ==================== أصوات عربية ====================
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-laila-compact",
            voiceName = "Laila",
            displayNameAr = "ليلى (مضغوط - خفيف)",
            displayNameEn = "Laila (Compact)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_arw_laila_22_embedded-compact_1-0-5.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_laila_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_laila_embedded_compact_3.7.1.mp3",
            sizeMb = 13,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-laila-high",
            voiceName = "Laila",
            displayNameAr = "ليلى (جودة عالية)",
            displayNameEn = "Laila (High Quality)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_arw_laila_22_embedded-high_2-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_laila_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_laila_embedded_high_3.7.1.mp3",
            sizeMb = 43,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-maged-compact",
            voiceName = "Maged",
            displayNameAr = "ماجد (مضغوط - خفيف)",
            displayNameEn = "Maged (Compact)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_arw_maged_22_embedded-compact_2-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_maged_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_maged_embedded_compact_3.7.1.mp3",
            sizeMb = 14,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-maged-high",
            voiceName = "Maged",
            displayNameAr = "ماجد (جودة عالية)",
            displayNameEn = "Maged (High Quality)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_arw_maged_22_embedded-high_2-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_maged_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_maged_embedded_high_3.7.1.mp3",
            sizeMb = 45,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-tarik-compact",
            voiceName = "Tarik",
            displayNameAr = "طارق (مضغوط - خفيف)",
            displayNameEn = "Tarik (Compact)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_arw_tarik_22_embedded-compact_2-0-0.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_tarik_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_tarik_embedded_compact_3.7.1.mp3",
            sizeMb = 13,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-tarik-high",
            voiceName = "Tarik",
            displayNameAr = "طارق (جودة عالية)",
            displayNameEn = "Tarik (High Quality)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_arw_tarik_22_embedded-high_2-0-0.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_tarik_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_tarik_embedded_high_3.7.1.mp3",
            sizeMb = 44,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-mariam-compact",
            voiceName = "Mariam",
            displayNameAr = "مريم (مضغوط - خفيف)",
            displayNameEn = "Mariam (Compact)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_arw_mariam_22_embedded-compact_1-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_mariam_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_mariam_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-ara-sau-mariam-high",
            voiceName = "Mariam",
            displayNameAr = "مريم (جودة عالية)",
            displayNameEn = "Mariam (High Quality)",
            locale = Locale.forLanguageTag("ar-AE"),
            languageCategory = "ar",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_arw_mariam_22_embedded-high_2-0-0.hdr",
            downloadUrl =
                BASE_DATA + "ve_ara_sau_mariam_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_ara_sau_mariam_embedded_high_3.7.1.mp3",
            sizeMb = 42,
            quality = "High"
        ),

        // ==================== أصوات إنجليزية ====================
        VocalizerCatalogItem(
            id = "vocalizer-eng-usa-ava-compact",
            voiceName = "Ava",
            displayNameAr = "آفا (أمريكي - مضغوط)",
            displayNameEn = "Ava (US - Compact)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_enu_ava_22_embedded-compact_1-1-0.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_usa_ava_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_usa_ava_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-usa-ava-high",
            voiceName = "Ava",
            displayNameAr = "آفا (أمريكي - جودة عالية)",
            displayNameEn = "Ava (US - High)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_enu_ava_22_embedded-high_2-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_usa_ava_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_usa_ava_embedded_high_3.7.1.mp3",
            sizeMb = 45,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-usa-samantha-compact",
            voiceName = "Samantha",
            displayNameAr = "سامانثا (أمريكي - مضغوط)",
            displayNameEn = "Samantha (US - Compact)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_enu_samantha_22_embedded-compact_1-0-4.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_usa_samantha_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_usa_samantha_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-usa-samantha-high",
            voiceName = "Samantha",
            displayNameAr = "سامانثا (أمريكي - جودة عالية)",
            displayNameEn = "Samantha (US - High)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_enu_samantha_22_embedded-high_2-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_usa_samantha_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_usa_samantha_embedded_high_3.7.1.mp3",
            sizeMb = 45,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-usa-tom-compact",
            voiceName = "Tom",
            displayNameAr = "توم (أمريكي - مضغوط)",
            displayNameEn = "Tom (US - Compact)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_enu_tom_22_embedded-compact_1-1-0.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_usa_tom_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_usa_tom_embedded_compact_3.7.1.mp3",
            sizeMb = 13,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-usa-tom-high",
            voiceName = "Tom",
            displayNameAr = "توم (أمريكي - جودة عالية)",
            displayNameEn = "Tom (US - High)",
            locale = Locale.forLanguageTag("en-US"),
            languageCategory = "en",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_enu_tom_22_embedded-high_2-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_usa_tom_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_usa_tom_embedded_high_3.7.1.mp3",
            sizeMb = 44,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-gbr-serena-compact",
            voiceName = "Serena",
            displayNameAr = "سيرينا (بريطاني - مضغوط)",
            displayNameEn = "Serena (UK - Compact)",
            locale = Locale.forLanguageTag("en-GB"),
            languageCategory = "en",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_eng_serena_22_embedded-compact_1-0-2.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_gbr_serena_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_gbr_serena_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-gbr-serena-high",
            voiceName = "Serena",
            displayNameAr = "سيرينا (بريطاني - جودة عالية)",
            displayNameEn = "Serena (UK - High)",
            locale = Locale.forLanguageTag("en-GB"),
            languageCategory = "en",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_eng_serena_22_embedded-high_2-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_gbr_serena_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_gbr_serena_embedded_high_3.7.1.mp3",
            sizeMb = 45,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-gbr-daniel-compact",
            voiceName = "Daniel",
            displayNameAr = "دانيال (بريطاني - مضغوط)",
            displayNameEn = "Daniel (UK - Compact)",
            locale = Locale.forLanguageTag("en-GB"),
            languageCategory = "en",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_eng_daniel_22_embedded-compact_1-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_gbr_daniel_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_gbr_daniel_embedded_compact_3.7.1.mp3",
            sizeMb = 13,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-gbr-daniel-high",
            voiceName = "Daniel",
            displayNameAr = "دانيال (بريطاني - جودة عالية)",
            displayNameEn = "Daniel (UK - High)",
            locale = Locale.forLanguageTag("en-GB"),
            languageCategory = "en",
            modelId = "embedded_high",
            headerFileName =
                "ve_pipeline_eng_daniel_22_embedded-high_2-0-0.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_gbr_daniel_embedded_high_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_gbr_daniel_embedded_high_3.7.1.mp3",
            sizeMb = 44,
            quality = "High"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-eng-gbr-oliver-compact",
            voiceName = "Oliver",
            displayNameAr = "أوليفر (بريطاني - مضغوط)",
            displayNameEn = "Oliver (UK - Compact)",
            locale = Locale.forLanguageTag("en-GB"),
            languageCategory = "en",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_eng_oliver_22_embedded-compact_1-1-0.hdr",
            downloadUrl =
                BASE_DATA + "ve_eng_gbr_oliver_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_eng_gbr_oliver_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),

        // ==================== لغات أخرى ====================
        VocalizerCatalogItem(
            id = "vocalizer-fra-fra-thomas-compact",
            voiceName = "Thomas",
            displayNameAr = "توماس (فرنسي)",
            displayNameEn = "Thomas (French)",
            locale = Locale.forLanguageTag("fr-FR"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_frf_thomas_22_embedded-compact_1-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_fra_fra_thomas_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_fra_fra_thomas_embedded_compact_3.7.1.mp3",
            sizeMb = 13,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-fra-fra-audrey-compact",
            voiceName = "Audrey",
            displayNameAr = "أودري (فرنسي)",
            displayNameEn = "Audrey (French)",
            locale = Locale.forLanguageTag("fr-FR"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_frf_audrey_22_embedded-compact_1-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_fra_fra_audrey_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_fra_fra_audrey_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-deu-deu-anna-compact",
            voiceName = "Anna",
            displayNameAr = "آنا (ألماني)",
            displayNameEn = "Anna (German)",
            locale = Locale.forLanguageTag("de-DE"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_ged_anna_22_embedded-compact_1-0-2.hdr",
            downloadUrl =
                BASE_DATA + "ve_deu_deu_anna_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_deu_deu_anna_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-deu-deu-yannick-compact",
            voiceName = "Yannick",
            displayNameAr = "يانيك (ألماني)",
            displayNameEn = "Yannick (German)",
            locale = Locale.forLanguageTag("de-DE"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_ged_yannick_22_embedded-compact_1-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_deu_deu_yannick_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_deu_deu_yannick_embedded_compact_3.7.1.mp3",
            sizeMb = 13,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-spa-esp-jorge-compact",
            voiceName = "Jorge",
            displayNameAr = "خورخي (إسباني)",
            displayNameEn = "Jorge (Spanish)",
            locale = Locale.forLanguageTag("es-ES"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_spe_jorge_22_embedded-compact_1-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_spa_esp_jorge_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_spa_esp_jorge_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-spa-esp-monica-compact",
            voiceName = "Monica",
            displayNameAr = "مونيكا (إسباني)",
            displayNameEn = "Monica (Spanish)",
            locale = Locale.forLanguageTag("es-ES"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_spe_monica_22_embedded-compact_1-0-1.hdr",
            downloadUrl =
                BASE_DATA + "ve_spa_esp_monica_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_spa_esp_monica_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-tur-tur-yelda-compact",
            voiceName = "Yelda",
            displayNameAr = "يلدا (تركي)",
            displayNameEn = "Yelda (Turkish)",
            locale = Locale.forLanguageTag("tr-TR"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_tut_yelda_22_embedded-compact_1-0-2.hdr",
            downloadUrl =
                BASE_DATA + "ve_tur_tur_yelda_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_tur_tur_yelda_embedded_compact_3.7.1.mp3",
            sizeMb = 12,
            quality = "Compact"
        ),
        VocalizerCatalogItem(
            id = "vocalizer-rus-rus-milena-compact",
            voiceName = "Milena",
            displayNameAr = "ميلينا (روسي)",
            displayNameEn = "Milena (Russian)",
            locale = Locale.forLanguageTag("ru-RU"),
            languageCategory = "other",
            modelId = "embedded_compact",
            headerFileName =
                "ve_pipeline_rur_milena_22_embedded-compact_1-0-2.hdr",
            downloadUrl =
                BASE_DATA + "ve_rus_rus_milena_embedded_compact_3.7.1.zip",
            sampleUrl =
                BASE_SAMPLE + "ve_rus_rus_milena_embedded_compact_3.7.1.mp3",
            sizeMb = 13,
            quality = "Compact"
        )
    )

    /**
     * التحقق من تثبيت الصوت بفحص وجود ملف الـ Header الخاص به
     * في مجلد VocalizerEx2.
     */
    fun isVoiceInstalled(targetDir: File, item: VocalizerCatalogItem): Boolean {
        if (!targetDir.exists() || !targetDir.isDirectory) return false
        val hdrFile = File(targetDir, item.headerFileName)
        return hdrFile.exists() && hdrFile.length() > 0
    }

    /**
     * فحص جميع المسارات المحتملة لمعرفة ما إذا كان الصوت مثبتاً.
     */
    fun isVoiceInstalled(
        context: android.content.Context,
        item: VocalizerCatalogItem
    ): Boolean {
        for (dir in VocalizerDownloader.getAllSearchDirs(context)) {
            if (isVoiceInstalled(dir, item)) return true
        }
        return false
    }
}
