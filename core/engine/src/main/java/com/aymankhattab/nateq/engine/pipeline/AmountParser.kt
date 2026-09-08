package com.aymankhattab.nateq.engine.pipeline

/**
 * تحليل وتحجيم الأرقام الخام (فواصل الآلاف/الكسور) المستخدمة من خطوات العملات
 * والوحدات والأرقام العادية — نُقل من TextProcessor للمشاركة بينها.
 */
internal object AmountParser {

    /**
     * تنظيف تمثيل رقمي خام بفصل دقيق بين فاصل الآلاف وفاصلة الكسور:
     *  - فاصل آلاف يُحذف (1,234 → 1234، 1.234.567 → 1234567)
     *  - فاصلة كسور تُوحَّد إلى نقطة (1,5 → 1.5، 1.234,56 → 1234.56)
     * قاعدة التمييز: آخر فاصل يُعتبر كسوراً إلا إذا كان طرفه ثلاثي الخانات
     * ضمن متوالية آلاف منسجمة (فحص تنوّع الرموز). الفاصلة المنفردة بطرف ثلاثي
     * (3.141) تبقى عشرية فلا تُفسد الأعداد العشرية كأعداد صحيحة.
     */
    fun sanitizeNumerals(raw: String): String {
        val seps = mutableListOf<Int>()
        for (i in raw.indices) {
            val ch = raw[i]
            val digitAround = i > 0 && raw[i - 1].isDigit() && i + 1 < raw.length && raw[i + 1].isDigit()
            if ((ch == ',' || ch == '.') && digitAround) seps.add(i)
        }
        if (seps.isEmpty()) return raw

        val lastIdx = seps.last()
        val lastCh = raw[lastIdx]
        val tailLen = raw.length - lastIdx - 1

        val lastIsDecimal = when {
            tailLen != 3 -> true
            seps.size == 1 -> lastCh == '.'   // فاصلة وحيدة + طرف ثلاثي: (,) آلاف أمريكية، (.) عشرية
            else -> seps.any { raw[it] != lastCh }  // تنوّع الرموز: الأخيرة كسور (1,234.567)، وإلا فكلها آلاف
        }

        val sb = StringBuilder(raw.length)
        for (i in raw.indices) {
            when {
                i == lastIdx && lastIsDecimal -> sb.append('.')
                i in seps -> Unit
                else -> sb.append(raw[i])
            }
        }
        return sb.toString()
    }

    /** تحليل مبلغ رقمي خام مع تمييز صحيح بين فاصلة الآلاف وفاصلة الكسور.
     *  @return القيمة العددية أو صفراً عند تعذر الفهم (لا انهيار للنطق). */
    fun parseAmount(raw: String): Double {
        val cleaned = sanitizeNumerals(raw)
        return cleaned.toDoubleOrNull() ?: 0.0
    }
}