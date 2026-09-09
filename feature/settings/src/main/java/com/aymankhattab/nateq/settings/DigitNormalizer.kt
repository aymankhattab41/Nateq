package com.aymankhattab.nateq.settings

/** يحوّل الأرقام العربية المشرقية (٠..٩) والفارسية/الأردية (۰..۹) إلى
 * أرقام لاتينية، ويترك كل ما عداه دون تغيير (حروف، أرقام لاتينية، رموز). */
internal fun String.normalizeDigits(): String = map { ch ->
    when {
        ch in '\u0660'..'\u0669' -> (ch - '\u0660' + '0'.code).toChar()
        ch in '\u06F0'..'\u06F9' -> (ch - '\u06F0' + '0'.code).toChar()
        else -> ch
    }
}.joinToString("")