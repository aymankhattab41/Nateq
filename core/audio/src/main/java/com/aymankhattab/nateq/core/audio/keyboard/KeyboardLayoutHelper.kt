package com.aymankhattab.nateq.core.audio.keyboard

/**
 * محددات وتخطيطات مفاتيح لوحة مفاتيح Lord TTS الميسّرة.
 * يحدد طبقات الحروف العربية، الإنجليزية، الرموز، والتشكيل،
 * مع النطق اللفظي والتهجئة التوضيحية لكل مفتاح.
 */
object KeyboardLayoutHelper {

    enum class KeyboardLayer {
        ARABIC,
        ENGLISH,
        SYMBOLS,
        TASHKEEL
    }

    enum class KeyType {
        CHARACTER,
        ACTION_BACKSPACE,
        ACTION_SPACE,
        ACTION_ENTER,
        ACTION_SWITCH_LANG,
        ACTION_SYMBOLS,
        ACTION_TASHKEEL,
        ACTION_VOICE,
        ACTION_CLIPBOARD,
        ACTION_HIDE
    }

    data class KeyModel(
        val label: String,
        val spokenText: String,
        val phonetic: String? = null,
        val type: KeyType = KeyType.CHARACTER,
        val widthWeight: Float = 1.0f
    )

    private val ARABIC_PHONETICS = mapOf(
        "أ" to "ألف - أسد",
        "إ" to "ألف مكسورة - إبريق",
        "آ" to "ألف مدّة - آية",
        "ا" to "ألف - أسد",
        "ب" to "باء - بطة",
        "ت" to "تاء - تفاحة",
        "ث" to "ثاء - ثعلب",
        "ج" to "جيم - جمل",
        "ح" to "حاء - حصان",
        "خ" to "خاء - خروف",
        "د" to "دال - ديك",
        "ذ" to "ذال - ذئب",
        "ر" to "راء - رمان",
        "ز" to "زاي - زرافة",
        "س" to "سين - سمكة",
        "ش" to "شين - شمس",
        "ص" to "صاد - صقر",
        "ض" to "ضاد - ضفدع",
        "ط" to "طاء - طائرة",
        "ظ" to "ظاء - ظرف",
        "ع" to "عين - عصفور",
        "غ" to "غين - غزال",
        "ف" to "فاء - فراشة",
        "ق" to "قاف - قلم",
        "ك" to "كاف - كتاب",
        "ل" to "لام - ليمون",
        "م" to "ميم - مسجد",
        "ن" to "نون - نخلة",
        "ه" to "هاء - هلال",
        "و" to "واو - وردة",
        "ي" to "ياء - يد",
        "ة" to "تاء مربوطة",
        "ى" to "ألف مقصورة",
        "ء" to "همزة على السطر",
        "ئ" to "همزة على نبرة",
        "ؤ" to "همزة على واو"
    )

    private val ENGLISH_PHONETICS = mapOf(
        "a" to "Alpha", "b" to "Bravo", "c" to "Charlie",
        "d" to "Delta", "e" to "Echo", "f" to "Foxtrot",
        "g" to "Golf", "h" to "Hotel", "i" to "India",
        "j" to "Juliett", "k" to "Kilo", "l" to "Lima",
        "m" to "Mike", "n" to "November", "o" to "Oscar",
        "p" to "Papa", "q" to "Quebec", "r" to "Romeo",
        "s" to "Sierra", "t" to "Tango", "u" to "Uniform",
        "v" to "Victor", "w" to "Whiskey", "x" to "X-ray",
        "y" to "Yankee", "z" to "Zulu"
    )

    fun getArabicRows(): List<List<KeyModel>> {
        val row1Chars = listOf(
            "ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"
        )
        val row1 = row1Chars.map { ch ->
            KeyModel(ch, ch, ARABIC_PHONETICS[ch])
        }

        val row2Chars = listOf(
            "ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"
        )
        val row2 = row2Chars.map { ch ->
            KeyModel(ch, ch, ARABIC_PHONETICS[ch])
        }

        val row3 = mutableListOf<KeyModel>()
        row3.add(
            KeyModel("ء", "همزة", ARABIC_PHONETICS["ء"], widthWeight = 1.0f)
        )
        val midChars = listOf("ذ", "د", "ز", "ر", "و", "ظ", "ة", "ى")
        midChars.forEach { ch ->
            row3.add(KeyModel(ch, ch, ARABIC_PHONETICS[ch]))
        }
        row3.add(
            KeyModel(
                label = "⌫",
                spokenText = "حذف",
                type = KeyType.ACTION_BACKSPACE,
                widthWeight = 1.3f
            )
        )

        val row4 = listOf(
            KeyModel(
                label = "?123",
                spokenText = "الأرقام والرموز",
                type = KeyType.ACTION_SYMBOLS,
                widthWeight = 1.3f
            ),
            KeyModel(
                label = "🌐",
                spokenText = "تبديل اللغة",
                type = KeyType.ACTION_SWITCH_LANG,
                widthWeight = 1.1f
            ),
            KeyModel(
                label = "ً",
                spokenText = "التشكيل",
                type = KeyType.ACTION_TASHKEEL,
                widthWeight = 1.1f
            ),
            KeyModel(
                label = "مسافة",
                spokenText = "مسافة",
                type = KeyType.ACTION_SPACE,
                widthWeight = 3.5f
            ),
            KeyModel(".", "نقطة", widthWeight = 1.0f),
            KeyModel(
                label = "↵",
                spokenText = "إدخال",
                type = KeyType.ACTION_ENTER,
                widthWeight = 1.5f
            )
        )

        return listOf(row1, row2, row3, row4)
    }

