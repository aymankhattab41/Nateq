package com.aymankhattab.nateq.core.audio.announcement

import com.aymankhattab.nateq.core.common.VisibleLanguages
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
     * 1) [forced] — مفتاح النطق الصريح إن حُدِّد،
     * 2) لغة الفئة المحفوظة من شاشة الفئات،
     * 3) لغة معرّف الصوت المحفوظ ([languageOfVoiceId])،
     * 4) لغة التطبيق الفعلية.
     *
     * ⚠️ **تحذير معماري:** [forced] هو «مفتاح لغة النطق العامة»
     * (`settings.getAnnouncementSpeechLanguage()`) وهو مُصمَّم لفئة
     * **الأرقام حصراً** (بُني لها في الجولة 11). **ممنوع تمريره من أي
     * فئة أخرى** (الساعة/المتصل/الإشعارات…) — المسار الوحيد الصالح
     * لتمريره هو نهج فئة الأرقام في `TimeAnnouncementManager`
     * (`effectiveNumberSpeechIsEnglish`). كل مستدعٍ لفئة غير الأرقام
     * يمرّر `forced = null` دائماً فيبدأ الترتيب من لغة الفئة المحفوظة
     * ولا يتأثر بمفتاح اللغة العام إطلاقاً — **عند إضافة فئة إعلانية
     * جديدة لا تمرّر [forced] البتة، بل categoryLang وصوت فئتها**؛ تمريرُه
     * بطريق الخطأ كان سبب «تحكم لغة نطق الأرقام في فئة الساعة» (انتهاك
     * استقلال الفئات).
     */
    fun resolve(
        forced: String?,
        categoryLang: String?,
        savedVoiceId: String?,
        appLanguage: String
    ): String {
        val candidate = when {
            !forced.isNullOrBlank() ->
                if (LanguageCode.isEnglish(forced)) ENGLISH else ARABIC
            !categoryLang.isNullOrBlank() ->
                if (LanguageCode.isEnglish(categoryLang)) ENGLISH else ARABIC
            else -> languageOfVoiceId(savedVoiceId)
                ?: if (LanguageCode.isEnglish(appLanguage)) ENGLISH else ARABIC
        }
        if (!VisibleLanguages.isVisible(candidate)) {
            return when {
                VisibleLanguages.isVisible(ARABIC) -> ARABIC
                VisibleLanguages.isVisible(ENGLISH) -> ENGLISH
                VisibleLanguages.visibleSet().isNotEmpty() ->
                    VisibleLanguages.visibleSet().first()
                else -> candidate
            }
        }
        return candidate
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