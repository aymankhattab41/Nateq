package com.aymankhattab.nateq.core.audio.providers

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ملف بيان حزمة صوت Vocalizer Expressive 2 المدمجة (ع٣٢-1).
 *
 * يوثق كل الملفات المستخرجة أو المثبتة لصوت معين مع أحجامها،
 * لضمان الاكتمال التام وعدم حذف الملفات المشتركة إلا بعد
 * التأكد من عدم استخدامها بواسطة صوت آخر (عدّاد مراجع).
 */
data class VocalizerManifestFile(
    val name: String,
    val size: Long
)

data class VocalizerVoiceManifest(
    val voiceId: String,
    val voiceName: String,
    val headerFile: String,
    val files: List<VocalizerManifestFile>
) {

    fun toJson(): String {
        val json = JSONObject()
        json.put("voiceId", voiceId)
        json.put("voiceName", voiceName)
        json.put("headerFile", headerFile)
        val arr = JSONArray()
        for (f in files) {
            val item = JSONObject()
            item.put("name", f.name)
            item.put("size", f.size)
            arr.put(item)
        }
        json.put("files", arr)
        return json.toString(2)
    }

    /**
     * التحقق من وجود كل الملفات المذكورة في البيان بنفس الحجم.
     */
    fun isComplete(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        if (files.isEmpty()) return false
        return files.all { manifestFile ->
            val f = File(dir, manifestFile.name)
            f.exists() && f.isFile && f.length() == manifestFile.size &&
                f.length() > 0L
        }
    }

    /**
     * حفظ البيان إلى ملف manifest.json على القرص.
     */
    fun writeTo(file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(toJson(), Charsets.UTF_8)
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {

        val HDR_NAME_REGEX = Regex(
            """^ve_pipeline_(.+)_(\d+)_(.+)\.hdr$""",
            RegexOption.IGNORE_CASE
        )

        val DAT_REF_REGEX = Regex(
            """[A-Za-z0-9_.\-]+\.dat""",
            RegexOption.IGNORE_CASE
        )

        /** لاحقة رقم إصدار الرأس `_x-y-z.hdr` في نهاية الاسم. */
        val HDR_VERSION_SUFFIX_REGEX = Regex(
            """_\d+-\d+-\d+\.hdr$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * مفتاح «عائلة الرأس»: اسم الرأس بعد تجريد رقم إصداره
         * (`_x-y-z`). هوية الصوت تقوم على (الاسم + النوع) لا على رقم
         * إصدار الرأس، فصوتٌ مستوردٌ بإصدار رأس مختلف (مثل
         * `..._embedded-high_1-0-5.hdr` مقابل المتوقع `..._2-0-1.hdr`)
         * هو نفسُ الصوت ويُعرَف بالعائلة لا بالتطابق الحرفي.
         */
        fun headerFamily(headerFileName: String): String =
            headerFileName.replace(HDR_VERSION_SUFFIX_REGEX, ".hdr")

        /**
         * فحص ما إذا كان اسم الملف ينتمي للحزمة المشتركة.
         */
        fun isSharedFile(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return lower == "clm.dat" ||
                lower == "lid.dat" ||
                lower == "msp.dat" ||
                lower == "sysdct.dat" ||
                (lower.startsWith("synth_") && lower.endsWith(".dat")) ||
                lower.endsWith(".xml")
        }

        /**
         * استخراج مراجع ملفات .dat من محتوى ملف .hdr.
         */
        fun extractDatReferencesFromHdr(hdrFile: File): List<String> {
            if (!hdrFile.exists() || hdrFile.length() <= 0L) {
                return emptyList()
            }
            return try {
                val text = hdrFile.readBytes()
                    .toString(Charsets.ISO_8859_1)
                DAT_REF_REGEX.findAll(text)
                    .map { it.value }
                    .distinct()
                    .toList()
            } catch (_: Throwable) {
                emptyList()
            }
        }

        /**
         * استخراج اسم ملف .dat المتوقع من اسم ملف .hdr.
         */
        fun extractExpectedDatFromHdrName(hdrFileName: String): String? {
            val match = HDR_NAME_REGEX.find(hdrFileName) ?: return null
            val voice = match.groupValues[1]
            val variant = match.groupValues[3]
            return "${voice}_${variant}.dat"
        }

        /**
         * أدنى حجم لملف بيانات صوت `.dat` يُعدّ دليلاً على صوت
         * مُنزَّل فعلاً (دونه لا يكتمل المحرّك).
         */
        const val MIN_VOICE_DAT_BYTES = 1_048_576L

        /**
         * أسماء قواعد الأصوات المكتملة داخل مجلد واحد.
         *
         * ع٣٢-2: **لا يُشترط تطابق اسم الرأس مع اسم البيانات** لأن
         * حزم Vocalizer المدمجة تُسمّي الرأس `ve_pipeline_..._x-y-z.hdr`
         * بينما بيانات الصوت `..._embedded-<نوع>_x-y-z.dat` (اسمان
         * مختلفان)؛ لذا يُشتقّ اسم `.dat` المتوقع من الرأس، ويُقبل
         * أي `.dat` غير مشترك بحجم كافٍ كاحتياط للرؤوس غير النمطية.
         * بلا هذا التبديل كان المحرّك يبقى غير مهيّأ والصوت لا يُنطق.
         */
        fun completeVoiceBases(dir: File): List<String> {
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            val files = dir.listFiles() ?: return emptyList()
            val hdrs = files.filter {
                it.isFile && it.name.endsWith(".hdr", true) &&
                    it.length() > 0L
            }
            if (hdrs.isEmpty()) return emptyList()
            val bigDats = files.filter {
                it.isFile && it.name.endsWith(".dat", true) &&
                    it.length() >= MIN_VOICE_DAT_BYTES
            }
            val voiceDats = bigDats.filter { !isSharedFile(it.name) }
            if (voiceDats.isEmpty()) return emptyList()

            // وجود رأس + بيانات صوت بحجم كافٍ دليل الاكتمال؛
            // ويُقبل احتياطاً أي .dat صوتي للرؤوس غير النمطية أو
            // ذات إصدار مخالف (لا يُشترط تطابق الأسماء الحرفي).
            return hdrs.map { it.nameWithoutExtension }
        }

        /**
         * هل يحتوي المجلد على صوت Vocalizer مكتمل (رأس + بيانات)؟
         */
        fun hasCompleteVoice(dir: File): Boolean =
            completeVoiceBases(dir).isNotEmpty()

        /**
         * قراءة البيان من نص JSON.
         */
        fun fromJson(jsonStr: String): VocalizerVoiceManifest? {
            return try {
                val json = JSONObject(jsonStr)
                val voiceId = json.optString("voiceId")
                val voiceName = json.optString("voiceName")
                val headerFile = json.optString("headerFile")
                val arr = json.optJSONArray("files") ?: JSONArray()
                val list = mutableListOf<VocalizerManifestFile>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        VocalizerManifestFile(
                            name = obj.getString("name"),
                            size = obj.getLong("size")
                        )
                    )
                }
                if (voiceId.isBlank() || headerFile.isBlank()) return null
                VocalizerVoiceManifest(
                    voiceId = voiceId,
                    voiceName = voiceName,
                    headerFile = headerFile,
                    files = list
                )
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * قراءة البيان من ملف على القرص.
         */
        fun fromFile(file: File): VocalizerVoiceManifest? {
            if (!file.exists() || !file.isFile || file.length() <= 0L) {
                return null
            }
            return try {
                fromJson(file.readText(Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * توليد بيان تقديري من القواعد للتثبيتات السابقة (ع٣٢-1).
         *
         * يشترط وجود ملف .hdr وملف .dat الخاص بالصوت وكل
         * الملفات المشتركة المطلوبة. عند الاكتمال يحفظ
         * <voiceId>.manifest.json مرة واحدة ويعيد البيان.
         */
        fun createEstimatedManifest(
            dir: File,
            hdrFile: File,
            writeToDisk: Boolean = true
        ): VocalizerVoiceManifest? {
            if (!dir.exists() || !dir.isDirectory) return null
            if (!hdrFile.exists() || hdrFile.length() <= 0L) return null

            val catalogItem = VocalizerModelCatalog.ITEMS.firstOrNull {
                it.headerFileName.equals(hdrFile.name, ignoreCase = true)
            } ?: VocalizerModelCatalog.ITEMS.firstOrNull {
                headerFamily(it.headerFileName) == headerFamily(hdrFile.name)
            }
            val voiceId = catalogItem?.id ?: hdrFile.nameWithoutExtension
            val voiceName = catalogItem?.voiceName
                ?: hdrFile.nameWithoutExtension

            // 1. تحديد ملفات .dat المطلوبة للصوت
            val hdrRefs = extractDatReferencesFromHdr(hdrFile)
            val expectedDatName = extractExpectedDatFromHdrName(hdrFile.name)

            val requiredDatFiles = mutableSetOf<String>()
            if (hdrRefs.isNotEmpty()) {
                requiredDatFiles.addAll(hdrRefs)
            }
            if (expectedDatName != null) {
                requiredDatFiles.add(expectedDatName)
            }
            requiredDatFiles.add("${hdrFile.nameWithoutExtension}.dat")
            if (catalogItem != null) {
                val prefixClean = hdrFile.nameWithoutExtension
                    .removePrefix("ve_pipeline_")
                val fallbackDat = "${prefixClean}.dat"
                requiredDatFiles.add(fallbackDat)
            }

            // التحقق من وجود ملف بيانات الصوت الأساسي
            val voiceDatFile = requiredDatFiles.map { File(dir, it) }
                .firstOrNull { it.exists() && it.length() > 0L }
            if (voiceDatFile == null) {
                return null
            }

            // 2. التحقق من الملفات المشتركة المطلوبة
            val clmFile = File(dir, "clm.dat")
            val lidFile = File(dir, "lid.dat")
            val mspFile = File(dir, "msp.dat")
            val sysdctFile = File(dir, "sysdct.dat")
            val synthFiles = dir.listFiles { f ->
                f.isFile && f.name.startsWith("synth_", ignoreCase = true) &&
                    f.name.endsWith(".dat", ignoreCase = true) &&
                    f.length() > 0L
            } ?: emptyArray()
            val xmlFiles = dir.listFiles { f ->
                f.isFile && f.name.endsWith(".xml", ignoreCase = true) &&
                    f.length() > 0L
            } ?: emptyArray()

            val sharedPresent = clmFile.exists() && clmFile.length() > 0L &&
                lidFile.exists() && lidFile.length() > 0L &&
                mspFile.exists() && mspFile.length() > 0L &&
                sysdctFile.exists() && sysdctFile.length() > 0L &&
                synthFiles.isNotEmpty() &&
                xmlFiles.isNotEmpty()

            if (!sharedPresent) {
                return null
            }

            // جمع كل الملفات التابعة للصوت وحزمته المشتركة
            val manifestFiles = mutableListOf<VocalizerManifestFile>()
            manifestFiles.add(
                VocalizerManifestFile(hdrFile.name, hdrFile.length())
            )
            manifestFiles.add(
                VocalizerManifestFile(voiceDatFile.name, voiceDatFile.length())
            )

            val sharedList = listOf(clmFile, lidFile, mspFile, sysdctFile) +
                synthFiles.toList() + xmlFiles.toList()
            for (sf in sharedList) {
                if (manifestFiles.none { it.name.equals(sf.name, true) }) {
                    manifestFiles.add(
                        VocalizerManifestFile(sf.name, sf.length())
                    )
                }
            }

            val manifest = VocalizerVoiceManifest(
                voiceId = voiceId,
                voiceName = voiceName,
                headerFile = hdrFile.name,
                files = manifestFiles
            )

            if (writeToDisk) {
                val manifestFile = File(dir, "$voiceId.manifest.json")
                manifest.writeTo(manifestFile)
            }

            return manifest
        }
    }
}
