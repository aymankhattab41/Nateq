package com.aymankhattab.nateq.core.audio.providers

import java.util.Locale

/** صوتٌ مثبَّت فعلياً في محرك Vocalizer المدمج، مقروءاً من قائمة المضيف. */
internal data class InstalledVocalizerVoice(
    val name: String,
    val language: String?
)

/**
 * يختار اسم الصوت الذي يُطلب من المضيف فعلاً بدل السقوط على اسمٍ ثابت
 * (كان «Laila»/«Samantha») قد لا يكون منزّلاً أصلاً.
 *
 * كان الصوتُ المطلوب إن لم يكن من كتالوج النماذج الثابت يُستبدل باسمٍ
 * ثابت، فإن لم يكن ذلك الاسم منزّلاً لم يخرج صوتٌ إطلاقاً رغم أن للمحرك
 * أصواتاً أخرى منزّلة وصالحة. الاختيار هنا من **المثبَّت** لا من الثابت:
 * 1. الاسم المطلوب نفسه إن كان مثبتاً (تطابق تام غير حسّاس لحالة الأحرف).
 * 2. أول صوت مثبت يطابق لغة الطلب.
 * 3. أول صوت مثبت (لئلا يُسقَط النطق).
 * 4. الاسم المطلوب كما هو إن لم تُعرَف أي أصوات (يقرر المضيف بنفسه).
 */
internal fun chooseVocalizerVoiceName(
    requestedName: String,
    requestedLanguage: String?,
    installed: List<InstalledVocalizerVoice>
): String {
    val requested = requestedName.trim()
    if (installed.isEmpty()) return requested
    val exact = installed.firstOrNull {
        it.name.equals(requested, ignoreCase = true)
    }
    if (exact != null) return exact.name
    val lang = requestedLanguage?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (lang.isNotEmpty()) {
        val sameLanguage = installed.firstOrNull { voice ->
            val voiceLang = voice.language
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
            voiceLang.isNotEmpty() &&
                (voiceLang == lang ||
                    voiceLang.startsWith("$lang-") ||
                    lang.startsWith("$voiceLang-"))
        }
        if (sameLanguage != null) return sameLanguage.name
    }
    return installed.first().name
}
