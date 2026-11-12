package com.aymankhattab.nateq.core.audio.providers

/**
 * تحويل معاملات السرعة والنبرة لمحرك Acapela
 * متوافق تماماً مع نهج MultiVoice / base.apk.
 */
object AcapelaParameters {

    /**
     * نسبة السرعة لمحرك Acapela في النطاق 30..400 (100 = عادي).
     */
    fun sdkRate(percent: Int): Int = percent.coerceIn(30, 400)

    /**
     * نسبة النبرة لمحرك Acapela الكلاسيكي في النطاق 70..130.
     */
    fun sdkPitch(percent: Int): Int = percent.coerceIn(70, 130)

    /**
     * نبرة الحزم المسجلة لمحرك Acapela الممتد (ExtendedEngine).
     */
    fun registeredPitch(percent: Int): Int = (sdkPitch(percent) * 2) - 100

    fun interface PropertyWriter {
        fun set(key: String, value: String): Int
    }

    fun applyRegistered(
        rate: Int,
        pitch: Int,
        writer: PropertyWriter
    ): Int {
        val p = writer.set("pitch", registeredPitch(pitch).toString())
        if (p != 0) return p
        val v = writer.set("volume", "0")
        return if (v == 0) {
            writer.set("rate", sdkRate(rate).toString())
        } else {
            v
        }
    }
}
