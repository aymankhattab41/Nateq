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

    fun getStandardDialpadKeys(): List<List<DialpadKey>> = listOf(
        listOf(
            DialpadKey('1', "1", "واحد", "", ToneGenerator.TONE_DTMF_1),
            DialpadKey('2', "2", "اثنان", "ABC", ToneGenerator.TONE_DTMF_2),
            DialpadKey('3', "3", "ثلاثة", "DEF", ToneGenerator.TONE_DTMF_3)
        ),
        listOf(
            DialpadKey('4', "4", "أربعة", "GHI", ToneGenerator.TONE_DTMF_4),
            DialpadKey('5', "5", "خمسة", "JKL", ToneGenerator.TONE_DTMF_5),
            DialpadKey('6', "6", "ستة", "MNO", ToneGenerator.TONE_DTMF_6)
        ),
        listOf(
            DialpadKey('7', "7", "سبعة", "PQRS", ToneGenerator.TONE_DTMF_7),
            DialpadKey('8', "8", "ثمانية", "TUV", ToneGenerator.TONE_DTMF_8),
            DialpadKey('9', "9", "تسعة", "WXYZ", ToneGenerator.TONE_DTMF_9)
        ),
        listOf(
            DialpadKey('*', "*", "نجمة", "", ToneGenerator.TONE_DTMF_S),
            DialpadKey('0', "0", "صفر", "+", ToneGenerator.TONE_DTMF_0),
            DialpadKey('#', "#", "مربع", "", ToneGenerator.TONE_DTMF_P)
        )
    )
}
