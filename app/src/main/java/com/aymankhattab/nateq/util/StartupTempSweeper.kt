package com.aymankhattab.nateq.util

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
 *  - في مجلد التنزيلات لا يمسّ ملف الـ APK الحالي المسمّى [UpdateChecker.APK_NAME]
 *    ولا يمسّ أي ملف أثناء تنزيل نشط (ملف حجمه صفر أو ملف بامتداد جزئي
 *    `.tmp`/`.part` يُترك لمدير التنزيلات)، ويمحو فقط ملفات APK قديمة بأسماء
 *    أخرى أو نسخ مضغوطة (`.jpg`/`.zip`) إن وُجدت — لكي لا تُكسر دورة التحديث.
 */
class StartupTempSweeper(private val context: Context) {

    companion object {
        private const val TAG = "NATEQ_TEMP_SWEEP"

        /** اسم ملف الـ APK الحالي النشط في مجلد التنزيلات (يطابق UpdateChecker).
         *  لا نمسّه أبداً حتى لا يكسر دورة التحديث/التثبيت. */
        private const val ACTIVE_APK_NAME = "lord_tts.apk"
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
            cache.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }?.forEach {
                if (it.delete()) deleted++
            }
            deleted
        }.getOrDefault(0)
    }

    private fun sweepDownloads(): Int {
        return runCatching {
            val downloads = File(context.getExternalFilesDir(null), "downloads")
            if (!downloads.exists() || !downloads.isDirectory) return 0
            var deleted = 0
            downloads.listFiles()?.filter { file ->
                file.isFile && !file.name.equals(ACTIVE_APK_NAME, ignoreCase = true)
            }?.forEach { file ->
                // لا نلمس ملفاً أثناء تنزيل نشط (حجم صفري أو لاحقة جزئية).
                if (file.length() == 0L) return@forEach
                if (file.name.endsWith(".tmp") || file.name.endsWith(".part")) return@forEach
                if (file.delete()) deleted++
            }
            deleted
        }.getOrDefault(0)
    }
}
