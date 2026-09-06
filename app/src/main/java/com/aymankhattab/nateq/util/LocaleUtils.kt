package com.aymankhattab.nateq.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** أدوات توحيد رموز اللغات والبلدان بين شكلَي ISO-2 وISO-3. */
object LocaleUtils {
    /**
     * توحيد رمز اللغة من الشكل ISO-3 (ara, eng) إلى ISO-2 (ar, en).
     * يُستخدم مع أصوات المتحدثين (مثل سماعات بلوتوث) التي ترسل رموزاً
     * موسّعة، ولا نريد أن يضيع التعرف عليها في النطق التلقائي.
     * @return رمز ISO-2 مثل "ar" أو null عند عدم معرفة الرمز.
     */
    fun normalizeLanguageCode(code: String?): String = code?.lowercase()?.let {
        when (it) {
            "ara" -> "ar"
            "eng" -> "en"
            "fra" -> "fr"
            "deu" -> "de"
            "spa" -> "es"
            "ita" -> "it"
            "rus" -> "ru"
            "zho" -> "zh"
            "jpn" -> "ja"
            "kor" -> "ko"
            else -> it
        }
    } ?: "ar"

    /** توحيد رمز البلد من الشكل ISO-3 (EGY, USA) إلى ISO-2 (EG, US). */
    fun normalizeCountryCode(code: String?): String? = code?.uppercase()?.let {
        when (it) {
            "EGY" -> "EG"
            "USA" -> "US"
            "GBR" -> "GB"
            "SAU" -> "SA"
            "ARE" -> "AE"
            "AUS" -> "AU"
            "CAN" -> "CA"
            "FRA" -> "FR"
            "DEU" -> "DE"
            "ESP" -> "ES"
            "ITA" -> "IT"
            else -> it
        }
    }

    /** هل يحتوي النص على أي حرف عربي (الأساسي + الإضافة + الممتد-A
     *  + نماذج العرض A/B للنصوص القديمة)؟ */
    fun containsArabic(text: String): Boolean {
        return text.any {
            it in '\u0600'..'\u06FF' ||
                it in '\u0750'..'\u077F' ||
                it in '\u08A0'..'\u08FF' ||
                it in '\uFB50'..'\uFDFF' ||
                it in '\uFE70'..'\uFEFF'
        }
    }

    /** تطبيع الأرقام الشرقية (٠١٢٣٤٥٦٧٨٩) والفارسية (۰۱۲۳) والهندية
     *  (०१२३) إلى أرقام غربية (0123456789) لأن أنماط \d لا تطابقها. */
    fun normalizeIndicDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val c = ch.code
            sb.append(
                when {
                    c in 0x0660..0x0669 -> (c - 0x0660 + '0'.code).toChar()
                    c in 0x06F0..0x06F9 -> (c - 0x06F0 + '0'.code).toChar()
                    c in 0x0966..0x096F -> (c - 0x0966 + '0'.code).toChar()
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** جلب سلسلة مورد بلغة نطق محددة (وليست لغة واجهة التطبيق):
     * تتيح لمستقبلات النطق (رسائل/مكالمات/بطارية) أن تُعلن بلسان
     * الصوت المختار (ar-EG / en-US) حتى لو كانت واجهة التطبيق
     * بالعربية أو الإنجليزية. الجلب يجبر اللغة المطلوبة صراحةً عبر
     * Context مستقل فلا يؤثر تبديل لغة الواجهة على سلاسل النطق.
     * @return قيمة المورد باللغة المطلوبة. */
    fun stringForSpeech(context: Context, languageTag: String, arabicRes: Int, englishRes: Int): String {
        val isArabic = languageTag.startsWith("ar", ignoreCase = true)
        if (isArabic) {
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag("ar"))
            }
            return context.createConfigurationContext(config).getString(arabicRes)
        }
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("en"))
        }
        return context.createConfigurationContext(config).getString(englishRes)
    }
}