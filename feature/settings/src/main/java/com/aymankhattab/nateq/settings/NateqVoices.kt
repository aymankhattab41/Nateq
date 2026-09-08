package com.aymankhattab.nateq.settings

import java.util.Locale

/**
 * صوت ناطق (نطق Lord TTS) معروض في قوائم الشاشة.
 *
 * مبسّطة إلى لغتين فقط: "العربية" و"الإنجليزية". تُبنى قائمة الأصوات في
 * [VoiceSelectionFragment] بعد الانضمام للسياق (لا يجوز في مُنشئ/خاصية
 * تستدعي getString()) ويستهلكها كل أقسام الشاشة، لذا أُخرج النوع إلى
 * هذا الملف ليكون مشتركاً بين الفصيل وضابطات الأقسام.
 */
internal data class NateqVoice(
    val name: String,
    val languageTag: String,
    val displayName: String,
    val locale: Locale
)