package com.aymankhattab.nateq.settings

import android.widget.SeekBar

/** الحد الأدنى لمعامل السرعة/النبرة؛ دونها يتوقف صوت المحرك عن الاستجابة. */
internal const val MIN_SPEED_PITCH_FACTOR = 0.25f

/** التقدّم (0..200) المقابل للحد الأدنى (معامل 0.25 × 100). */
internal const val MIN_SPEED_PITCH_PERCENT = 25

/** يحوّل تقدّم المؤشر (0..200) إلى معامل سرعة/نبرة مع حدّ أدنى 0.25. */
internal fun Int.speedFactor(): Float =
    (this / 100f).coerceAtLeast(MIN_SPEED_PITCH_FACTOR)

/** يقرّب مؤشر السرعة/النبرة إلى التقدّم الأدنى حتى يطابق المعامل المعروض. */
internal fun SeekBar.snapSpeedMin() {
    if (progress < MIN_SPEED_PITCH_PERCENT) progress = MIN_SPEED_PITCH_PERCENT
}