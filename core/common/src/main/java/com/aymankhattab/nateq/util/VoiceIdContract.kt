package com.aymankhattab.nateq.util

/**
 * عقد موحّد لمعرّفات الأصوات — المصدر الوحيد لصيغة معرّف صوت أي لغة،
 * تعتمده كل الجهات لتبقى متطابقة دوماً:
 * - الإعلان للنظام (onGetVoices / tts_engine.xml) عبر
 *   [com.aymankhattab.nateq.core.audio.engine.VoiceCatalog]
 * - واصفات المزوّدين ([VoiceDescriptor.id] في listVoices)
 * - القيم المخزنة في الإعدادات (SettingsRepository)
 *
 * الصيغة الخارجية ثابتة عمداً: ar-EG / en-US / "<lang>" (مثل fr/de/es).
 * أي تغيير فيها يكسر التفضيلات المخزنة لدى المستخدمين وأسماء الأصوات
 * المعلنة في tts_engine.xml. يمنع العقد انحيازَ أحد الأطراف عن الآخرين
 * مجدداً — حدث سابقاً:
 * كان المزوّد يصدر "nateq-<lang>-local" بينما يعلن الكتالوج "<lang>-local"
 * فتساقط الصوت المختار في كل لغة غير ar/en.
 *
 * ملاحظة تاريخية: استُخدمت سابقاً صيغة "<lang>-local" لكنها كسرت شاشة
 * إعدادات TTS في سامسونج (تحوّل أسماء الأصوات عبر Locale.forLanguageTag
 * فتفشل "fr-local" فتنهار القائمة) — فاستُبدلت بالصيغة البسيطة الصالحة
 * كـ Locale، مع ترقية القيم القديمة المخزنة في [normalize].
 */
object VoiceIdContract {

    private val LEGACY_LOCAL = Regex(
        "^nateq-(.+)-local$",
        RegexOption.IGNORE_CASE
    )

    /** المعرّف الموحّد لصوت لغةٍ معيّنة (يُقصى ISO-3→ISO-2 أولاً عبر
     *  [LocaleUtils]). الصيغة صالحة كـ Locale (ar-EG/en-US/أو "fr") لأن
     *  شاشة TTS في سامسونج تفتت أسماء الأصوات عبر Locale.forLanguageTag
     *  فتنهار على أي اسم غير صالح كان سابقاً "<lang>-local". */
    fun createId(language: String): String {
        val norm = LocaleUtils.normalizeLanguageCode(language)
        return when (norm) {
            LanguageCode.AR.tag -> "ar-EG"
            LanguageCode.EN.tag -> "en-US"
            else -> norm.lowercase(java.util.Locale.ROOT)
        }
    }

    /** لغات الأصوات المُعلَنة الثابتة في tts_engine.xml — المصدر الوحيد
     *  للفهرس المضمون في الإعلان (ar/en أساسيتان + القارّات fr/de/es).
     *  أي لغة إضافية تُكتشف ديناميكياً عبر المحركات ولا تُضمَّن هنا. */
    fun declaredLanguages(): List<String> =
        listOf(
            LanguageCode.AR.tag,
            LanguageCode.EN.tag,
            "fr",
            "de",
            "es"
        )

    /** أسماء الأصوات المُعلَنة الثابتة في tts_engine.xml بنفس الترتيب —
     *  يلتزمها الوجهان معاً (CHECK_TTS_DATA عبر declaredVoices و onGetVoices
     *  عبر supportedLocales في VoiceCatalog) فيتطابقان مع الملف حرفاً
     *  بحرف. كلُّ اسمٍ مولَّد من [createId] بالعقد نفسه فلا ينفرد عنه. */
    fun declaredVoiceNames(): List<String> =
        declaredLanguages().map { createId(it) }

    /**
     * يطبّع معرّفاً وارداً/مخزّناً إلى الصيغة الموحّدة:
     * - القديمان من نسخ ما قبل التسمية: "nateq-ar*"/"nateq-en*" و
     *   "ar-local"/"en-local" → ar-EG/en-US
     * - البديل الأحدث الخاطئ: "nateq-<lang>-local" → "<lang>"
     * - الصيغة السابقة المعطوبة على سامسونج: "<lang>-local" → "<lang>"
     *   (تُرقّى تلقائياً حتى لا تُكسر القيم المخزنة قديماً)
     * - الصيغة الموحّدة الحالية تمرّ كما هي.
     */
    fun normalize(id: String?): String? {
        if (id == null) return null
        val trimmed = id.trim()
        val legacyLocal = LEGACY_LOCAL.matchEntire(trimmed)
        if (legacyLocal != null) return createId(legacyLocal.groupValues[1])
        return when {
            trimmed.contains("nateq-ar", ignoreCase = true) ||
                trimmed.equals("ar-local", ignoreCase = true) -> "ar-EG"
            trimmed.contains("nateq-en", ignoreCase = true) ||
                trimmed.equals("en-local", ignoreCase = true) -> "en-US"
            trimmed.endsWith("-local", ignoreCase = true) ->
                createId(trimmed.removeSuffix("-local"))
            else -> trimmed
        }
    }

    /**
     * يكشف لغة اسم صوتٍ مُعلَن ثابت (tts_engine.xml) ثم يصدر العقد لها —
     * التحقق أن أي اسم إعلان يُعاد توليده عبر [createId] بنفسه، فلا تنحرف
     * أسماء الملفات الثابتة عن صيغة العقد نحو صيغةٍ أجنبية مجدداً.
     */
    fun createIdForDeclared(declared: String): String {
        val norm = normalize(declared) ?: return declared
        return if (norm.endsWith("-local")) {
            createId(norm.removeSuffix("-local"))
        } else {
            norm
        }
    }

