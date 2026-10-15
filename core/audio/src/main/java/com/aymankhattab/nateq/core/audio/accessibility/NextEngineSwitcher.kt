package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * يبدّل محرك Lord TTS الافتراضي إلى المحرك التالي في ترتيب النظام.
 *
 * لا يكتب إعداد TTS الافتراضي المحمي في أندرويد؛ بل يحدّث فئة Lord TTS
 * الافتراضية، لذلك يعمل من البلاطة حتى عندما لا تكون خدمة المحرك متصلة.
 */
object NextEngineSwitcher {

    data class SwitchResult(
        val packageName: String,
        val label: String,
        val engineCount: Int
    )

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
        val next = nextEngine(
            engines,
            settings.getEngineForCategory(
                SettingsRepository.VOICE_CATEGORY_DEFAULT
            )
        ) ?: return null
        settings.setEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT,
            next.packageName
        )
        return SwitchResult(next.packageName, next.label, engines.size)
    }

    /** المحرك المختار حالياً وتسمية عرضه في البلاطة. */
    fun selectedEngineLabel(context: Context): String? {
        val appContext = context.applicationContext
        val settings = SettingsRepository.create(appContext)
        val selected = settings.getEngineForCategory(
            SettingsRepository.VOICE_CATEGORY_DEFAULT
        ) ?: return null
        return EnginePicker.installedEngines(appContext)
            .firstOrNull { it.packageName == selected }
            ?.label
    }
}
