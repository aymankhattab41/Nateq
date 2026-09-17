package com.aymankhattab.nateq.core.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * مزوّد إشعارات داخلي (غير مُصدَّر) بين عمليتَي التطبيق (main و :tts):
 * عند كل كتابةٍ في الإعدادات تُرسل [SettingsRepository] عبر
 * [Context.getContentResolver] إشعار [ContentResolver.notifyChange] على
 * سلطانٍ موحّد فيستيقظ [ContentObserver] العملية الأخرى ويسترجع الإعدادات
 * من القرص فوراً — بلا انتظار دورة (متعلقة بعدم ظهور التغيير إلا لاحق).
 *
 * أرجاع الدوال الموروثة كلها «خاملة» — الغرض هو الاشتراك والإبلاغ لا
 * تخزين بيانات — والسلطان يحدده [uri] بما يوافق إعلان الـ Manifest.
 */
internal class SettingsChangeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        /** سلطان الإشعار الموحد المُعلن في Manifest الوحدة. */
        const val AUTHORITY = "com.aymankhattab.nateq.settings"

        fun uri(): Uri = Uri.parse("content://$AUTHORITY")
    }
}