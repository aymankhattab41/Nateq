package com.aymankhattab.nateq.core.audio.phone

import android.media.ToneGenerator

/**
 * نموذج يمثل مفتاحاً في لوحة اتصال Lord TTS الميسّرة.
 */
data class DialpadKey(
    val digit: Char,
    val label: String,
    val spokenLabel: String,
    val subText: String = "",
    val dtmfTone: Int
)

/**
 * مصفوفة المفاتيح القياسية لشبكة الهاتف 3×4.
 */
object DialpadLayoutHelper {

    val KEY_1 = DialpadKey('1', "1", "واحد", "", ToneGenerator.TONE_DTMF_1)
    val KEY_2 = DialpadKey('2', "2", "اثنان", "ABC", ToneGenerator.TONE_DTMF_2)
    val KEY_3 = DialpadKey('3', "3", "ثلاثة", "DEF", ToneGenerator.TONE_DTMF_3)
    val KEY_4 = DialpadKey('4', "4", "أربعة", "GHI", ToneGenerator.TONE_DTMF_4)
    val KEY_5 = DialpadKey('5', "5", "خمسة", "JKL", ToneGenerator.TONE_DTMF_5)
    val KEY_6 = DialpadKey('6', "6", "ستة", "MNO", ToneGenerator.TONE_DTMF_6)
    val KEY_7 = DialpadKey('7', "7", "سبعة", "PQRS", ToneGenerator.TONE_DTMF_7)
    val KEY_8 = DialpadKey('8', "8", "ثمانية", "TUV", ToneGenerator.TONE_DTMF_8)
    val KEY_9 = DialpadKey('9', "9", "تسعة", "WXYZ", ToneGenerator.TONE_DTMF_9)
    val KEY_0 = DialpadKey('0', "0", "صفر", "+", ToneGenerator.TONE_DTMF_0)
    val KEY_STAR = DialpadKey(
        '*', "*", "نجمة", "", ToneGenerator.TONE_DTMF_S
    )
    val KEY_HASH = DialpadKey(
        '#', "#", "مربع", "", ToneGenerator.TONE_DTMF_P
    )

    fun getStandardDialpadKeys(): List<List<DialpadKey>> = listOf(
        listOf(KEY_1, KEY_2, KEY_3),
        listOf(KEY_4, KEY_5, KEY_6),
        listOf(KEY_7, KEY_8, KEY_9),
        listOf(KEY_STAR, KEY_0, KEY_HASH)
    )

    fun getKeys(style: String): List<List<DialpadKey>> = when (style) {
        "GRID_5X2" -> listOf(
            listOf(KEY_1, KEY_2, KEY_3, KEY_4, KEY_5),
            listOf(KEY_6, KEY_7, KEY_8, KEY_9, KEY_0),
            listOf(KEY_STAR, KEY_HASH)
        )
        "SINGLE_ROW" -> listOf(
            listOf(
                KEY_1, KEY_2, KEY_3, KEY_4, KEY_5,
                KEY_6, KEY_7, KEY_8, KEY_9, KEY_0
            ),
            listOf(KEY_STAR, KEY_HASH)
        )
        else -> getStandardDialpadKeys()
    }

    fun numCols(style: String): Int = when (style) {
        "GRID_5X2" -> 5
        "SINGLE_ROW" -> 10
        else -> 3
    }
}
