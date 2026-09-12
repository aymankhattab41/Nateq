package com.aymankhattab.nateq.engine

/**
 * التهجئة الذكية ونطق التشكيل عند التنقل الحرفي عبر TalkBack: يُرسل قارئ
 * الشاشة حرفاً واحداً (مع التشكيل صراحةً) إلى المحرك فيُنطق اسم الحرف مع
 * حركته («بَ» ← «باء مفتوحة»)، وللإنجليزية يُستعمل الأبجدية الصوتية
 * الدولية (NATO) مع تمييز الحروف الكبيرة («A» ← «Capital Alpha»).
 *
 * لا تُهجّأ إلا المدخلات المكوّنة من حرف هجائي واحد (عربي أو لاتيني)
 * وما يلحقه من علامات تشكيل عربية؛ أي مدخل آخر يُعاد كما هو (null)
 * ليبقى النص في مسار المعالجة المعتاد.
 */
object SmartSpeller {

    /** أسماء الحروف العربية للنطق الصوتي (منزوعة الحركات). */
    private val ARABIC_LETTERS = mapOf(
        'ء' to "همزة",
        // الهمزات تُنطق مميّزة عن الألف المجرّدة (تحسين نطق)
        'أ' to "ألف بهمزة",
        'إ' to "ألف بهمزة",
        'آ' to "ألف ممدودة",
        'ٱ' to "ألف وصل",
        'ؤ' to "واو مهموزة",
        'ئ' to "ياء مهموزة",
        'ا' to "ألف",
        'ب' to "باء",
        'ت' to "تاء",
        'ث' to "ثاء",
        'ج' to "جيم",
        'ح' to "حاء",
        'خ' to "خاء",
        'د' to "دال",
        'ذ' to "ذال",
        'ر' to "راء",
        'ز' to "زاي",
        'س' to "سين",
        'ش' to "شين",
        'ص' to "صاد",
        'ض' to "ضاد",
        'ط' to "طاء",
        'ظ' to "ظاء",
        'ع' to "عين",
        'غ' to "غين",
        'ف' to "فاء",
        'ق' to "قاف",
        'ك' to "كاف",
        'ل' to "لام",
        'م' to "ميم",
        'ن' to "نون",
        'ه' to "هاء",
        'و' to "واو",
        'ي' to "ياء",
        'ة' to "تاء مربوطة"
    )

    /** ألفبائية NATO الصوتية للحروف اللاتينية (تُنطق بحروفها الكبيرة). */
    private val NATO_LETTERS = mapOf(
        'a' to "Alpha",
        'b' to "Bravo",
        'c' to "Charlie",
        'd' to "Delta",
        'e' to "Echo",
        'f' to "Foxtrot",
        'g' to "Golf",
        'h' to "Hotel",
        'i' to "India",
        'j' to "Juliett",
        'k' to "Kilo",
        'l' to "Lima",
        'm' to "Mike",
        'n' to "November",
        'o' to "Oscar",
        'p' to "Papa",
        'q' to "Quebec",
        'r' to "Romeo",
        's' to "Sierra",
        't' to "Tango",
        'u' to "Uniform",
        'v' to "Victor",
        'w' to "Whiskey",
        'x' to "Xray",
        'y' to "Yankee",
        'z' to "Zulu"
    )

    /** علامات التشكيل العربية وألفاظها في نطق الحرف المفرد. */
    private val TASHKEEL_WORDS = mapOf(
        '\u064B' to "منصوبة", // تنوين فتح
        '\u064C' to "مرفوعة", // تنوين ضم
        '\u064D' to "مجرورة", // تنوين كسر
        '\u064E' to "مفتوحة", // فتحة
        '\u064F' to "مضمومة", // ضمة
        '\u0650' to "مكسورة", // كسرة
        '\u0651' to "مشددة", // شدة
        '\u0652' to "ساكنة" // سكون
    )

    /** مجموعة علامات التشكيل المسموح لاحقتها حرفاً عربياً مفرداً. */
    private val ALLOWED_MARKS = TASHKEEL_WORDS.keys +
        // علامات إضافية تُجرَّد صمتاً (ألف خنجرية ومذيداتها، همزات وصل…)
        '\u0670' + '\u0653' + '\u0654' + '\u0655' + '\u0656' + '\u0657' +
        '\u0658' + '\u0659' + '\u065A' + '\u065B' + '\u065C' + '\u065D' +
        '\u065E' + '\u065F' + '\u0671'

    /**
     * يهجّئ المدخل إن كان حرفاً هجائياً واحداً (عربي أو لاتيني) وقد
     * تلحقه علامات تشكيل عربية؛ وإلا يعيد null ليصار المعالجة العادية.
     */
    fun spell(input: String, languageTag: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val base = text[0]
        // بقية الحروف يجب أن تكون علامات تشكيل عربية فقط لا أحرفاً ثانية
        for (i in 1 until text.length) {
            if (text[i] !in ALLOWED_MARKS) return null
        }
        return when {
            base in ARABIC_LETTERS -> spellArabic(base, text)
            base in NATO_LETTERS || base.lowercaseChar() in NATO_LETTERS ->
                spellEnglish(base)
            else -> null
        }
    }

    /** نطق الحرف العربي مع صفات حركاته (شدة أولاً ثم الحركة الأساسية). */
    private fun spellArabic(base: Char, text: String): String {
        val marks = text.substring(1)
        val shadda = marks.contains('\u0651')
        val vowel = marks.replace("\u0651", "")
            .firstOrNull { it != base }?.let { TASHKEEL_WORDS[it] }
        val adjectives = buildList {
            if (shadda) add("مشددة")
            if (vowel != null) add(vowel)
        }
        val baseName = ARABIC_LETTERS.getValue(base)
        return if (adjectives.isEmpty()) {
            baseName
        } else {
            baseName + " " + adjectives.joinToString(" ")
        }
    }

    /** النطق اللاتيني: NATO مع «Capital» للحروف الكبيرة. */
    private fun spellEnglish(base: Char): String {
        val word = NATO_LETTERS.getValue(base.lowercaseChar())
        return if (base.isUpperCase()) "Capital $word" else word
    }
}