    fun getEnglishRows(isCaps: Boolean = false): List<List<KeyModel>> {
        val row1Chars = listOf(
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p"
        )
        val row1 = row1Chars.map { ch ->
            val label = if (isCaps) ch.uppercase() else ch
            KeyModel(label, label, ENGLISH_PHONETICS[ch])
        }

        val row2Chars = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val row2 = row2Chars.map { ch ->
            val label = if (isCaps) ch.uppercase() else ch
            KeyModel(label, label, ENGLISH_PHONETICS[ch])
        }

        val row3 = mutableListOf<KeyModel>()
        val shiftLabel = if (isCaps) "⇧" else "⇩"
        val shiftSpoken = if (isCaps) "أحرف كبيرة" else "أحرف صغيرة"
        row3.add(KeyModel(shiftLabel, shiftSpoken, widthWeight = 1.2f))
        val row3Chars = listOf("z", "x", "c", "v", "b", "n", "m")
        row3Chars.forEach { ch ->
            val label = if (isCaps) ch.uppercase() else ch
            row3.add(KeyModel(label, label, ENGLISH_PHONETICS[ch]))
        }
        row3.add(
            KeyModel(
                label = "⌫",
                spokenText = "حذف",
                type = KeyType.ACTION_BACKSPACE,
                widthWeight = 1.3f
            )
        )

        val row4 = listOf(
            KeyModel(
                label = "?123",
                spokenText = "الأرقام والرموز",
                type = KeyType.ACTION_SYMBOLS,
                widthWeight = 1.3f
            ),
            KeyModel(
                label = "🌐",
                spokenText = "تبديل اللغة",
                type = KeyType.ACTION_SWITCH_LANG,
                widthWeight = 1.2f
            ),
            KeyModel(
                label = "Space",
                spokenText = "مسافة",
                type = KeyType.ACTION_SPACE,
                widthWeight = 4.0f
            ),
            KeyModel(".", "نقطة", widthWeight = 1.0f),
            KeyModel(
                label = "↵",
                spokenText = "إدخال",
                type = KeyType.ACTION_ENTER,
                widthWeight = 1.5f
            )
        )

        return listOf(row1, row2, row3, row4)
    }

    fun getNumberRow(): List<KeyModel> {
        val numChars = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0"
        )
        return numChars.map { ch -> KeyModel(ch, ch) }
    }

    fun getSymbolsRows(): List<List<KeyModel>> {
        val row1 = getNumberRow()

        val row2Chars = listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")")
        val row2Spoken = listOf(
            "آت", "مربع", "دولار", "بالمئة", "واو العطف",
            "نجمة", "ناقص", "زائد", "قوس مفتوح", "قوس مغلق"
        )
        val row2 = row2Chars.mapIndexed { idx, ch ->
            KeyModel(ch, row2Spoken[idx])
        }

        val row3Chars = listOf("=", "/", "\\", "\"", "'", ":", ";", "!", "?")
        val row3Spoken = listOf(
            "يساوي", "شرطة مائلة", "شرطة مائلة عكسية",
            "علامة تنصيص", "فاصلة علوية", "نقطتان",
            "فاصلة منقوطة", "علامة تعجب", "علامة استفهام"
        )
        val row3 = mutableListOf<KeyModel>()
        row3Chars.forEachIndexed { idx, ch ->
            row3.add(KeyModel(ch, row3Spoken[idx]))
        }
        row3.add(
            KeyModel(
                label = "⌫",
                spokenText = "حذف",
                type = KeyType.ACTION_BACKSPACE,
                widthWeight = 1.3f
            )
        )

        val row4 = listOf(
            KeyModel(
                label = "أبج",
                spokenText = "الحروف",
                type = KeyType.ACTION_SYMBOLS,
                widthWeight = 1.5f
            ),
            KeyModel("،", "فاصلة عربية", widthWeight = 1.2f),
            KeyModel(
                label = "مسافة",
                spokenText = "مسافة",
                type = KeyType.ACTION_SPACE,
                widthWeight = 3.8f
            ),
            KeyModel(".", "نقطة", widthWeight = 1.0f),
            KeyModel(
                label = "↵",
                spokenText = "إدخال",
                type = KeyType.ACTION_ENTER,
                widthWeight = 1.5f
            )
        )

        return listOf(row1, row2, row3, row4)
    }

    fun getTashkeelRow(): List<KeyModel> = listOf(
        KeyModel("َ", "فتحة"),
        KeyModel("ً", "تنوين فتح"),
        KeyModel("ُ", "ضمة"),
        KeyModel("ٌ", "تنوين ضم"),
        KeyModel("ِ", "كسرة"),
        KeyModel("ٍ", "تنوين كسر"),
        KeyModel("ْ", "سكون"),
        KeyModel("ّ", "شدة"),
        KeyModel("ـ", "تطويل")
    )
}
