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
    fun normalizeLanguageCode(code: String?): String =
        // **بند 6.2:** Locale.ROOT صراحةً في التحويل الأحرفي — رموز ISO
        // أسماءٌ ثابتة لا تخضع لقواعد المحلي الحالي (مثل التركية التي
        // تحوّل I بلا نقطة فتكسر "IND"→"ınd" ولا تُطابق أي مدخل).
        code?.lowercase(Locale.ROOT)?.let {
        when (it) {
            "ara" -> LanguageCode.AR.tag
            "eng" -> LanguageCode.EN.tag
            "fra" -> "fr"
            "deu" -> "de"
            "spa" -> "es"
            "ita" -> "it"
            "rus" -> "ru"
            "zho" -> "zh"
            "jpn" -> "ja"
            "kor" -> "ko"
            // بند 5.4: توسعة الخريطة — الرموز غير الممطوبة كانت تُعاد
            // كما هي (ISO-3) فتُفقد مطابقة كتالوج الأصوات لتلك اللغات.
            "tur" -> "tr"
            "urd" -> "ur"
            "hin" -> "hi"
            "ben" -> "bn"
            "fas" -> "fa"
            "por" -> "pt"
            "nld" -> "nl"
            "pol" -> "pl"
            "swe" -> "sv"
            "dan" -> "da"
            "nor" -> "no"
            "fin" -> "fi"
            "ces" -> "cs"
            "ell" -> "el"
            "heb" -> "he"
            "vie" -> "vi"
            "tha" -> "th"
            "ind" -> "id"
            "msa" -> "ms"
            "ukr" -> "uk"
            "tam" -> "ta"
            "tel" -> "te"
            "swa" -> "sw"
            "amh" -> "am"
            "som" -> "so"
            "azj" -> "az"
            "kur" -> "ku"
            "pus" -> "ps"
            "tgk" -> "tg"
            "uzb" -> "uz"
            "kaz" -> "kk"
            "mon" -> "mn"
            "lao" -> "lo"
            "khm" -> "km"
            "mya" -> "my"
            "glg" -> "gl"
            "cat" -> "ca"
            "eus" -> "eu"
            "ron" -> "ro"
            "bul" -> "bg"
            "srp" -> "sr"
            "hrv" -> "hr"
            "slv" -> "sl"
            "lit" -> "lt"
            "lav" -> "lv"
            "est" -> "et"
            "isl" -> "is"
            "mkd" -> "mk"
            "geo" -> "ka"
            "arm" -> "hy"
            else -> it
        }
    } ?: LanguageCode.AR.tag

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
     *  (०१२३) إلى أرقام غربية (0123456789) لأن أنماط \d لا تطابقها،
     *  مع تحويل الفاصلتين العربيتين: العشرية ٫ U+066B إلى «.» والفاصلة
     *  الألفية ٬ U+066C إلى «،» غربية — «١٫٥» و«١،٥٠٠» بلا هذا التحويل
     *  كانتا تتركان رقمين مقطوعين بلا نطق مبلغٍ (لا يقرأهما نمط العملة). */
    fun normalizeIndicDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val c = ch.code
            sb.append(
                when {
                    c in 0x0660..0x0669 -> (c - 0x0660 + '0'.code).toChar()
                    c in 0x06F0..0x06F9 -> (c - 0x06F0 + '0'.code).toChar()
                    c in 0x0966..0x096F -> (c - 0x0966 + '0'.code).toChar()
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
        val code = OTP_CODE.containsMatchIn(norm)
        return keyword && code
    }

    private val OTP_KEYWORD = Regex(
        "رمز التحقق|رمز التفعيل|رمز التأكيد|كود التحقق|" +
            "كود التفعيل|كود التأكيد|رمز الأمان|كود الأمان|" +
            "الرقم السري|الرمز السري|كلمة المرور|كلمة السر|" +
            "رقم التحقق|رقم التفعيل|otp|one[ -]?time password|" +
            "verification code|activation code|" +
            "confirmation code|security code|" +
            "passcode|تأكيد الدخول|كود الدخول",
        RegexOption.IGNORE_CASE
    )

    /** كود 4-8 خانات قد تحوي فاصلَ - أو مسافة بين خاناتها
     *  (مثل 123-456 أو 1234 5678). */
    private val OTP_CODE = Regex("(?<![0-9])[0-9](?:[- ]?[0-9]){3,7}(?![0-9])")

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