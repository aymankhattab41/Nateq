package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * يبدّل محرك Lord TTS الافتراضي إلى المحرك التالي في ترتيب النظام.
 *
 * يحدّث الفئة الافتراضية وجميع اللغات النشطة ليتحوّل قارئ الشاشة فعلياً
 * إلى المحرك الجديد، مع إمكانية العودة إلى محرك Lord TTS الافتراضي.
 */
object NextEngineSwitcher {

    const val LORD_TTS_PACKAGE = "com.aymankhattab.nateq.default"

    data class SwitchResult(
        val packageName: String,
        val label: String,
        val engineCount: Int
    )

    /**
     * قائمة المحركات المتاحة في حلقة التبديل، وتبدأ دائماً
     * بمحرك Lord TTS الافتراضي ثم المحركات المثبتة في النظام.
     */
    fun availableEngines(
        context: Context
    ): List<EnginePicker.InstalledEngine> {
        val lordTts = EnginePicker.InstalledEngine(
            LORD_TTS_PACKAGE,
            "Lord TTS"
        )
        val installed = EnginePicker.installedEngines(context)
        return listOf(lordTts) + installed
    }

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
        val effective = currentPackage ?: LORD_TTS_PACKAGE
        val currentIndex = engines.indexOfFirst {
            it.packageName == effective ||
                (effective == LORD_TTS_PACKAGE &&
                    it.packageName == LORD_TTS_PACKAGE)
        }
        val nextIndex = (currentIndex + 1).mod(engines.size)
        return engines[nextIndex]
    }

    /** يختار السابق، أو آخر عنصر عند غياب المحرك الحالي من القائمة. */
    internal fun previousEngine(
        engines: List<EnginePicker.InstalledEngine>,
        currentPackage: String?
    ): EnginePicker.InstalledEngine? {
        if (engines.isEmpty()) return null
        val effective = currentPackage ?: LORD_TTS_PACKAGE
        val currentIndex = engines.indexOfFirst {
            it.packageName == effective ||
                (effective == LORD_TTS_PACKAGE &&
                    it.packageName == LORD_TTS_PACKAGE)
        }
        val prevIndex = if (currentIndex <= 0) {
            engines.size - 1
        } else {
            currentIndex - 1
        }
        return engines[prevIndex]
    }

    /** يقرأ القائمة الحالية ويحفظ المحرك التالي في إعداد Lord TTS العام. */
    fun switchToNext(context: Context): SwitchResult? {
        val appContext = context.applicationContext
        val settings = SettingsRepository.create(appContext)
        val engines = availableEngines(appContext)
        val current = currentEnginePackage(settings)
        val next = nextEngine(engines, current) ?: return null
        return applyEngineSwitch(settings, next, engines.size)
    }

    /** يقرأ القائمة الحالية ويحفظ المحرك السابق في إعداد Lord TTS العام. */
    fun switchToPrevious(context: Context): SwitchResult? {
        val appContext = context.applicationContext
        val settings = SettingsRepository.create(appContext)
        val engines = availableEngines(appContext)
        val current = currentEnginePackage(settings)
        val prev = previousEngine(engines, current) ?: return null
        return applyEngineSwitch(settings, prev, engines.size)
    }

    private fun applyEngineSwitch(
        settings: SettingsRepository,
        target: EnginePicker.InstalledEngine,
        engineCount: Int
    ): SwitchResult {
        val isLord = target.packageName == LORD_TTS_PACKAGE
        val actualEngine = if (isLord) null else target.packageName

        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            actualEngine
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

        settings.setEngineForAllLanguages(targetLanguages, actualEngine)
        settings.setAutoConvertEnabled(!isLord)

        return SwitchResult(target.packageName, target.label, engineCount)
    }

    /** المحرك المختار حالياً وتسمية عرضه في البلاطة. */
    fun selectedEngineLabel(context: Context): String? {
        val appContext = context.applicationContext
        val settings = SettingsRepository.create(appContext)
        val selected = currentEnginePackage(settings) ?: LORD_TTS_PACKAGE
        return availableEngines(appContext)
            .firstOrNull { it.packageName == selected }
            ?.label ?: "Lord TTS"
    }
}
