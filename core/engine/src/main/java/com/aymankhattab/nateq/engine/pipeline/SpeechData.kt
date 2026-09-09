package com.aymankhattab.nateq.engine.pipeline

/** بيانات وحدة قياس: المفرد والجمع والمثنى وجنس الوحدة
 *  لدعم التوافق النحوي مع العدد */
internal data class UnitInfo(
    val symbol: String,
    val singular: String,
    val plural: String,
    val dual: String,
    val isFeminine: Boolean
)

/** بيانات عملة: المفرد والجمع والمثنى والجنس، مع اسم وحدتها الفرعية
 *  («سنت / قرش / هللة») وجنسها وجمعها لنطق الكسور (0.50 → «وخمسون سنتاً»). */
internal data class CurrencyInfo(
    val name: String,
    val plural: String,
    val dual: String,
    val isFeminine: Boolean,
    val subunit: String,
    val subunitPlural: String,
    val subunitFeminine: Boolean,
    /** عدد الوحدات الفرعية في الوحدة الرئيسية: 100 (سنت/قرش/هللة من مئتي جزء)
     *  أو 1000 (الدينارات الخليجية والريال العماني = 1000 فلس/بيسة/مليم). */
    val subunitsPerUnit: Int = 100
)

/** مفردات مقياس عدد (ألف/مليون/مليار/…) بأشكال العدد المختلفة لاختيار
 *  التمييز الصحيح: مفرد، مثنى، جمع، منصوب (11–99)، وصيغة الإضافة بعد مئة. */
internal data class ScaleWords(
    val one: String,
    val two: String,
    val plural: String,
    val accusative: String,
    val inHundred: String
)