package com.aymankhattab.nateq.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** أدوات توحيد رموز اللغات والبلدان بين شكلَي ISO-2 وISO-3. */
object LocaleUtils {

    private val ISO3_TO_ISO2 = mapOf(
        "ara" to "ar", "eng" to "en", "fra" to "fr", "deu" to "de",
        "spa" to "es", "ita" to "it", "rus" to "ru", "zho" to "zh",
        "jpn" to "ja", "kor" to "ko", "tur" to "tr", "urd" to "ur",
        "hin" to "hi", "ben" to "bn", "fas" to "fa", "por" to "pt",
        "nld" to "nl", "pol" to "pl", "swe" to "sv", "dan" to "da",
        "nor" to "no", "fin" to "fi", "ces" to "cs", "ell" to "el",
        "heb" to "he", "vie" to "vi", "tha" to "th", "ind" to "id",
        "msa" to "ms", "ukr" to "uk", "tam" to "ta", "tel" to "te",
        "swa" to "sw", "amh" to "am", "som" to "so", "azj" to "az",
        "kur" to "ku", "pus" to "ps", "tgk" to "tg", "uzb" to "uz",
        "kaz" to "kk", "mon" to "mn", "lao" to "lo", "khm" to "km",
        "mya" to "my", "glg" to "gl", "cat" to "ca", "eus" to "eu",
        "ron" to "ro", "bul" to "bg", "srp" to "sr", "hrv" to "hr",
        "slv" to "sl", "lit" to "lt", "lav" to "lv", "est" to "et",
        "isl" to "is", "mkd" to "mk", "geo" to "ka", "arm" to "hy"
    )

    private val ISO3_COUNTRY_TO_ISO2 = mapOf(
        "EGY" to "EG", "USA" to "US", "GBR" to "GB", "SAU" to "SA",
        "ARE" to "AE", "AUS" to "AU", "CAN" to "CA", "FRA" to "FR",
        "DEU" to "DE", "ESP" to "ES", "ITA" to "IT", "RUS" to "RU",
        "TUR" to "TR", "IND" to "IN", "PAK" to "PK", "JOR" to "JO",
        "IRQ" to "IQ", "SYR" to "SY", "LBN" to "LB", "MAR" to "MA",
        "DZA" to "DZ", "TUN" to "TN", "KWT" to "KW", "QAT" to "QA",
        "BHR" to "BH", "OMN" to "OM", "YEM" to "YE", "SDN" to "SD"
    )

    /** صيغ لغوية نصية شائعة (من المحركات) → وسم BCP-47. */
    private val DISPLAY_NAME_TO_BCP47 = mapOf(
        "arabic" to "ar", "arabic (saudi arabia)" to "ar-SA",
        "arabic (egypt)" to "ar-EG", "arabic (uae)" to "ar-AE",
        "english" to "en", "english (united states)" to "en-US",
        "english (us)" to "en-US", "english (united kingdom)" to "en-GB",
        "english (uk)" to "en-GB", "french" to "fr",
        "french (france)" to "fr-FR", "german" to "de",
        "german (germany)" to "de-DE", "spanish" to "es",
        "spanish (spain)" to "es-ES", "italian" to "it",
        "italian (italy)" to "it-IT", "russian" to "ru",
        "turkish" to "tr", "portuguese" to "pt",
        "dutch" to "nl", "chinese" to "zh", "japanese" to "ja",
        "korean" to "ko", "hindi" to "hi", "urdu" to "ur",
        "persian" to "fa", "hebrew" to "he", "thai" to "th"
    )

