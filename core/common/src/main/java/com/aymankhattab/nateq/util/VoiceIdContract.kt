package com.aymankhattab.nateq.util

import java.util.Locale
import java.util.logging.Logger

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

    /**
     * تحذير JVM-safe (لا `android.util.Log`): هذه الوحدة نقيّة وتُختبر
     * على JVM مباشرةً، فاستخدام Stub الأندرويد كان سيفشل الاختبار.
     */
    private val warnLog = Logger.getLogger(VoiceIdContract::class.java.name)

    /** أسماء بديلة تهجئةً لنفس الصوت تُوحَّد إلى الاسم المعتمد. */
    private val VOICE_NAME_ALIASES = mapOf(
        "leila" to "Laila",
        "layla" to "Laila",
        "tariq" to "Tarik",
        "tarek" to "Tarik",
        "tarick" to "Tarik"
    )

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
     * توحيد اسم الصوت إلى اسمٍ قانوني واحد (canonical): يطبّق الأسماء
     * البديلة المعروفة (Tarek/Tariq/Tarik → Tarik، Leila/Layla → Laila)
     * مع إزالة لاحقة العرض بين قوسين، دون تقليص أسماء موسّعة مجهولة
     * (مثل "Laila Extra") حتى تبقى المطابقة الصارمة سليمة.
     */
    fun canonicalVoiceName(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val noParen = trimmed.substringBefore('(').trim().ifEmpty { trimmed }
        // تطابق الاسم كاملاً (بدون قوس) أو الرمز الأول كبديل تهجئة.
        VOICE_NAME_ALIASES[noParen.lowercase(Locale.ROOT)]?.let { return it }
        VOICE_NAME_ALIASES[trimmed.lowercase(Locale.ROOT)]?.let { return it }
        val first = noParen.substringBefore(' ').trim()
        val firstAlias = VOICE_NAME_ALIASES[first.lowercase(Locale.ROOT)]
        if (firstAlias != null && noParen.contains(' ')) {
            // اسم بديل مرفق بلاحقة جودة/وصف (مثل "Tarek Compact") →
            // يُوحَّد إلى الاسم القانوني مع الحفاظ على دلالة الاسم فقط.
            return firstAlias
        }
        return noParen
    }

    /** هل الاسمان يشيـران إلى الصوت نفسه بعد توحيد الأسماء البديلة؟ */
    fun sameVoiceName(a: String?, b: String?): Boolean {
        val ca = canonicalVoiceName(a)
        val cb = canonicalVoiceName(b)
        if (ca.isEmpty() || cb.isEmpty()) return false
        return ca.equals(cb, ignoreCase = true)
    }

    /** هل المعرّف/الاسم يتطابق مع صوتٍ قانونيٍّ معيّن بعد التوحيد؟ */
    fun matchesCanonicalVoiceName(candidate: String?, target: String?): Boolean {
        if (candidate.isNullOrBlank() || target.isNullOrBlank()) return false
        if (sameVoiceName(candidate, target)) return true
        val c = canonicalVoiceName(candidate)
        val t = canonicalVoiceName(target)
        if (c.isEmpty() || t.isEmpty()) return false
        return candidate.contains(t, ignoreCase = true) ||
            t.contains(c, ignoreCase = true)
    }

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
     * البند ١٩: معرّفات الأصوات النظامية المُعلَنة في tts_engine.xml ليست
     * أسماءً حقيقية لأصوات المحركات المدمجة. حفظُها تحت محرك مدمج يبدو
     * صحيحاً ثم يسقط النطق صامتاً عند أول استخدام، لذا تُرفض صراحةً مع
     * تحذيرٍ يسمّى المحرك والمعرّف المزيّف.
     */
    private val GENERIC_SYSTEM_VOICE_ID = Regex(
        "^[a-zA-Z]{2}(-[a-zA-Z0-9]{2,4})?$"
    )

    private fun isGenericSystemVoiceId(voiceId: String): Boolean {
        val v = voiceId.trim()
        return GENERIC_SYSTEM_VOICE_ID.matches(v) ||
            v.equals("Device Default", ignoreCase = true)
    }

    /** رفضٌ مع تحذير صريح — يُستخدم في فروع المحركات المدمجة فقط. */
    private fun rejectFakeEmbeddedVoice(
        engine: String,
        voiceId: String
    ): Boolean {
        warnLog.warning(
            "VoiceIdContract: معرّف صوت نظامي مزيّف [$voiceId] " +
                "تحت محرك مدمج [$engine] — مرفوض"
        )
        return false
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
            if (isGenericSystemVoiceId(voiceId)) {
                return rejectFakeEmbeddedVoice(engine, voiceId)
            }
            val isGoogle = voiceId.contains("-x-") ||
                voiceId.endsWith("-local")
            val isAcapela = voiceId.startsWith("ar_", ignoreCase = true) ||
                voiceId.startsWith("en_", ignoreCase = true) ||
                voiceId.startsWith("ara-", ignoreCase = true) ||
                voiceId.startsWith("eng-", ignoreCase = true) ||
                voiceId.startsWith("fra-", ignoreCase = true) ||
                voiceId.contains("acapela", ignoreCase = true)
            val isSamsung = voiceId.contains("_f00") ||
                voiceId.contains("_m00")
            if (isGoogle || isAcapela || isSamsung) return false
            return true
        }

        val isMicrosoftEmbedded = lowerEng == "microsoft" ||
            lowerEng == "microsoft_embedded" ||
            lowerEng.contains("microsoft")

        if (isMicrosoftEmbedded) {
            if (isGenericSystemVoiceId(voiceId)) {
                return rejectFakeEmbeddedVoice(engine, voiceId)
            }
            val isGoogle = voiceId.contains("-x-") ||
                voiceId.endsWith("-local")
            val isAcapela = voiceId.startsWith("ar_", ignoreCase = true) ||
                voiceId.startsWith("en_", ignoreCase = true) ||
                voiceId.startsWith("ara-", ignoreCase = true) ||
                voiceId.startsWith("eng-", ignoreCase = true) ||
                voiceId.startsWith("fra-", ignoreCase = true) ||
                voiceId.contains("acapela", ignoreCase = true)
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
            if (isAcapelaEmbedded && isGenericSystemVoiceId(voiceId)) {
                return rejectFakeEmbeddedVoice(engine, voiceId)
            }
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
                if (voiceId.contains("com.acapela", ignoreCase = true) ||
                    voiceId.contains("acapelagroup", ignoreCase = true) ||
                    voiceId.startsWith("ara-sau-", ignoreCase = true) ||
                    voiceId.startsWith("ara-egy-", ignoreCase = true) ||
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