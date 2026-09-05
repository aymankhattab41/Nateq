package com.aymankhattab.nateq.util

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