    /**
     * التحقق مما إذا كان اسم الصوت ينتمي للمحرك المعين (الجولة 53 - الأمر 3).
     */
    fun isVoiceBelongingToEngine(
        engine: String?,
        voiceId: String?,
        knownVoices: List<String>? = null
    ): Boolean {
        if (engine.isNullOrBlank() || voiceId.isNullOrBlank()) return true
        if (!knownVoices.isNullOrEmpty()) {
            return voiceId in knownVoices
        }
        val lowerEng = engine.lowercase()
        val isVocalizerEmbedded = lowerEng == "vocalizer" ||
            lowerEng == "vocalizer_embedded" ||
            lowerEng.contains("vocalizer")

        if (isVocalizerEmbedded) {
            val isGoogle = voiceId.contains("-x-") ||
                voiceId.endsWith("-local")
            val isAcapela = voiceId.startsWith("ar_") ||
                voiceId.startsWith("en_") ||
                voiceId.startsWith("ara-") ||
                voiceId.startsWith("eng-") ||
                voiceId.startsWith("fra-") ||
                voiceId.contains("acapela")
            val isSamsung = voiceId.contains("_f00") ||
                voiceId.contains("_m00")
            if (isGoogle || isAcapela || isSamsung) return false
            return true
        }

        val isMicrosoftEmbedded = lowerEng == "microsoft" ||
            lowerEng == "microsoft_embedded" ||
            lowerEng.contains("microsoft")

        if (isMicrosoftEmbedded) {
            val isGoogle = voiceId.contains("-x-") ||
                voiceId.endsWith("-local")
            val isAcapela = voiceId.startsWith("ar_") ||
                voiceId.startsWith("en_") ||
                voiceId.startsWith("ara-") ||
                voiceId.startsWith("eng-") ||
                voiceId.startsWith("fra-") ||
                voiceId.contains("acapela")
            val isSamsung = voiceId.contains("_f00") ||
                voiceId.contains("_m00")
            if (isGoogle || isAcapela || isSamsung) return false
            return true
        }

        if (lowerEng == "com.google.android.tts") {
            if (voiceId.startsWith("ara-", ignoreCase = true) ||
                voiceId.startsWith("eng-", ignoreCase = true) ||
                voiceId.startsWith("fra-", ignoreCase = true) ||
                voiceId.startsWith("ar_", ignoreCase = true) ||
                voiceId.startsWith("en_", ignoreCase = true) ||
                voiceId.contains("vocalizer", ignoreCase = true) ||
                voiceId.contains("tarik", ignoreCase = true) ||
                voiceId.contains("tarek", ignoreCase = true) ||
                voiceId.contains("maged", ignoreCase = true) ||
                voiceId.contains("mariam", ignoreCase = true) ||
                voiceId.equals("laila", ignoreCase = true) ||
                voiceId.equals("leila", ignoreCase = true)
            ) {
                return false
            }
        }

        val isAcapelaEmbedded = lowerEng == "acapela" ||
            lowerEng == "acapela_embedded"
        val isAcapelaExternal = lowerEng.contains("acapela") &&
            !isAcapelaEmbedded

        if (isAcapelaEmbedded || isAcapelaExternal) {
            val isVocVoice = voiceId.startsWith("vocalizer") ||
                voiceId.contains("vocalizer") ||
                voiceId.contains("nuance") ||
                voiceId.contains("maged", ignoreCase = true) ||
                voiceId.contains("tarik", ignoreCase = true) ||
                voiceId.contains("tarek", ignoreCase = true) ||
                voiceId.contains("mariam", ignoreCase = true) ||
                (voiceId.equals("laila", ignoreCase = true) &&
                    !voiceId.startsWith("ara-", ignoreCase = true) &&
                    !voiceId.startsWith("ar_", ignoreCase = true))
            if (isVocVoice) return false

            if (isAcapelaEmbedded) {
                if (voiceId.startsWith("ara-", ignoreCase = true) ||
                    voiceId.startsWith("eng-", ignoreCase = true) ||
                    voiceId.startsWith("fra-", ignoreCase = true) ||
                    voiceId.startsWith("ar_", ignoreCase = true) ||
                    voiceId.startsWith("en_", ignoreCase = true) ||
                    voiceId.contains("com.acapela", ignoreCase = true) ||
                    voiceId.contains("acapelagroup", ignoreCase = true) ||
                    voiceId.contains("-x-") ||
                    voiceId.endsWith("-local") ||
                    voiceId.endsWith("-network") ||
                    voiceId.contains("_f00") ||
                    voiceId.contains("_m00")
                ) {
                    return false
                }
            } else {
                if (voiceId.startsWith(
                        "acapela_embedded:",
                        ignoreCase = true
                    ) ||
                    voiceId.startsWith("acapela:", ignoreCase = true) ||
                    (voiceId.contains("(") && voiceId.contains(")")) ||
                    voiceId.contains("_f00") ||
                    voiceId.contains("_m00")
                ) {
                    return false
                }
            }
        }

        if (lowerEng.contains("samsung")) {
            if (voiceId.startsWith("ara-", ignoreCase = true) ||
                voiceId.startsWith("eng-", ignoreCase = true) ||
                voiceId.contains("-x-") ||
                voiceId.contains("vocalizer", ignoreCase = true) ||
                voiceId.contains("tarik", ignoreCase = true) ||
                voiceId.contains("tarek", ignoreCase = true) ||
                voiceId.contains("maged", ignoreCase = true) ||
                voiceId.contains("mariam", ignoreCase = true)
            ) {
                return false
            }
        }

        return true
    }
}