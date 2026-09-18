package com.aymankhattab.nateq.core.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * مزوّد إشعارات داخلي (غير مُصدَّر) بين عمليتَي التطبيق (main و :tts):
 * عند كل كتابةٍ في الإعدادات تُرسل [SettingsRepository] عبر
 * [Context.getContentResolver] إشعار [ContentResolver.notifyChange] على
 * سلطانٍ موحّد فيستيقظ [ContentObserver] العملية الأخرى ويسترجع الإعدادات
 * من القرص فوراً — بلا انتظار دورة (متعلقة بعدم ظهور التغيير إلا لاحق).
 *
 * إضافةً إلى دور الإشعار العام، يوفّر المزوّد مساراً فرعياً `speaking`
 * لقفل النطق العابر بين العمليتين: محرك التخليق (process :tts) يرفع العلم
 * عبر تحديث هذا المسار في بداية التخليق ويخفضه في نهايته، ومتحدثُ الإعلانات
 * (العملية الرئيسية) يقرؤه قبل النطق ويؤجّل إعلانَه حتى يهدأ القارئ، مع
 * اشتراك [ContentObserver] على ذات المسار لتلقّي انخفاضه فوراً. القيمة
 * تخزن في الذاكرة (بلا ديمومة ضرورية — مهلة المعلّقين القصوى 5 ثوانٍ ثم
 * نطق على أي حال).
 *
 * أرجاع الدوال الموروثة كلها «خاملة» ما عدا [update] و[query] على المسار
 * الفرعي `speaking` — الغرض هو الاشتراك والإبلاغ لا تخزين بيانات دائمة.
 *
 * فئة عامة (public) وليست internal ليتسنّى للاختبارات في الوحدات الأخرى
 * تثبيتها عبر Robolectric.setupContentProvider — أمنياً يبقى غير مُصدَّر
 * في الـ Manifest (exported=false) داخل التطبيق.
 */
class SettingsChangeProvider : ContentProvider() {

    // علم «نطق جارٍ» (1) / «هادئ» (0) — يُتشارَك عبر العمليتين بالتركيز على
    // [ContentResolver.notifyChange] لكل تحديث فيستيقظ المراقبون فوراً.
    @Volatile
    private var speakingFlag = 0

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.lastPathSegment != SPEAKING_PATH) return null
        return MatrixCursor(arrayOf(SPEAKING_COLUMN)).apply {
            addRow(arrayOf(speakingFlag))
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        update(uri, values, null, null).takeIf { it > 0 }?.let { uri }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        if (uri.lastPathSegment != SPEAKING_PATH) return 0
        speakingFlag = 0
        publishSpeaking()
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        if (uri.lastPathSegment != SPEAKING_PATH) return 0
        val raw = values?.getAsInteger(SPEAKING_COLUMN) ?: return 0
        speakingFlag = if (raw > 0) 1 else 0
        publishSpeaking()
        return 1
    }

    /** إشعار المراقبين باسم المسار الفرعي نفسه (لا الجذر) — فيستيقظ
     *  [ContentObserver] المعلّقين على قفل النطق دون القراء الآخرين. */
    private fun publishSpeaking() {
        context?.contentResolver?.notifyChange(
            speakingUri(), null
        )
    }

    companion object {
        /** سلطان الإشعار الموحد المُعلن في Manifest الوحدة. */
        const val AUTHORITY = "com.aymankhattab.nateq.settings"

        private const val SPEAKING_PATH = "speaking"
        private const val SPEAKING_COLUMN = "speaking"

        fun uri(): Uri = Uri.parse("content://$AUTHORITY")

        /** المسار الفرعي لقفل النطق العابر — يُحدَّث عبر [SpeechLock]. */
        fun speakingUri(): Uri = Uri.withAppendedPath(uri(), SPEAKING_PATH)
    }
}
