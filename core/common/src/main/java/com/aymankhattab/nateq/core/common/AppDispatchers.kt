package com.aymankhattab.nateq.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * المفوّضون الموحّدون للكوروتين — المصدر الوحيد لاختيار الحوار مكان استدعاء
 * [Dispatchers] مباشرة من كل ملف، فيتيح استبدالها موحداً في الاختبارات لاحقاً.
 */
object AppDispatchers {

    /** يُفضَّل لعمل الإدخال/الإخراج (ملفات، شبكة، SharedPreferences). */
    val io: CoroutineDispatcher get() = Dispatchers.IO

    /** للعمليات الحسابية الثقيلة غير الحاصرة. */
    val default: CoroutineDispatcher get() = Dispatchers.Default

    /** للخيط الرئيسي (update الواجهة). */
    val main: CoroutineDispatcher get() = Dispatchers.Main
}