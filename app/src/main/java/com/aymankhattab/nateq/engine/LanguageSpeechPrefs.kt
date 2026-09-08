package com.aymankhattab.nateq.engine

import com.aymankhattab.nateq.util.LanguageCode
import com.google.gson.Gson

/**
 * تفضيلات نطق لغةٍ واحدة داخل نظام التحويل التلقائي:
 * المحرك الذي ينطق به هذه اللغة، اسم الصوت داخله، ومركّبات الصوت
 * (سرعة/نبرة/مستوى صوت). كيان خالص (بلا أي Android) قابل للاختبار مباشرة.
 *
 * مفتاح الخريطة هو «كود اللغة» الموحّد (ISO-2 مثل ar/en/fr/de/…) الناتج
 * من [ConvertPreferencesCodec.normalizeLanguageTag] حتى تتطابق مفاتيح
 * المكتشف مع مفاتيح المحفوظ مهما وصل من قارئ الشاشة بأي صيغة.
 */
data class LanguageSpeechPrefs(
    /** حزمة محرك TTS الذي ينطق به هذه اللغة (مثل com.google.android.tts). */
    val engine: String? = null,
    /** اسم الصوت داخل المحرك المختار لهذه اللغة (اختياري). */
    val voiceName: String? = null,
    /** سرعة النطق (1.0 = طبيعي، 0.5 نصف، 2.0 ضعف). */
    val rate: Float = 1.0f,
    /** نبرة الصوت (1.0 = طبيعي، أقل أخفض، أعلى أعلى). */
    val pitch: Float = 1.0f,
    /** مستوى الصوت (0.0 صامت - 1.0 كامل). */
    val volume: Float = 1.0f
) {
    /** هل توجد أي تعديلات فعليّة تستوجب تطبيق التحويل حين النطق؟ */
    val hasAdjustment: Boolean
        get() = engine != null || voiceName != null ||
            rate != 1.0f || pitch != 1.0f || volume != 1.0f
}

/**
 * ترميز وتطبيع تفضيلات التحويل لكل اللغات، وك.:JSON والترحيل من السلوتات
 * القديمة. منطق خالص بلا Context (قابل للاختبار عبر JUnit النقي).
 */
object ConvertPreferencesCodec {

    private val gson = Gson()

    /** رموز ISO-3 الشائعة المفضّلة لإرجاعها إلى ISO-2 الموحّد للخريطة. */
    private val ISO3_TO_ISO2 = mapOf(
        "ara" to LanguageCode.AR.tag, "eng" to LanguageCode.EN.tag, "fra" to "fr", "deu" to "de",
        "spa" to "es", "ita" to "it", "rus" to "ru", "zho" to "zh",
        "jpn" to "ja", "kor" to "ko", "por" to "pt", "tur" to "tr",
        "nld" to "nl", "ell" to "el", "swe" to "sv", "pol" to "pl",
        "ces" to "cs", "ukr" to "uk", "hin" to "hi", "heb" to "he",
        "fas" to "fa", "urd" to "ur", "tha" to "th", "vie" to "vi",
        "ind" to "id", "msa" to "ms", "cat" to "ca", "dan" to "da",
        "fin" to "fi", "hun" to "hu", "ron" to "ro", "slk" to "sk",
        "lit" to "lt", "lav" to "lv", "est" to "et", "bul" to "bg",
        "hrv" to "hr", "srp" to "sr", "slv" to "sl", "mkd" to "mk",
        "bel" to "be", "aze" to "az", "kat" to "ka", "hye" to "hy",
        "eus" to "eu", "glg" to "gl", "cym" to "cy", "afr" to "af",
        "swa" to "sw", "tam" to "ta", "tel" to "te", "ben" to "bn",
        "mal" to "ml", "kan" to "kn", "mar" to "mr", "pan" to "pa",
        "guj" to "gu", "urd" to "ur", "nob" to "nb", "nno" to "nn"
    )

