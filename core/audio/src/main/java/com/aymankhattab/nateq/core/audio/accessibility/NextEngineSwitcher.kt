package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * يبدّل محرك Lord TTS الافتراضي إلى المحرك التالي في ترتيب النظام.
 *
 * يحدّث الفئة الافتراضية وجميع اللغات النشطة ليتحوّل قارئ الشاشة فعلياً
 * إلى المحرك الجديد، ويعمل حتى عندما لا تكون خدمة المحرك متصلة.
 */
object NextEngineSwitcher {

    data class SwitchResult(
        val packageName: String,
        val label: String,
        val engineCount: Int
    )

    /** يحدد حزمة المحرك النشط حالياً من الفئة الافتراضية أو اللغات. */
    internal fun currentEnginePackage(
        settings: SettingsRepository
    ): String? {
        val defaultCategory = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        )
        if (!defaultCategory.isNullOrBlank()) return defaultCategory
        val ar = settings.getEngineForLanguage("ar")
        if (!ar.isNullOrBlank()) return ar
        val en = settings.getEngineForLanguage("en")
        if (!en.isNullOrBlank()) return en
        return settings.allConvertLanguagePreferences().values
            .firstOrNull { !it.engine.isNullOrBlank() }
            ?.engine
    }

    /** يختار التالي، أو أول عنصر عند غياب المحرك الحالي من القائمة. */
    internal fun nextEngine(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?
    ): EnginePicker.InstalledEngine? {
        if (engines.isEmpty()) return null
        val currentIndex = engines.indexOfFirst {
            it.packageName == currentPackage
        }
        val nextIndex = (currentIndex + 1).mod(engines.size)
        return engines[nextIndex]
    }

    /** يقرأ القائمة الحالية ويحفظ المحرك التالي في إعداد Lord TTS العام. */
    fun switchToNext(context: Context): SwitchResult? {
        val appContext = context.applicationContext
        val settings = SettingsRepository.create(appContext)
        val engines = EnginePicker.installedEngines(appContext)
        val current = currentEnginePackage(settings)
        val next = nextEngine(engines, current) ?: return null

        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            next.packageName
        )

        val existingKeys = settings.allConvertLanguagePreferences().keys
        val baseKeys = listOf(
            "ar", "en",
            settings.getAppLanguage(),
            settings.getSecondaryLanguage()
        )
        val targetLanguages = (existingKeys + baseKeys)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()

        settings.setEngineForAllLanguages(targetLanguages, next.packageName)
        settings.setAutoConvertEnabled(true)

        return SwitchResult(next.packageName, next.label, engines.size)
    }

    /** المحرك المختار حالياً وتسمية عرضه في البلاطة. */
    fun selectedEngineLabel(context: Context): String? {
        val appContext = context.applicationContext
        val settings = SettingsRepository.create(appContext)
        val selected = currentEnginePackage(settings) ?: return null
        return EnginePicker.installedEngines(appContext)
            .firstOrNull { it.packageName == selected }
            ?.label
    }
}
