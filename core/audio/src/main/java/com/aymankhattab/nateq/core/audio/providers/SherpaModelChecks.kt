package com.aymankhattab.nateq.core.audio.providers

import java.io.File

/**
 * ع٢٩-6: رفض تحقق مسبق من نموذج Sherpa قبل لمس المكتبة الأصلية.
 * [code] رمز معنون يصل سجلّ الأعطال (ع٢٩-8) بدل خطأ عام:
 * `MODEL_FILE_MISSING` أو `TOKENS_MISSING` أو
 * `ESPEAK_DATA_INCOMPLETE`.
 */
class SherpaModelException(
    val code: String,
    override val message: String
) : Exception(message)

/**
 * ع٢٩-6: فحوص التحقق المسبق من نماذج Sherpa — دوال خالصة بلا
 * سياق أندرويد حتى تُختبر في اختبارات وحدة JVM. يستعملها
 * المزوّد (عرض) والمضيف `:engine_host` (تهيئة OfflineTts) معاً.
 */
object SherpaModelChecks {

    /** أصغر حجم صالح لملف النموذج: يجب أن يتجاوز 100KB. */
    const val MIN_MODEL_BYTES = 100L * 1024L

    /** المدخلات الأساسية المطلوبة في مجلد espeak-ng-data. */
    val ESPEAK_REQUIRED_ENTRIES = listOf(
        "phondata",
        "phontab",
        "phonindex",
        "intonations",
        "voices",
        "lang"
    )

    /**
     * المدخلات الأساسية الغائبة أو الفارغة في [dir]؛ المجلد الفارغ
     * أو المفقود يعيد القائمة كاملة (ملف طوله صفر ومجلد بلا محتوى
     * يُعدّان ناقصين).
     */
    fun missingEspeakEntries(dir: File): List<String> {
        if (!dir.isDirectory) return ESPEAK_REQUIRED_ENTRIES
        return ESPEAK_REQUIRED_ENTRIES.filter { name ->
            val entry = File(dir, name)
            when {
                !entry.exists() -> true
                entry.isDirectory ->
                    entry.listFiles()?.isEmpty() != false
                else -> entry.length() == 0L
            }
        }
    }

    /**
     * التحقق الكامل قبل أي تهيئة أصلية: ملف النموذج أكبر من
     * 100KB، وملف tokens موجود غير فارغ، ومجلد espeak مكتمل.
     * يرمي [SherpaModelException] ولا يصل لاستدعاء المكتبة أبداً.
     */
    fun validate(
        modelFile: File,
        tokensFile: File,
        espeakDir: File
    ) {
        if (!modelFile.isFile || modelFile.length() <= MIN_MODEL_BYTES) {
            throw SherpaModelException(
                "MODEL_FILE_MISSING",
                "ملف النموذج مفقود أو أصغر من 100KB: " +
                    "${modelFile.absolutePath} " +
                    "(${modelFile.length()} bytes)"
            )
        }
        if (!tokensFile.isFile || tokensFile.length() == 0L) {
            throw SherpaModelException(
                "TOKENS_MISSING",
                "ملف tokens مفقود أو فارغ: ${tokensFile.absolutePath}"
            )
        }
        val missing = missingEspeakEntries(espeakDir)
        if (missing.isNotEmpty()) {
            throw SherpaModelException(
                "ESPEAK_DATA_INCOMPLETE",
                "بيانات espeak-ng ناقصة في ${espeakDir.absolutePath}: " +
                    missing.joinToString()
            )
        }
    }
}
