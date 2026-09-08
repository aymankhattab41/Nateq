package com.aymankhattab.nateq.util

/**
 * عقد موحّد لمعرّفات الأصوات — المصدر الوحيد لصيغة معرّف صوت أي لغة،
 * تعتمده كل الجهات لتبقى متطابقة دوماً:
 * - الإعلان للنظام (onGetVoices / tts_engine.xml) عبر [com.aymankhattab.nateq.core.audio.engine.VoiceCatalog]
 * - واصفات المزوّدين ([VoiceDescriptor.id] في listVoices)
 * - القيم المخزنة في الإعدادات (SettingsRepository)
 *
 * الصيغة الخارجية ثابتة عمداً: ar-EG / en-US / "<lang>-local". أي تغيير فيها
 * يكسر التفضيلات المخزنة لدى المستخدمين وأسماء الأصوات المعلنة في
 * tts_engine.xml. يمنع العقد انحيازَ أحد الأطراف عن الآخرين مجدداً — حدث سابقاً:
 * كان المزوّد يصدر "nateq-<lang>-local" بينما يعلن الكتالوج "<lang>-local"
 * فتساقط الصوت المختار في كل لغة غير ar/en.
 */
object VoiceIdContract {

    private val LEGACY_LOCAL = Regex("^nateq-(.+)-local$", RegexOption.IGNORE_CASE)

    /** المعرّف الموحّد لصوت لغةٍ معيّنة (يُقصى ISO-3→ISO-2 أولاً عبر [LocaleUtils]). */
    fun createId(language: String): String {
        val norm = LocaleUtils.normalizeLanguageCode(language)
        return when (norm) {
            LanguageCode.AR.tag -> "ar-EG"
            LanguageCode.EN.tag -> "en-US"
            else -> "${norm.lowercase(java.util.Locale.ROOT)}-local"
        }
    }

    /**
     * يطبّع معرّفاً وارداً/مخزّناً إلى الصيغة الموحّدة:
     * - القديمان من نسخ ما قبل التسمية: "nateq-ar*"/"nateq-en*" و "ar-local"/"en-local" → ar-EG/en-US
     * - البديل الأحدث الخاطئ: "nateq-<lang>-local" → "<lang>-local" (عقد متطابق مع الكتالوج)
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
            else -> trimmed
        }
    }
}