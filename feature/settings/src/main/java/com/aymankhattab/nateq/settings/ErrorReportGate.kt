package com.aymankhattab.nateq.settings

/**
 * باب منع تكرار جمع تقرير الأخطاء (ملاحظة مراجعة سجل المستخدم:
 * ضغطتان سريعتان على زر الإبلاغ كانتا تشغّلان كوروتينين متوازيين
 * يبنيان التقرير ويكتبان **نفس ملف الكاش** `nateq_diagnostic_log.txt`
 * فكان هناك تعارض كتابة/إفساد محتمل).
 *
 * منطق نقي بلا اعتماديات: [tryBegin] يُمنح لأول المتصلين فقط ما دام
 * جمعٌ جارٍ، و[finish] يُفتح الباب لالتقرير التالي (يُستدعى دائماً في
 * `finally` بمسار الجمع — حتى عند الإلغاء والإرجاع المبكر).
 */
internal class ErrorReportGate {

    private var collecting = false

    /** يحاول افتتاح جمع جديد: true لأول المتصلين، false إذا كان جمعٌ
     *  جارٍ أصلاً (الضغطات التالية مرفوضة). آمن للتكرار. */
    fun tryBegin(): Boolean {
        if (collecting) return false
        collecting = true
        return true
    }

    /** يُنهي الجمع الحالي (يُستدعى في `finally` بعد اكتمال/فشل/إلغاء
     *  رحلة الجمع) فيُعاد فتح الباب للتقرير التالي. */
    fun finish() {
        collecting = false
    }
}
