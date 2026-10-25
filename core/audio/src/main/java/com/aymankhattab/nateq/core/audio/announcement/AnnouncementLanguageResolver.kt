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
        if (LanguageCode.isEnglish(appLanguage)) {
            return ENGLISH
        }
        return ARABIC
    }

    /**
     * حارس تعارض لغة الصوت مع لغة الإعلان المحسومة (الأمر 2، البند 5):
     * إن حُسمت لغة الإعلان ولم تطابق لغة صوت الفئة المحفوظ،
     * يتم تجاهل صوت الفئة واستعمال صوت اللغة المحسومة.
     */
    fun resolveVoiceWithConflictGuard(
        resolvedLanguage: String,
        savedVoiceId: String?,
        languageDefaultVoiceId: String?
    ): String? {
        if (savedVoiceId.isNullOrBlank()) return languageDefaultVoiceId
        val voiceLang = languageOfVoiceId(savedVoiceId)
        if (voiceLang != null &&
            !voiceLang.equals(resolvedLanguage, ignoreCase = true)
        ) {
            return languageDefaultVoiceId
        }
        return savedVoiceId
    }

    /**
     * لغةُ معرّف صوتٍ محفوظ عبر [VoiceIdContract].
     */
    fun languageOfVoiceId(voiceId: String?): String? =
        VoiceIdContract.languageOfVoiceId(voiceId)
}