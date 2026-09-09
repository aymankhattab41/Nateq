package com.aymankhattab.nateq.core.audio.engine

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
 * مستقل عن مفتاح «التحويل التلقائي»، لأنه تفضيلُ نطقٍ عام (اللغة التي
 * يقرأها التطبيق تُنطق بمحركها المحدد حتى لو عُطِّل التحويل). ترتيب القرار:
 * 1) محرك/صوت هدف التحويل التلقائي إن طابقت لغتُه لغةَ النص الطالبة.
 * 2) وإلا تفضيلُ المحرك/الصوت الصريح للغة الطلب نفسها.
 * 3) وإلا null → يختار المزوّد محركَه العام (المحدَّد من المستخدم أو التلقائي).
 *
 * منطق نقي قابل للاختبار الآلي دون أجهزة؛ التحقق من تثبيت المحرك/صحة الصوت
 * يبقى على المزوّد عند الربط.
 */
object LanguageSpeechRouter {

    fun route(
        matchesRequest: Boolean,
        convertEngine: String?,
        convertVoiceName: String?,
        perLanguageEngine: String?,
        perLanguageVoiceName: String?
    ): RoutedEngine {
        val engine = if (matchesRequest) {
            convertEngine ?: perLanguageEngine
        } else {
            perLanguageEngine
        }
        val voiceName = if (matchesRequest) {
            convertVoiceName ?: perLanguageVoiceName
        } else {
            perLanguageVoiceName
        }
        return RoutedEngine(engine, voiceName)
    }
}