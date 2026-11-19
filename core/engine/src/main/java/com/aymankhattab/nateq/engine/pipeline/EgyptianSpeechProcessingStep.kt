package com.aymankhattab.nateq.engine.pipeline

import java.text.Normalizer

/**
 * معالجة مصرية/تشكيل تلقائي خفيف للنص العربي قبل أي محرك (أفضل جهد،
 * قابل للتوسعة). عند التفعيل:
 * - يضيف تشكيلًا خفيفًا لكلمات عربية شائعة (معجم صغير) لتحسين النطق.
 * - يطبّق بدائل نطق مصرية لكلمات/عبارات شائعة.
 *
 * بند 5: إضافة التشكيل تُطبَّق فقط مع المحركات التي تنطقه صحيحاً
 * (مثل Google)؛ وتُتخطّى لمحركات Vocalizer المدمج ومايكروسوفت أو
 * غير المعروف منها، مع تجريد أي تشكيل مُضاف حتى لا يُنطق خطأ.
 *
 * لا تغيّر النص عند التعطيل. لا تمس نصًا غير عربي أو رموزًا محجوزة.
 */
internal class EgyptianSpeechProcessingStep(
    private val enabledProvider: () -> Boolean,
    private val allowTashkeelProvider: () -> Boolean = { true }
) : TextProcessingStep {

    override fun apply(input: String): String {
        if (!enabledProvider()) return input
        if (input.isEmpty()) return input
        // لا نعالج نصًا خاليًا من العربية
        if (!ARABIC_CHAR_REGEX.containsMatchIn(input)) return input

        var out = Normalizer.normalize(input, Normalizer.Form.NFC)
        out = applyWordMap(out, COMMON_EGYPTIAN_REPLACEMENTS)
        val allowTashkeel = allowTashkeelProvider()
        if (allowTashkeel) {
            out = applyWordMap(out, COMMON_TASHKEEL_WORDS)
        } else {
            // بند 5: المحرك لا ينطق التشكيل صحيحاً (Vocalizer/مايكروسوفت
            // أو غير معروف) — نتجنب إضافة أي تشكيل بإزالة الحركات
            // (بما في ذلك ما تحمله بدائل القاموس المصرية).
            out = TashkeelStripStep.apply(out)
        }
        return out
    }

    private fun applyWordMap(text: String, map: Map<String, String>): String {
        if (map.isEmpty()) return text
        var result = text
        // تطبيق على حدود الكلمات العربية لتقليل الاستبدالات الخاطئة
        for ((src, dst) in map) {
            if (!result.contains(src)) continue
            val pattern = Regex(
                "(?<![\\p{L}\\p{M}\\u0640])" +
                    Regex.escape(src) +
                    "(?![\\p{L}\\p{M}\\u0640])"
            )
            result = pattern.replace(result) { dst }
        }
        return result
    }

    companion object {
        /** إجمالي مدخلات القاموس (بدائل مصرية + تشكيل خفيف). */
        fun dictionaryEntryCount(): Int =
            COMMON_EGYPTIAN_REPLACEMENTS.size + COMMON_TASHKEEL_WORDS.size

        /** عدد بدائل النطق/الصياغة المصرية. */
        fun egyptianReplacementsCount(): Int =
            COMMON_EGYPTIAN_REPLACEMENTS.size

        /** عدد كلمات التشكيل الخفيف. */
        fun tashkeelWordsCount(): Int = COMMON_TASHKEEL_WORDS.size

        private val ARABIC_CHAR_REGEX = Regex("[\\u0600-\\u06FF]")

        /**
         * بدائل نطق/صياغة مصرية شائعة (كلمات وعبارات). قابلة للتوسعة.
         * تفضّل صيغًا منطوقة أوضح للمحركات دون تغيير المعنى قدر الإمكان.
         */
        private val COMMON_EGYPTIAN_REPLACEMENTS:
            LinkedHashMap<String, String> =
            linkedMapOf(
                "ازاي" to "إِزَّاي",
                "إزاي" to "إِزَّاي",
                "ازيك" to "إِزَّيَّك",
                "إزيك" to "إِزَّيَّك",
                "ازاى" to "إِزَّاي",
                "عايز" to "عَايِز",
                "عايزة" to "عَايْزَة",
                "عاوز" to "عَاوِز",
                "عاوزه" to "عَاوْزَة",
                "عاوزة" to "عَاوْزَة",
                "ماشي" to "مَاشِي",
                "مافيش" to "مَافِيش",
                "مفيش" to "مَافِيش",
                "كده" to "كِدَه",
                "كدا" to "كِدَه",
                "ده" to "دَه",
                "دي" to "دِي",
                "دول" to "دُول",
                "النهارده" to "النَّهَارْدَه",
                "انهارده" to "النَّهَارْدَه",
                "امبارح" to "إِمْبَارِح",
                "إمبارح" to "إِمْبَارِح",
                "بكره" to "بُكْرَه",
                "بكرة" to "بُكْرَه",
                "دلوقتي" to "دِلْوَقْتِي",
                "دلوقت" to "دِلْوَقْتِي",
                "عشان" to "عَلَشَان",
                "علشان" to "عَلَشَان",
                "ليه" to "لِيه",
                "ايوه" to "أَيْوَه",
                "أيوه" to "أَيْوَه",
                "آه" to "آه",
                "لأ" to "لَأ",
                "علطول" to "عَلَطُول",
                "خالص" to "خَالِص",
                "اوي" to "أَوِي",
                "أوي" to "أَوِي",
                "قوي" to "قَوِي",
                "جنيه" to "جُنِيه",
                "جنيهات" to "جُنِيهَات"
            )

        /**
         * تشكيل تلقائي خفيف لكلمات عربية فصحى/شائعة (عينة قابلة للتوسعة).
         */
        private val COMMON_TASHKEEL_WORDS: LinkedHashMap<String, String> =
            linkedMapOf(
                "مرحبا" to "مَرْحَبًا",
                "مرحباً" to "مَرْحَبًا",
                "السلام" to "السَّلَام",
                "عليكم" to "عَلَيْكُم",
                "شكرا" to "شُكْرًا",
                "شكراً" to "شُكْرًا",
                "من فضلك" to "مِنْ فَضْلِك",
                "لو سمحت" to "لَوْ سَمَحْت",
                "نعم" to "نَعَم",
                "لا" to "لَا",
                "حسنا" to "حَسَنًا",
                "حسناً" to "حَسَنًا",
                "جيدا" to "جَيِّدًا",
                "جيداً" to "جَيِّدًا",
                "الان" to "الآن",
                "الآن" to "الآن",
                "اليوم" to "الْيَوْم",
                "غدا" to "غَدًا",
                "غداً" to "غَدًا",
                "امس" to "أَمْس",
                "أمس" to "أَمْس",
                "صباح" to "صَبَاح",
                "مساء" to "مَسَاء",
                "مصر" to "مِصْر",
                "القاهرة" to "الْقَاهِرَة",
                "الله" to "الله",
                "ان شاء الله" to "إِنْ شَاءَ الله",
                "إن شاء الله" to "إِنْ شَاءَ الله",
                "ما شاء الله" to "مَا شَاءَ الله",
                "الحمد لله" to "الْحَمْدُ لِلَّه"
            )
    }
}