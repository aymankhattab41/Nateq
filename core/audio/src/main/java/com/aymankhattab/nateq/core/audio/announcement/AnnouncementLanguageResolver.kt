package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.VoiceIdContract

/**
 * يحلّ لغة نطق فئة إعلانية (ساعة/أرقام/…) من مصادر القرار المرتّبة:
 * مفتاح النطق EN/AR الصريح ثم لغة الفئة المحفوظة ثم استنتاج لغة
 * معرّف الصوت المحفوظ ثم لغة التطبيق الفعلية. النطق يدعم العربية
 * والإنجليزية فقط فتؤول أيُّ لغة أخرى إلى العربية (سقوطٌ قائم).
 *
 * يتصدّى تحديداً لأصوات المحركات المكتشفة المحفوظة بمعرّفاتها الخام
 * (مثل com.google.android.tts:eng-usa و com.samsung.SMT:en-us) التي
 * كانت فحوص البادئات المنطقية (nateq-en/en-local/en-US) لا تلتقطها —
 * فتُنطق فئةُ الساعة/الأرقام بلغة التطبيق رغم حفظ الصوت واللغة
 * المطلوبين (أندرويد 16/17).
 */
object AnnouncementLanguageResolver {

    /** وسم الإنجليزية — لغةُ النطق القاصرة على عربية/إنجليزية. */
    const val ENGLISH = "en"

    /** وسم العربية — لغةُ النطق القاصرة على عربية/إنجليزية. */
    const val ARABIC = "ar"

    /**
     * لغةُ النطق المرتّبة للفئة:
     * 1) مفتاح النطق الصريح إن حُدِّد،
     * 2) لغة الفئة المحفوظة من شاشة الفئات،
     * 3) لغة معرّف الصوت المحفوظ ([languageOfVoiceId])،
     * 4) لغة التطبيق الفعلية.
     */
    fun resolve(
        forced: String?,
        categoryLang: String?,
        savedVoiceId: String?,
        appLanguage: String
    ): String {
        if (!forced.isNullOrBlank()) {
            return if (LanguageCode.isEnglish(forced)) ENGLISH else ARABIC
        }
        if (!categoryLang.isNullOrBlank()) {
            return if (LanguageCode.isEnglish(categoryLang)) {
                ENGLISH
            } else {
                ARABIC
            }
        }
        languageOfVoiceId(savedVoiceId)?.let { return it }
        return if (LanguageCode.isEnglish(appLanguage)) ENGLISH else ARABIC
    }

    /**
     * لغةُ معرّف صوتٍ محفوظ دون مفتاحٍ صريح ولا لغةِ فئة:
     * - منطقي (ar-EG/en-US/nateq-* أو en-local/ar-local) عبر
     *   تطبيع [VoiceIdContract] الموحّد،
     * - صوتَ محركٍ مكتشف (com.google.android.tts:eng-usa) عبر وسم
     *   اللسان بعد الفاصلة: الاصطلاح السائد في جوجل/سامسونج/ivona
     *   (eng-usa/en-us/en_GB/arb/ara-…). غير المعروف يعيد null فيُحسم
     *   القرارُ بالمصدر التالي.
     */
    fun languageOfVoiceId(voiceId: String?): String? {
        if (voiceId.isNullOrBlank()) return null
        VoiceIdContract.normalize(voiceId)?.let { normalized ->
            if (normalized.startsWith("en", ignoreCase = true)) {
                return ENGLISH
            }
            if (normalized.startsWith("ar", ignoreCase = true)) {
                return ARABIC
            }
        }
        val raw = voiceId.substringAfter(':', missingDelimiterValue = "")
        if (raw.startsWith("en", ignoreCase = true)) return ENGLISH
        if (raw.startsWith("ar", ignoreCase = true)) return ARABIC
        return null
    }
}