    /**
     * يوحّد علامة لغة (languageTag) إلى كود اللغة ISO-2 (ar/en/fr/de/…):
     * يجرّد البلد/البديل، يحوّل ISO-3 إلى ISO-2، ويوحّد حالة الأحرف — فيتطابق
     * مفتاح الخريطة دائماً بين ما يُكتشف والمحفوظ ومفتاح وقت قراءة الطلب.
     */
    fun normalizeLanguageTag(languageTag: String): String {
        if (languageTag.isBlank()) return "und"
        val code = languageTag.trim().substringBefore('-').substringBefore('_').lowercase()
        if (code.isEmpty()) return "und"
        return ISO3_TO_ISO2[code] ?: code
    }

    /** بناء مدخل الحفظ: يعقّم الفراغ وحقولًا خارج النطاق. مدخل بلا تعديلات
     *  (كل القيم الافتراضية) يعني «لا تحويل» ويُحذف من الخريطة عند الحفظ. */
    fun entryForSave(
        engine: String?,
        voiceName: String?,
        rate: Float,
        pitch: Float,
        volume: Float
    ): LanguageSpeechPrefs = LanguageSpeechPrefs(
        engine = engine?.takeIf { it.isNotBlank() },
        voiceName = voiceName?.takeIf { it.isNotBlank() },
        rate = rate.coerceIn(0f, 2f),
        pitch = pitch.coerceIn(0f, 2f),
        volume = volume.coerceIn(0f, 1f)
    )

    /** تجزئة الخريطة من JSON بأمان: JSON فاسد → فارغة، والقيم الغائبة/الشاذة
     *  تُعوَّض بالافتراضي (تجزئة يدوية عبر JsonParser فلا يعتمد على بناء
     *  منعكس للأصناف وبالتالي لا يقع في قيم شاذة من المهاجمين/الإصدارات). */
    fun fromJson(json: String?): Map<String, LanguageSpeechPrefs> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = try {
            com.google.gson.JsonParser.parseString(json)
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()
        if (!root.isJsonObject) return emptyMap()

        val out = LinkedHashMap<String, LanguageSpeechPrefs>()
        for ((key, value) in root.asJsonObject.entrySet()) {
            if (!value.isJsonObject) continue
            val e = value.asJsonObject
            val engine = if (e.has("engine") && !e.get("engine").isJsonNull)
                e.get("engine").asString.takeIf { it.isNotBlank() } else null
            val voiceName = if (e.has("voiceName") && !e.get("voiceName").isJsonNull)
                e.get("voiceName").asString.takeIf { it.isNotBlank() } else null
            val rate = if (e.has("rate")) e.get("rate").asFloat else 1.0f
            val pitch = if (e.has("pitch")) e.get("pitch").asFloat else 1.0f
            val volume = if (e.has("volume")) e.get("volume").asFloat else 1.0f
            out[key] = LanguageSpeechPrefs(
                engine = engine,
                voiceName = voiceName,
                rate = rate.coerceIn(0f, 2f),
                pitch = pitch.coerceIn(0f, 2f),
                volume = volume.coerceIn(0f, 1f)
            )
        }
        return out
    }

    /** تسلسل الخريطة إلى JSON للخزن في SharedPreferences. */
    fun toJson(map: Map<String, LanguageSpeechPrefs>): String =
        gson.toJson(map, GsonTypes.mapStringOf(LanguageSpeechPrefs::class.java))

    /**
     * ترحيل سلوت قديم (1/2) إلى الخريطة الديناميكية بمفتاح لغة موحّد.
     * إن كان مفتاح اللسان غائباً نستخدم [defaultKey] (السلوت الأول كان
     * حقّها العربية والثاني الإنجليزية). القيّم الافتراضية لا تُكتب.
     */
    fun mergeLegacySlot(
        target: MutableMap<String, LanguageSpeechPrefs>,
        legacyLanguage: String?,
        slot: LanguageSpeechPrefs,
        defaultKey: String
    ) {
        val key = normalizeLanguageTag(legacyLanguage ?: defaultKey)
        if (slot.hasAdjustment) target[key] = slot
    }
}