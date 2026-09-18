package com.aymankhattab.nateq.core.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * منظف الملفات المؤقتة اليتيمة عند الإقلاع (بند 19.2).
 *
 * عند انقطاع العملية فجأة (قَتل النظام، إعادة تشغيل، تجمّد) قد تبقى ملفات
 * `.wav` مؤقتة في cacheDir لم تُحذف في `finally`، وقد تتراكم APK قديمة في
 * مجلد التنزيلات لم يعد التطبيق بحاجتها. هذا المنظف يشغَّل مرة واحدة عند
 * الإقلاع (من [NateqApplication.onCreate]) على خيط خلفي غير حاصر فيسكبها
 * دون إبطاء بدء التطبيق.
 *
 * قواعد الحذف الآمنة:
 *  - يمحو كل `*.wav` يتيم في cacheDir (لا يمسّ نسخ الكاش المستخدمة فعلياً —
 *    أسماء WAV المؤقتة في هذا المشروع فريدة بطابع زمني لإشعارات TTS).
 *    **حدّ العمر**: الملفات الأحدث من [MIN_AGE_MS] تبقى — قد تكون ملف
 *    `nateq_tts_session.wav` نشطاً تتولّده الآن خدمة :tts في عملية منفصلة،
 *    وحذفه أثناء الاستخدام يوقف الصوت (كانت المسابقة بإقلاع الخدمة تحذف
 *    الملف لحظياً فترميه لإعادة التوليد أو ترمي «صوتاً ناقصاً»).
 *  - في مجلد التنزيلات لا يمسّ ملف الـ APK الحالي المسمّى
 *    [UpdateChecker.APK_NAME]
 *    ولا يمسّ أي ملف أثناء تنزيل نشط (ملف حجمه صفر أو ملف بامتداد جزئي
 *    `.tmp`/`.part` يُترك لمدير التنزيلات — وكذلك أي ملف حديث، قد يكون APK
 *    نزل للتو ولا تزال المعالجة تنقل نسخته)، ويمحو فقط ملفات APK قديمة بأسماء
 *    أخرى أو نسخ مضغوطة (`.jpg`/`.zip`) إن وُجدت — لكي لا تُكسر دورة التحديث.
 */
class StartupTempSweeper(private val context: Context) {

    companion object {
        private const val TAG = "NATEQ_TEMP_SWEEP"

        /** أقل عمر للملف لاعتباره يتيماً: ملفات أحدث من هذا تُترك — قد
         *  يكون يكتبها الآن التطبيقُ/الخدمة (جلسة TTS أو تنزيل مُعنْقَل). */
        private const val MIN_AGE_MS = 30 * 60 * 1000L

        /** اسم ملف الـ APK الحالي النشط في مجلد التنزيلات
         *  (يطابق UpdateChecker).
         *  لا نمسّه أبداً حتى لا يكسر دورة التحديث/التثبيت. */
        private const val ACTIVE_APK_NAME = "nateq.apk"
    }

    /** ينفّذ التنظيف ويعيد عدد الملفات المحذوفة. */
    fun sweep(): Int {
        var deleted = 0
        deleted += sweepFileCache()
        deleted += sweepDownloads()
        if (deleted > 0) {
            Log.i(TAG, "تم حذف $deleted ملفاً مؤقتاً يتيماً عند الإقلاع")
        } else {
            Log.d(TAG, "لا ملفات مؤقتة يتيمة عند الإقلاع")
        }
        return deleted
    }

    private fun sweepFileCache(): Int {
        return runCatching {
            val cache = context.cacheDir
            if (!cache.exists() || !cache.isDirectory) return 0
            var deleted = 0
            val cutoff = System.currentTimeMillis() - MIN_AGE_MS
            cache.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name.endsWith(".wav") &&
                        it.lastModified() < cutoff
                }
                ?.forEach {
                    if (it.delete()) deleted++
                }
            deleted
        }.getOrDefault(0)
    }

    private fun sweepDownloads(): Int {
        return runCatching {
            // قد يُرجع getExternalFilesDir null (تخزين مشفَّر أو ممتلئ): نتدارك
            // بمسار داخلي للدليل بدل بناء مسار فارغ/بلا وجهة — يبقى التنظيف
            // آمناً ولا يُحبط دورة التحديث (بند 19.2).
            val base = context.getExternalFilesDir(null)
                ?: context.filesDir
                ?: return 0
            val downloads = File(base, "downloads")
            if (!downloads.exists() || !downloads.isDirectory) return 0
            var deleted = 0
            val cutoff = System.currentTimeMillis() - MIN_AGE_MS
            downloads.listFiles()?.filter { file ->
                file.isFile &&
                    !file.name.equals(ACTIVE_APK_NAME, ignoreCase = true)
            }?.forEach { file ->
                // لا نلمس ملفاً أثناء تنزيل نشط (حجم صفري أو لاحقة جزئية) أو
                // ملفاً حديثاً (قد يكتبه الآن مديرُ التنزيلات أو تُعالج نسخته).
                if (file.length() == 0L) return@forEach
                if (file.name.endsWith(".tmp") ||
                    file.name.endsWith(".part")) return@forEach
                if (file.lastModified() >= cutoff) return@forEach
                if (file.delete()) deleted++
            }
            deleted
        }.getOrDefault(0)
    }
}
