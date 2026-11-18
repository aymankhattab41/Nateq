package com.aymankhattab.nateq.core.audio.announcement

import android.speech.tts.Voice
import android.util.Log
import com.aymankhattab.nateq.util.LanguageCode
import com.aymankhattab.nateq.util.LocaleUtils
import java.util.Locale

/**
 * حلّ أصوات الإعلانات من أصوات المحرك — كتلة منطقية نقية فُصلت من
 * [AnnouncementSpeaker] دون تغيير سلوك: تُفضَّل المطابقة الصريحة لمعرّف
 * الصوت، ثم مطابقة اللسان/البلد، ثم صوت بديل عند رفض المحرك صوتاً،
 * ثم إقليم بديل عند رفض اللسان المجرد (ظاهرة Vocalizer).
 *
 * نقطة الدخول القديمة [AnnouncementSpeaker.voiceFor] (ونظيرتاها)
 * تبقى وتفوّض إلى هنا فلا ينكسر أي استدعاء أو اختبار قديم.
 */
internal object AnnouncementVoiceResolver {
    private const val TAG = "NATEQ_TTS"

    /** حلّ صوت وحدةٍ لغوية من صوت المحرك: يفضّل المعرّف الصريح
     *  ([partVoice] كاسم صوت مخصص في إعدادات اللغة)؛ وإلا أفضلَ صوتٍ
     *  لسانُه لسانُ الوحدة — تُرجَّح مطابقةُ رمز البلد، ثم أيُّ صوتٍ
     *  باللغة — بدل الاعتماد على `setLanguage` وحده الذي قد لا يبدّل
     *  لغةَ نطق المحرك فعلياً (بند 18). ترجيحُ البلد يمنح تغييرَ
     *  اللغة/المحرك أثراً حقيقياً بدل «أول صوتٍ» ثابت بلغة النظام
     *  (كان تبديل لغة النطق لا يغيّر الصوتَ على بعض المحركات).
     *  null إن لم يوجد صوت ملائم — يبقى `setLanguage` سقوطاً. */
    fun voiceFor(
        voices: Collection<Voice>?,
        partVoice: String?,
        locale: Locale
    ): Voice? {
        if (voices.isNullOrEmpty()) return null
        partVoice?.let { vid ->
            // 1. تطابق تام
            voices.firstOrNull { it.name == vid }?.let { v ->
                Log.d(
                    TAG,
                    "[voiceFor] exact match: requested=$vid → " +
                        "found=${v.name}"
                )
                return v
            }
            // 2. تطابق بدون حساسية الحالة
            voices.firstOrNull {
                it.name.equals(vid, ignoreCase = true)
            }?.let { v ->
                Log.d(
                    TAG,
                    "[voiceFor] case-insensitive match: " +
                        "requested=$vid → found=${v.name}"
                )
                return v
            }
            // 3. تطابق بعد حذف اللاحقة (compact/enhanced/...)
            val suffixRegex = Regex(
                "-(compact|standard|enhanced|premium)$",
                RegexOption.IGNORE_CASE
            )
            val vidBase = vid.replace(suffixRegex, "")
            voices.firstOrNull { v ->
                v.name.replace(suffixRegex, "")
                    .equals(vidBase, ignoreCase = true)
            }?.let { v ->
                Log.d(
                    TAG,
                    "[voiceFor] normalized match: " +
                        "requested=$vid → found=${v.name}"
                )
                return v
            }
            // لم يُعثر على تطابق تام أو متسامح — سجّل الأسماء بنفس اللغة
            val lang = LocaleUtils.normalizeLanguageCode(
                locale.language
            )
            val available = voices.filter {
                LocaleUtils.normalizeLanguageCode(it.locale?.language) ==
                    lang
            }.map { it.name }
            Log.w(
                TAG,
                "[voiceFor] requested=$vid NOT FOUND. " +
                    "locale=$locale, same-lang voices=" +
                    available.take(10)
            )
        }
        val targetLang = LocaleUtils.normalizeLanguageCode(
            locale.language
        )
        val sameLanguage = voices.filter {
            LocaleUtils.normalizeLanguageCode(it.locale?.language) ==
                targetLang
        }
        if (sameLanguage.isEmpty()) {
            Log.w(
                TAG,
                "[voiceFor] no voice for lang=$targetLang in " +
                    "${voices.size} voices"
            )
            return null
        }
        val country = LocaleUtils.normalizeCountryCode(locale.country)
        if (!country.isNullOrEmpty()) {
            sameLanguage.firstOrNull {
                val c = LocaleUtils.normalizeCountryCode(
                    it.locale?.country
                )
                c?.equals(country, ignoreCase = true) == true
            }?.let { v ->
                Log.d(
                    TAG,
                    "[voiceFor] country match: ${v.name} (${v.locale})"
                )
                return v
            }
        }
        val first = sameLanguage.first()
        Log.w(
            TAG,
            "[voiceFor] fallback to first same-lang: " +
                "${first.name} (${first.locale})"
        )
        return first
    }

    /** صوتٌ عربي/نفسُ اللسان بديل عن صوتٍ مرفوض: بعض المحركات (ظاهرة
     *  Vocalizer) تعرض أصواتاً برموز مختلفة عن المعرّف المُخصَّص،
     *  فتنجح `setVoice` بغير الصوت المُفضَّل. يُستبعد المعرّضُ الفاشل
     *  ويُرجَّح صوتُ البلد المطابق ثم أيُّ صوتٍ باللغة (بلا صوتٌ
     *  أجنبيٍّ للوحدة)، وإلا null للرضى بالـ setLanguage. */
    fun fallbackVoiceFor(
        voices: Collection<Voice>?,
        locale: Locale,
        excludedName: String?
    ): Voice? {
        if (voices.isNullOrEmpty()) return null
        val targetLang = LocaleUtils.normalizeLanguageCode(
            locale.language
        )
        val eligible = voices.filter {
            LocaleUtils.normalizeLanguageCode(it.locale?.language) ==
                targetLang && it.name != excludedName
        }
        if (eligible.isEmpty()) return null
        val country = LocaleUtils.normalizeCountryCode(locale.country)
        return if (!country.isNullOrEmpty()) {
            eligible
                .firstOrNull {
                    val c = LocaleUtils.normalizeCountryCode(
                        it.locale?.country
                    )
                    c?.equals(country, ignoreCase = true) == true
                }
                ?: eligible.first()
        } else {
            eligible.first()
        }
    }

    /** حل لسان بديل مدعوم بإقليم عند رفض المحرك اللسان المجرد (مثل
     *  رفض Vocalizer لـ Locale("ar") بدون بلد): يفضل إقليم أول صوت
     *  مطابق في المحرك، وإلا إقليماً قياسياً شهيراً باللسان ذاته. */
    fun resolveFallbackLocale(
        locale: Locale,
        voices: Collection<Voice>?
    ): Locale? {
        val targetLang = LocaleUtils.normalizeLanguageCode(
            locale.language
        )
        val voiceMatch = voices?.firstOrNull {
            LocaleUtils.normalizeLanguageCode(it.locale?.language) ==
                targetLang
        }
        if (voiceMatch?.locale != null) {
            Log.d(
                TAG,
                "[resolveFallbackLocale] engine locale=" +
                    "${voiceMatch.locale} for $locale"
            )
            return voiceMatch.locale
        }
        return when (targetLang) {
            LanguageCode.AR.tag -> Locale.forLanguageTag("ar-AE")
            LanguageCode.EN.tag -> Locale.US
            "fr" -> Locale.FRANCE
            else -> null
        }
    }
}
