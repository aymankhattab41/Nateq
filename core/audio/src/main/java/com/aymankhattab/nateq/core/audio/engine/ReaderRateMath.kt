package com.aymankhattab.nateq.core.audio.engine

/**
 * حسابات السرعة/الصوت النقية لمسار القراءة — فُصلت من [NateqTtsService]
 * دون تغيير سلوك. الدوال `internal` في الحزمة نفسها فتبقى مرئية لكل
 * مستدعيها القديم (الخدمة واختبارات `SpeechRateMathTest`) بلا تعديل.
 */

/**
 * يحوّل نسبةَ سرعةِ قارئ الشاشة (100 = طبيعي) إلى معامل (1.0 = طبيعي) —
 * TextToSpeech.setSpeechRate يتوقع 1.0 عند الطبيعي لا النسبةَ المئوية نفسها.
 * أي نسبة غير موجبة (0/سلبية) تُعتبر 100 (طبيعي محايد).
 */
internal fun readerRateFromPercent(reqRate: Float): Float {
    val safe = if (reqRate > 0f) reqRate else 100f
    return safe / 100f
}

/**
 * السرعة النهائية للنطق — نموذج الضرب: سرعةُ القارئ معاملٌ أَساسٌ يُضرب
 * في معامل LORD (تفضيل التطبيق) ثم مضاعف السرعة العام (إن فعّله المستخدم)،
 * ثم يُقصّ على نطاقٍ آمن (0.1..6.0). عند اتباع سرعة القارئ يكون
 * المعامل = 1.0 فيُبقى الخرج = سرعة القارئ × المضاعف.
 *
 * **حدود السرعة — فرق موثّق (بند ١١):** هذا القصّ (0.1..6.0) هو حدّ
 * التخطيط في هذه الخدمة. ثم يأتي [SystemVoiceProvider] ليقصّ القيمة
 * مجدداً بحدٍّ خاصّ بكل محرك (`EngineQuirks.maxSafeSpeechRate`،
 * افتراضياً 2.5) لأن بعض المحركات يتجاهل الأوامر أو يشوّه الصوت فوق
 * حدّه — حدّ أدنى من هنا عمداً، وليس تعارضاً بين المسارات.
 */
internal fun computeFinalSpeechRate(
    readerRate: Float,
    nateqMultiplier: Float,
    boost: Float = 1.0f
): Float = (readerRate * nateqMultiplier * boost).coerceIn(0.1f, 6.0f)

/**
 * مستوى الصوت النهائي لمقطع القراءة: مستوى الصوت الأساسي (تفضيل التطبيق)
 * مضروباً في مضاعف الصوت العام (إن فعّله المستخدم)، ثم يُقصّ على النطاق
 * الكامل (0.0..1.0) — أعلى مستوى متاح للنظام بلا تشويه (كسب >1 يُقصف).
 */
internal fun computeFinalVolume(
    volume: Float,
    boost: Float = 1.0f
): Float = (volume * boost).coerceIn(0f, 1f)