    /**
     * تحويل صيغة لغة واردة من المحرك (وسم مركب مثل ara-SAU، اسم نصي مثل
     * "Arabic (Saudi Arabia)"، أو رمز ثلاثي) إلى وسم BCP-47 صحيح.
     * بند 6.
     */
    fun toBcp47Tag(raw: String?, fallbackToRaw: Boolean = true): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        DISPLAY_NAME_TO_BCP47[lower]?.let { return it }
        // وسوم مركبة أو رموز مفصولة.
        val parts = trimmed.replace('_', '-').split('-')
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return if (fallbackToRaw) trimmed else null
        val langRaw = parts[0].lowercase(Locale.ROOT)
        val lang = ISO3_TO_ISO2[langRaw] ?: langRaw
        if (lang.isEmpty()) return if (fallbackToRaw) trimmed else null
        // رمز لغة صالح عادة 2-3 أحرف؛ وإلا قد يكون اسماً غير معروف.
        val langLooksCode = lang.length in 2..3 && lang.all { it.isLetter() }
        if (!langLooksCode) {
            // جرّب كاسم عرضي جزئي/بديل أخير.
            val fromName = DISPLAY_NAME_TO_BCP47[lower]
            return fromName ?: if (fallbackToRaw) trimmed else null
        }
        if (parts.size == 1) return lang
        val regionRaw = parts[1]
        val region = normalizeRegionSubtag(regionRaw)
        return if (region != null) "$lang-$region" else lang
    }

    private fun normalizeRegionSubtag(raw: String): String? {
        val up = raw.uppercase(Locale.ROOT)
        if (up.length == 2 && up.all { it.isLetter() }) return up
        if (up.length == 3 && up.all { it.isLetter() }) {
            return ISO3_COUNTRY_TO_ISO2[up] ?: up
        }
        if (up.length == 3 && up.all { it.isDigit() }) return up
        return null
    }

    /**
     * توحيد رمز اللغة من الشكل ISO-3 (ara, eng) إلى ISO-2 (ar, en).
     * يقبل أيضاً وسوم مركبة مثل ara-SAU/ar_EG ويستخرج الجزء اللغوي.
     * @return رمز ISO-2 مثل "ar" (افتراضياً "ar" عند الفراغ/العدم).
     */
    fun normalizeLanguageCode(code: String?): String {
        val raw = code?.trim().orEmpty()
        if (raw.isEmpty()) return LanguageCode.AR.tag
        // بند 6: قبول صيغ المحرك المتنوعة (وسوم، أسماء نصية، رموز ثلاثية).
        val tag = toBcp47Tag(raw, fallbackToRaw = false)
        val langPart = (tag ?: raw)
            .substringBefore('-').substringBefore('_')
            .lowercase(Locale.ROOT)
        return ISO3_TO_ISO2[langPart] ?: langPart
    }

    /** توحيد رمز البلد من الشكل ISO-3 (EGY, USA) إلى ISO-2 (EG, US). */
    fun normalizeCountryCode(code: String?): String? =
        // بند 6.2: Locale.ROOT — كرموزَ اللغة أعلاه لا تخضع لِقواعد المحلي.
        code?.uppercase(Locale.ROOT)?.let {
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

    /** الجذر اللغوي لرمزِ لغةٍ/اسمِ صوتٍ ("ar-EG"/"ara"/"fr" → ar/fr):
     *  يفصل علامة البلد/اللاحقة أولاً ('-' أو '_') ثم يوحّد ISO-3→ISO-2
     *  عبر [normalizeLanguageCode] — تُستخدم في فحص الأصوات (CHECK_TTS_DATA)
     *  ومفاتيح النصوص التجريبية ليطابق طلبُ النظام أسماءَ الأصوات المعلنة
     *  مهما كان الشكل الوارد (ara/eng/fra أو ar-EG أو es-ES أو fr-local
     *  القديمة من نسخ سابقة لصيغة الأسماء). */
    fun languageRoot(tag: String): String =
        normalizeLanguageCode(
            tag.trim().substringBefore('-').substringBefore('_')
        )

    /** هل يحتوي النص على أي حرف عربي (الأساسي + الإضافة + الممتد-A
     *  + نماذج العرض A/B للنصوص القديمة)؟ */
    fun containsArabic(text: String): Boolean {
        return text.any {
            (it in '\u0600'..'\u06FF' && it != '\u061C') ||
                it in '\u0750'..'\u077F' ||
                it in '\u08A0'..'\u08FF' ||
                it in '\uFB50'..'\uFDFF' ||
                it in '\uFE70'..'\uFEFC'
        }
    }

    /** تطبيع الأرقام الشرقية (٠١٢٣٤٥٦٧٨٩) والفارسية (۰۱۲۳) والهندية
     *  (०१२३) وسائر أنظمة العدّ الهندية (بنغالية/تاميلية/تيلوغوية/
     *  ملايالامية/تايلندية/خميرية…) إلى أرقام غربية (0123456789) لأن
     *  أنماط \d لا تطابقها، مع تحويل الفاصلتين العربيتين: العشرية ٫
     *  U+066B إلى «.» والفاصلة الألفية ٬ U+066C إلى «،» غربية — «١٫٥»
     *  و«١،٥٠٠» بلا هذا التحويل كانتا تتركان رقمين مقطوعين بلا نطق
     *  مبلغٍ (لا يقرأهما نمط العملة)؛ وسائر أنظمة العدّ (بند 6-7) كانت
     *  تُسند للغة الأرقام وتصل للمحرك مكتوبةً بنظامها فلا تُقرأ أرقاماً. */
    fun normalizeIndicDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val c = ch.code
            sb.append(
                when {
                    c in 0x0660..0x0669 -> (c - 0x0660 + '0'.code).toChar()
                    c in 0x06F0..0x06F9 -> (c - 0x06F0 + '0'.code).toChar()
                    c in 0x0966..0x096F -> (c - 0x0966 + '0'.code).toChar()
                    c in 0x09E6..0x09EF -> (c - 0x09E6 + '0'.code).toChar()
                    c in 0x0A66..0x0A6F -> (c - 0x0A66 + '0'.code).toChar()
                    c in 0x0AE6..0x0AEF -> (c - 0x0AE6 + '0'.code).toChar()
                    c in 0x0B66..0x0B6F -> (c - 0x0B66 + '0'.code).toChar()
                    c in 0x0BE6..0x0BEF -> (c - 0x0BE6 + '0'.code).toChar()
                    c in 0x0C66..0x0C6F -> (c - 0x0C66 + '0'.code).toChar()
                    c in 0x0CE6..0x0CEF -> (c - 0x0CE6 + '0'.code).toChar()
                    c in 0x0D66..0x0D6F -> (c - 0x0D66 + '0'.code).toChar()
                    c in 0x0E50..0x0E59 -> (c - 0x0E50 + '0'.code).toChar()
                    c in 0x0ED0..0x0ED9 -> (c - 0x0ED0 + '0'.code).toChar()
                    c in 0x1040..0x1049 -> (c - 0x1040 + '0'.code).toChar()
                    c in 0x17E0..0x17E9 -> (c - 0x17E0 + '0'.code).toChar()
                    c in 0x1810..0x1819 -> (c - 0x1810 + '0'.code).toChar()
                    c in 0xFF10..0xFF19 -> (c - 0xFF10 + '0'.code).toChar()
                    c == 0x066B -> '.'   // فاصلة عشرية عربية («١٫٥»)
                    c == 0x066C -> ','   // فاصلة آلاف عربية («١٬٥٠٠»)
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** هل الرسالة نص تحقق بحرف (OTP)؟ — يُحمي المستخدم من نطق رموز التحقق
     *  المرسلة من البنوك والمنصات (كود التفعيل/كلمة المرور المؤقتة) بصوتٍ
     *  عالٍ في الأماكن العامة. الكشف يتطلب اجتماع شرطين (ليُقلّل من
     *  النتائج الكاذبة): وجود كلمة تحقق ووجود رقم من 4 إلى 8 خانات
     *  (تُسمح فواصل - أو مسافة واحدة بين الخانات مثل 123-456 أو 1234 5678).
     */
    fun containsOtp(text: String): Boolean {
        if (text.isBlank()) return false
        val norm = normalizeIndicDigits(text)
        val keyword = OTP_KEYWORD.containsMatchIn(norm)
        if (!keyword) return false
        val matches = OTP_CODE.findAll(norm)
        for (match in matches) {
            val candidate = match.value
            if (!DATE_STRUCTURE.matches(candidate)) {
                return true
            }
        }
        return false
    }

    private val DATE_STRUCTURE = Regex(
        """^(?:(?:19|20)\d{2}[- /]\d{1,2}[- /]\d{1,2}|""" +
            """\d{1,2}[- /]\d{1,2}[- /](?:19|20)\d{2})$"""
    )

    private val OTP_KEYWORD = Regex(
        "رمز التحقق|رمز التفعيل|رمز التأكيد|كود التحقق|" +
            "كود التفعيل|كود التأكيد|رمز الأمان|كود الأمان|" +
            "الرقم السري|الرمز السري|كلمة المرور|كلمة السر|" +
            "رقم التحقق|رقم التفعيل|otp|one[ -]?time password|" +
            "verification code|activation code|" +
            "confirmation code|security code|" +
            "passcode|تأكيد الدخول|كود الدخول|" +
            "کد تأیید|کد تایید|رمز تأیید|رمز تایید|" +
            "کد ورود|رمز ورود",
        RegexOption.IGNORE_CASE
    )

    /** كود 4-8 خانات قد تحوي فاصلَ - أو مسافة بين خاناتها
     *  (مثل 123-456 أو 1234 5678). */
    private val OTP_CODE = Regex(
        """(?:G-)?(?<![0-9]|(?:[0-9][- ]))""" +
            """[0-9](?:[- ]?[0-9]){3,7}""" +
            """(?!(?:[- ]?[0-9]))"""
    )

    /** جلب سلسلة مورد بلغة نطق محددة (وليست لغة واجهة التطبيق):
     * تتيح لمستقبلات النطق (رسائل/مكالمات/بطارية) أن تُعلن بلسان
     * الصوت المختار (ar-EG / en-US) حتى لو كانت واجهة التطبيق
     * بالعربية أو الإنجليزية. الجلب يجبر اللغة المطلوبة صراحةً عبر
     * Context مستقل فلا يؤثر تبديل لغة الواجهة على سلاسل النطق.
     * @return قيمة المورد باللغة المطلوبة. */
    fun stringForSpeech(
        context: Context,
        languageTag: String,
        arabicRes: Int,
        englishRes: Int
    ): String {
        if (LanguageCode.isArabic(languageTag)) {
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(LanguageCode.AR.tag))
            }
            return context.createConfigurationContext(config)
                .getString(arabicRes)
        }
        val config = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(LanguageCode.EN.tag))
        }
        return context.createConfigurationContext(config).getString(englishRes)
    }
}