package com.aymankhattab.nateq.core.audio.engine.quirks

import android.speech.tts.Voice
import com.aymankhattab.nateq.util.LocaleUtils
import java.util.Locale

/**
 * بصمة صوتية وصفية — تُستخدم لإعادة اختيار نفس الصوت المنطقي حتى
 * عند تغيّر أسماء أصوات Vocalizer.
 * غير مرتبطة بـ `Voice.name` الأصلي (قد يتغير مع تحديث المحرك).
 *
 * [enginePackage] حزمة المحرك (مثل "es.codefactory.vocalizertts")
 * [languageTag] كود اللغة (مثل "ar" أو "en")
 * [countryTag] رمز البلد (مثل "SA" أو "US") — قد يكون فارغاً
 * [originalName] الاسم الأصلي للصوت وقت الحفظ — للرجوع إليه كملاذ أخير
 */
data class VoiceFingerprint(
    val enginePackage: String,
    val languageTag: String,
    val countryTag: String = "",
    val originalName: String = ""
) {
    /** يُعيد بصمة من صوت محرك حالي. */
    companion object {
        fun fromVoice(enginePackage: String, voice: Voice): VoiceFingerprint {
            val lang = LocaleUtils.normalizeLanguageCode(
                voice.locale?.language ?: ""
            )
            val country = LocaleUtils.normalizeCountryCode(
                voice.locale?.country
            ) ?: ""
            return VoiceFingerprint(
                enginePackage = enginePackage,
                languageTag = lang,
                countryTag = country,
                originalName = voice.name
            )
        }
    }

    /** يتحقق هل هذا الصوت يطابق البصمة (بترتيب أولوية):
     * 1. تطابق الاسم الأصلي (fallback أخير)
     * 2. نفس locale (language + country)
     * 3. نفس اللغة فقط
     */
    fun matches(voice: Voice): Boolean {
        // 1. الاسم الأصلي (fallback أخير)
        if (originalName.isNotBlank() && voice.name == originalName) return true

        val vLang = LocaleUtils.normalizeLanguageCode(
            voice.locale?.language ?: ""
        )
        val vCountry = LocaleUtils.normalizeCountryCode(
            voice.locale?.country
        ) ?: ""

        // 2. نفس locale (language + country)
        if (vLang == languageTag && vCountry == countryTag) return true

        // 3. نفس اللغة فقط
        if (vLang == languageTag) return true

        return false
    }
}