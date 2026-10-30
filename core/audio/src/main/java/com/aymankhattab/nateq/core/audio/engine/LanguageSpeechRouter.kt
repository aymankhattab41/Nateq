package com.aymankhattab.nateq.core.audio.engine

import com.aymankhattab.nateq.core.common.VisibleEngines

/** نتيجة توجيه المحرك/الصوت للطلب الحالي (بلا أشرطة — تُدار منفصلة). */
data class RoutedEngine(
    val engine: String?,
    val voiceName: String?
)

/**
 * يوجّه محرك/صوت النطق للطلب الحالي في مساري النطق العام
 * ([NateqTtsService.synthesizeSingle]/[NateqTtsService.synthesizeMixed]):
 *
 * يهدف إلى جعل تفضيل المحرك/الصوت للغةٍ معيّنة سارياً **دائماً** وبشكلٍ
 * مستقل عن مفتاح «التحويل التلقائي»، لأنه تفضيلُ نطقٍ عام.
 */
object LanguageSpeechRouter {

    fun route(
        matchesRequest: Boolean,
        convertEngine: String?,
        convertVoiceName: String?,
        perLanguageEngine: String?,
        perLanguageVoiceName: String?,
        defaultEngine: String? = null
    ): RoutedEngine {
        val safeDefault = defaultEngine.takeIf {
            VisibleEngines.isVisible(it)
        }
        val safePerLang = perLanguageEngine.takeIf {
            VisibleEngines.isVisible(it)
        }
        val safeConvert = convertEngine.takeIf {
            VisibleEngines.isVisible(it)
        }

        val fallback = safePerLang ?: safeDefault
        val engine = if (matchesRequest) {
            safeConvert ?: fallback
        } else {
            fallback
        }
        val voiceName = if (matchesRequest && safeConvert != null) {
            convertVoiceName ?: perLanguageVoiceName
        } else {
            perLanguageVoiceName
        }
        return RoutedEngine(engine, voiceName)
    }
}
