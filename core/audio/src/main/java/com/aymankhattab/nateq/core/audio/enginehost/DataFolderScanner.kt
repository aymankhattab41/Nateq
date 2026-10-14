package com.aymankhattab.nateq.core.audio.enginehost

import java.io.File
import java.util.Locale

/**
 * نتيجة مسح مجلد بيانات Nuance Vocalizer
 * (ع٣٧-2 / ع٣٧-1).
 */
data class ScanResult(
    val headersText: String,
    val datFiles: Map<String, File>,
    val hdrFiles: List<File> = emptyList()
)

/**
 * ماسح بيانات مجلد Vocalizer (ع٣٧-2 / ع٣٧-1).
 *
 * يمسح المجلد تكرارياً ويجمع:
 * 1. نصوص ترويسات .hdr ككتلة واحدة (UTF-8)
 *    بالترتيب.
 * 2. ملفات الصوت .dat المفهرسة بأسمائها بحروف
 *    صغيرة، مستثنياً الملفات غير الصوتية
 *    (clm/lid/synth_med_fxd_bet3f22/sysdct).
 */
object DataFolderScanner {

    /**
     * ملفات .dat غير الصوتية المستثناة
     * من الكتالوج.
     */
    val EXCLUDED_DAT_FILES: Set<String> = setOf(
        "clm.dat",
        "lid.dat",
        "synth_med_fxd_bet3f22.dat",
        "sysdct.dat"
    )

    /**
     * قراءة نص الترويسات مباشرة من
     * مجلد البيانات (ع٣٧-1).
     */
    fun readHeaders(dir: File): String = scan(dir).headersText

    /**
     * قراءة نص الترويسات مباشرة من
     * مسار مجلد البيانات (ع٣٧-1).
     */
    fun readHeaders(dirPath: String): String = scan(File(dirPath)).headersText

    /**
     * مسح المجلد تكرارياً واستخراج
     * الترويسات وملفات .dat الصالحة.
     */
    fun scan(dir: File): ScanResult {
        if (!dir.exists() || !dir.isDirectory) {
            return ScanResult(
                headersText = "",
                datFiles = emptyMap(),
                hdrFiles = emptyList()
            )
        }

        val headersBuilder = StringBuilder()
        val datFiles = mutableMapOf<String, File>()
        val hdrFiles = mutableListOf<File>()

        try {
            val allFiles = dir.walkTopDown()
                .onFail { file, ioException ->
                    HostLog.w(
                        HostProtocol.ENGINE_VOCALIZER,
                        "DataFolderScanner: تعذّر الوصول إلى " +
                            "${file.absolutePath}: ${ioException.message}"
                    )
                }
                .filter { it.isFile }
                .toList()
                .sortedBy { it.path }

            for (file in allFiles) {
                val nameLower = file.name.lowercase(Locale.ROOT)
                when {
                    nameLower.endsWith(".hdr") -> {
                        try {
                            val text = file.readText(Charsets.UTF_8)
                            headersBuilder.append(text)
                            hdrFiles.add(file)
                        } catch (e: Exception) {
                            HostLog.w(
                                HostProtocol.ENGINE_VOCALIZER,
                                "DataFolderScanner: تعذّرت قراءة " +
                                    "${file.absolutePath}: ${e.message}"
                            )
                        }
                    }
                    nameLower.endsWith(".dat") -> {
                        if (nameLower !in EXCLUDED_DAT_FILES) {
                            datFiles[nameLower] = file
                        }
                    }
                }
            }
        } catch (e: Exception) {
            HostLog.w(
                HostProtocol.ENGINE_VOCALIZER,
                "DataFolderScanner: استثناء أثناء مسح " +
                    "${dir.absolutePath}: ${e.message}"
            )
        }

        return ScanResult(
            headersText = headersBuilder.toString(),
            datFiles = datFiles,
            hdrFiles = hdrFiles
        )
    }
}
