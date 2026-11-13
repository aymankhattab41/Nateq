package com.aymankhattab.nateq.core.audio.providers

/**
 * رخصة Acapela المستخرجة من تطبيق MultiTTS لتشغيل أصوات Acapela.
 */
// TODO(security, 2026-11-13): Move commercial license to a private build-time
// source (e.g. local.properties -> BuildConfig) in a dedicated change.
object AcapelaLicense {
    /** معرف العميل لترخيص Acapela المرمّز ("gXQF"). */
    const val USER_ID: Long = 0x46515867L

    /** كلمة مرور الترخيص لـ Acapela المستخرجة من MultiTTS. */
    const val PASSWORD: Long = 0x0030ab63L

    const val LICENSE: String =
        "\"2948 0 gXQF #COMMERCIAL#VOICE DREAM Norway\"\n" +
        "SS!F\$CISUucsOXtJ3rcosKHz3%@zf9knHIXjLcQ7boYm9aBeI%g@HwQF2Q##\n" +
        "VimwZIxpvrP4beSo@ezPlFY5Sml5oeP9fYK7CsEL%TzdiDTj\n" +
        "RylThU\$owbw68d6!hGVOaT##\n"
}
