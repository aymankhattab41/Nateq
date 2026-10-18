package com.aymankhattab.nateq.core.data

import android.telephony.PhoneNumberUtils
import java.util.Locale

/**
 * مطبّع وموحّد أشكال البحث لجهات الاتصال وأرقام الهواتف (ع٤١-ب10، ع٤٥).
 * نقي وخالٍ من الاعتماديات المعقدة ليعمل في طبقة البيانات.
 */
object ContactLookupNormalizer {

    private val PREFIXES = listOf(
        "دكتور ", "دكتورة ", "د.", "د/", "د ",
        "مهندس ", "مهندسة ", "م.", "م/", "م ",
        "أستاذ ", "أستاذة ", "استاذ ", "استاذه ",
        "أ.", "ا.", "أ/", "ا/",
        "الشيخ ", "شيخ ",
        "السيد ", "السيدة ", "سيد ", "سيدة ",
        "dr. ", "dr.", "dr ",
        "eng. ", "eng.", "eng ",
        "prof. ", "prof.", "prof ",
        "mr. ", "mr.", "mr ",
        "mrs. ", "mrs.", "mrs ",
        "ms. ", "ms.", "ms "
    )

    fun normalizeName(name: String): String {
        if (name.isBlank()) return ""
        var text = name.trim().lowercase(Locale.ROOT)

        // إزالة التشكيل (الحركات) والتطويل
        text = text.replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")

        // توحيد الألف والياء والتاء المربوطة
        text = text.replace(Regex("[إأآٱ]"), "ا")
            .replace('ى', 'ي')
            .replace('ة', 'ه')

        // حذف البادئات والألقاب من البداية
        var changed = true
        while (changed) {
            changed = false
            for (p in PREFIXES) {
                if (text.startsWith(p)) {
                    text = text.substring(p.length).trim()
                    changed = true
                    break
                }
            }
        }

        // تجريد علامات الترقيم
        text = text.replace(Regex("[.,/#!$%^&*;:{}=\\-_`~()?\\[\\]]"), "")
        return text.trim()
    }

    /** استخراج الاسم الأول فقط بعد التطبيع */
    fun firstName(name: String): String {
        val norm = normalizeName(name)
        return norm.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
    }

    /**
     * توليد صيغ البحث لرقم الهاتف (خام، أرقام فقط، E164، وصيغ محلية).
     */
    fun phoneLookupForms(number: String, region: String? = null): List<String> {
        val raw = number.trim()
        if (raw.isEmpty()) return emptyList()
        val digits = raw.filter { it.isDigit() }
        val forms = LinkedHashSet<String>()
        forms += raw
        if (digits.isNotEmpty() && digits != raw) forms += digits
        val iso = region?.trim()?.takeIf { it.isNotEmpty() }
        if (iso != null && digits.isNotEmpty()) {
            val e164 = runCatching {
                PhoneNumberUtils.formatNumberToE164(digits, iso)
            }.getOrNull()
            if (!e164.isNullOrBlank()) forms += e164
        }

        val cleanIntl = when {
            raw.startsWith("+") -> "+$digits"
            raw.startsWith("00") -> "+${digits.drop(2)}"
            forms.any { it.startsWith("+") } ->
                forms.first { it.startsWith("+") }
            else -> null
        }
        if (cleanIntl != null && cleanIntl.startsWith("+")) {
            val prefixes3 = listOf(
                "+966", "+971", "+965", "+974", "+973",
                "+968", "+962", "+963", "+964", "+967"
            )
            for (p in prefixes3) {
                if (cleanIntl.startsWith(p)) {
                    val withoutCode = cleanIntl.drop(p.length)
                    if (withoutCode.length >= 7) {
                        forms += "0$withoutCode"
                        forms += withoutCode
                    }
                }
            }
            if (cleanIntl.startsWith("+20")) {
                val withoutCode = cleanIntl.drop(3)
                if (withoutCode.length >= 7) {
                    forms += "0$withoutCode"
                    forms += withoutCode
                }
            }
        }

        return forms.toList()
    }
}
