package com.aymankhattab.nateq.core.engine

/**
 * مستويات اتساع الصوت (Stereo Widening / Virtualizer).
 * 0 = إيقاف، 1 = خفيف، 2 = متوسط.
 */
object AudioExpansionLevels {

    /** إيقاف: تعطيل التأثير بالكامل. */
    const val OFF = 0

    /** خفيف: اتساع مكاني خفيف لسماعات الأذن. */
    const val LIGHT = 1

    /**
     * متوسط: اتساع مكاني أوضح مع نكهة غرفة طبيعية خفيفة.
     */
    const val MEDIUM = 2

    /** أدنى قيمة صالحة للمستوى. */
    const val MIN = OFF

    /** أقصى قيمة صالحة للمستوى. */
    const val MAX = MEDIUM

    /** القيمة الافتراضية. */
    const val DEFAULT = OFF
}
