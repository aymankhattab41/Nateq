package com.aymankhattab.nateq.util

import android.os.Build
import android.view.View

/**
 * إعلان نص للمقروء شاشياً (TalkBack) بطريقة واحدة متوافقة مع كل الإصدارات.
 *
 * واجهة [View.announceForAccessibility] نفسها مُهجّرة منذ API 33، والبديل
 * المقترح performAccessibilityAction(ACTION_ANNOUNCE) يُنطق دلالات العقدة
 * (Node semantics) لا النص الممرَّر صراحةً — وقد يخلّ بتجربة إعلانات
 * الأسعار/الأقسام هنا. لذلك نُبقي الاستدعاء الأصلي (سلوك متطابق تماماً على
 * كل الإصدارات) ونحتوي تحذير التهجير في نقطة واحدة موثقة بدل 23 نقطة.
 */
fun View.announceCompat(text: CharSequence) {
    @Suppress("DEPRECATION")
    announceForAccessibility(text)
}

/**
 * يربط وصف الحالة الإتاحي للشريط (API 30+) بالقيمة الحالية أثناء التغيير،
 * فيقرؤه TalkBack فوراً لدى تحريك المؤشر بمفاتيح الصوت (لا عند التوقف عن
 * اللمس فقط كإعلان النهاية [announceCompat]). العقد الأقدم تُكتفى بإعلانات
 * التقدم المدمجة في النهاية مع بقاء النص المرئي محدَّثاً.
 */
fun View.setSeekStateDescription(text: CharSequence) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        stateDescription = text
    }
}