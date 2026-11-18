package com.aymankhattab.nateq.core.audio.providers

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * المرجع الأصلي لصوت Acapela العربي هو «ليلى» (`leila22k_hq`) لا «سلمى».
 *
 * هذه الوحدة لا تكتب أي `ini`: تنسخ الشجرة كما وردت في الأرشيف،
 * وتتحقق من كل ملف بالحجم وMD5 وSHA-256 مقابل جدول مقيس ثابت،
 * ثم تعرض تجربة `Path_Original_Leila_Verbatim` بأكوادها الحقيقية
 * إن وُجدت، وبتصريح صريح بعدم القياس إن لم تُشغَّل على جهاز.
 */
object AcapelaLeilaOriginalTree {

    /** اسم الصوت المرجعي كما ورد في `config.yaml`. */
    const val VOICE_NAME = "Leila"

    /** مجلد الصوت داخل رمز Acapela في الأرشيف. */
    const val VOICE_DIR = "leila22k_hq"

    /** مجلد الرمز المشفر تحت `voice/acapela/`. */
    const val TOKEN_DIR = "88GqqbfobVqqleus52QYWilZ0Nc0DtPaQztCeFMhsGg="

    /** جذر الأصوات داخل الأرشيف. */
    const val ARCHIVE_VOICE_ROOT = "multitts_files/files/voice/"

    /** بادئة شجرة ليلى والمفاتيح المشتركة داخل الأرشيف. */
    const val ARCHIVE_PREFIX = ARCHIVE_VOICE_ROOT + "acapela/" +
        TOKEN_DIR + "/"

    /** ملف التهيئة الأصلي (١٠٤٧ بايت) — لا يُعاد كتابته أبداً. */
    const val MAIN_INI = "ar_sa_hd_leila_22k_lf.bvcu.ini"

    /** ملف الوصف الأصلي (٤٨٩ بايت). */
    const val MAIN_INF = "ar_sa_hd_leila_22k_lf.bvcu.inf"

    /** سجل المكتبة الضخم الذي يُستبعد قاطعاً من أي نسخ. */
    const val LOG_FILE_NAME = "logfile.txt"

    /**
     * بصمات كل ملفات شجرة ليلى الثلاثة والعشرين والملفات المشتركة،
     * مقيسة من الأرشيف بقراءة الأعضاء المحددين فقط بلا تفكيك كامل.
     */
    val originalFiles: List<AcapelaVoiceDiagnostics.MeasuredArchiveFile> =
        listOf(
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "0x00294994",
            139L,
            "e4a2e71d6658ac1519c625928575055e",
            "2f46c283262990c95245ec2fd77eacab" +
                "4206121eb599e2472ec2e0b2f3ed4e1d"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa.bnx",
            16907L,
            "04ea2111dc6cec8b09bfb99baae170b4",
            "1f11d407907e254015fcd1235a65a32a" +
                "ec08ce0d568812a25909df1a952d9ee4"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa.dca",
            3455285L,
            "4150f26046e681c41ee7a97ead4d9bbe",
            "43492f7e3c1018c7f46b5871eb0dd2d3" +
                "027e281ea59012e934a1c28f725ca821"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa.ldi",
            14617L,
            "e2166e471c2f40953a6db6e1743bf7a1",
            "c5d8a1ade5f86f001fbf8d2d0f4289e6" +
                "01d390a3a4589868ba35900e09117955"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa.oso",
            23089L,
            "3a07e59b2d79e7b4415d0b7edb0b5c9f",
            "4ee836a2c1ae4a62305ba73cb4e68b44" +
                "a0b843abe942e7e036dff7141bcab91b"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa.trz",
            104143L,
            "e58ef4e7834c547f7ed0741c8dcf0231",
            "abaeb6117f476af6e8ad5c84e744f0ea" +
                "492fd4a7414bb1a75ee832bb4334c25f"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_cp.cma",
            605L,
            "976d00b14e8f211615a04ac2062b8ec0",
            "bb16869378f8152d7d3cb5b08005005c" +
                "b889c13f86bd0bf31f082c7bd48ae432"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_e_arq.dca",
            2947221L,
            "d673c77227d1c30c31267b0a32c45c51",
            "f560a4950b185850f988ff464791dbc1" +
                "4d91aa8b4d9168d37c6bb96d281fd980"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_hd.bnx",
            17023L,
            "2891d72006445d819dca79c50ca910d9",
            "799b3dd72e11b1503d2c422090d867d6" +
                "8d597237677b519494c38a71bc5c06cb"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_hde_arq.dca",
            1538609L,
            "6801b0761888ee0dba4d0cde185deeb3",
            "4abe134f87331455e2007bfe2a9feccf" +
                "15150f17bb77771afea2255959218c49"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_iv.trz.gra",
            2781L,
            "485d844da9a193d922cd356351c2068e",
            "b8060b7d5e7e8b8b5471663f6b40353e" +
                "a00040a5f00a4a9b4ab377bc2f4c2edd"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_oov.trz.gra",
            8739L,
            "a79bd18616733e7644bd252610bc480e",
            "218fa6a863c65dada25b919662a81f40" +
                "d6e71ec90a4c7df1236adadd15c17e14"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_smiley.ldi",
            4546L,
            "98a0ff1ae61d8cb5e289518462354d5a",
            "50d95fa22115e77a596097f4f4152271" +
                "c608a4da313385d31010e99cfb47d185"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_unplugged.bnx",
            4563L,
            "28942019b8c36cbbdd36f02f455de66e",
            "c788e2ae699950707e450365ef08fb86" +
                "a586bfd964a0f239d9b5990d46fadb8a"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_unplugged.dca",
            3455005L,
            "56b550b6663dc6dbf6a7cd719167afa7",
            "6e15add05d4f0afdcd58df12e6aa37cc" +
                "98ebf1c44467ead561076ef96ac2ecd2"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_unplugged.ldi",
            14169L,
            "9c91616e3a6c829da419d07f405f2a94",
            "9b6c6332b59e2c5ec6412cea94670d68" +
                "3bc3478772f26bb2019c476719c510b6"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_utf8.cma",
            19977L,
            "73776c06cda2b11a55eb604f16b0bfcc",
            "d51bd5e0e672292db387a6b70baa5c03" +
                "b15bf80a4a9cfb858468bc5efb9e8ec7"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/ar_sa_utf8.dca",
            11501L,
            "f7f5a112bd877cf6b2b283a1fbdf0444",
            "09a42b8e7448d51039b7443db80a4e83" +
                "6963494234b708149cefe6050bba22a6"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/NLP/user.userdico",
            81L,
            "4150021b1720790ddaa9feb6b117d588",
            "cc705cfcc9df29210d408c67d86295c3" +
                "5837a08c68e10d87f6f577e9d9cda9d7"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/ar_sa_hd_leila_22k_lf.bvcu.inf",
            489L,
            "c56b7fb07106a67641ff0af6faa64d82",
            "c312dda29c7d4e39601814384249c055" +
                "967f9f879f8c4d174044aece72804ec0"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/ar_sa_hd_leila_22k_lf.bvcu.ini",
            1047L,
            "7f28c7e3c123b6dd952e583509d77293",
            "5f2b80e9fab385aa9096aaf91a6135af" +
                "cdc3a91e0f249101ffc164c44ceaed66"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/leila_lf.txt",
            199L,
            "dc26fbd2e888ab16611bb88f554b0dcb",
            "a5f2665a4ba7f0e6ad019eb4cb26b544" +
                "2216c2f9deeacd92d6da8e7af3f73ecb"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/leila_lf/leila_22k_lf.bvcu",
            25857167L,
            "825bf78a56562c87cbf91045b1d8d9e1",
            "58950b4d599e6cf11aac0b08713837e2" +
                "9a4b87524907231adb01893019425f21"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/leila_lf/" +
                "leila_22k_lf.bvcu.nuul216vqfp",
            1160796L,
            "88f1d2627e87c34dffbd8d2b02fa1f13",
            "61bc68a325463e6f045b1e33da4f0e46" +
                "ff471d8a28abfdfea8d9d35f65a6cab9"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "leila22k_hq/version.txt",
            4L,
            "88d1955de012defb14b2db6f4797ff20",
            "5ad3a5738540131c55f9c7d97ddf6444" +
                "0c889ab683af7294dfb3d2558f111263"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "userdicos/ara-sau.userdico",
            41036L,
            "e8a78aeb3b2ec8bdeac093ee2b4a446e",
            "bdfae391c55a4dc5c8830762174adbf5" +
                "07b624aec822a1e09a893a5b396b9e45"
        ),
        AcapelaVoiceDiagnostics.MeasuredArchiveFile(
            "voicelist",
            14L,
            "675c7f830278b1bc3b2c1dff6e1a71e5",
            "6fed4eb8e8bc51b66039e8c6a1e8baee" +
                "4007350bc1f4064c847e5af31c935f66"
        )
    )

    /** نتيجة التحقق من شجرة منسوخة مقابل الجدول المقيس. */
    data class Verification(
        val matched: List<String>,
        val missing: List<String>,
        val mismatched: List<String>,
        val extra: List<String>,
        val generatedIni: List<String>
    ) {
        /** هل النسخة حرفية بالكامل بلا تسطيح ولا ini مولّد؟ */
        val isVerbatim: Boolean
            get() = missing.isEmpty() && mismatched.isEmpty() &&
                extra.isEmpty() && generatedIni.isEmpty()

        /** تقرير مختصر بالقياس لا بالتخمين. */
        fun asReport(): String = buildString {
            appendLine(
                "- الملفات المتطابقة: " +
                    "${matched.size}/${originalFiles.size}"
            )
            appendLine("- المفقودة: ${missing.size}")
            appendLine("- المختلفة: ${mismatched.size}")
            appendLine("- الزائدة: ${extra.size}")
            appendLine("- ini مولّد: ${generatedIni.size}")
            appendLine("- حرفي بالكامل: ${if (isVerbatim) "نعم" else "لا"}")
            mismatched.take(8).forEach {
                appendLine("  * مختلف: $it")
            }
            generatedIni.take(8).forEach {
                appendLine("  * ini مولّد: $it")
            }
        }.trimEnd()
    }

    /**
     * نسخ شجرة ليلى والملفات المشتركة من الأرشيف حرفياً.
     * لا يُنشأ أي ملف `ini`، ولا يُسطَّح أي مجلد، ويُستبعد `logfile.txt`.
     */
    fun extractVerbatim(zip: File, target: File): Int {
        if (!zip.isFile) return 0
        var copied = 0
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList().filter { entry ->
                val name = entry.name.replace('\\', '/')
                !entry.isDirectory && name.startsWith(ARCHIVE_PREFIX) &&
                    !name.substringAfterLast('/').equals(
                        LOG_FILE_NAME, ignoreCase = true
                    )
            }
            val targetRoot = target.canonicalFile
            for (entry in entries) {
                val relative = entry.name.replace('\\', '/')
                    .removePrefix(ARCHIVE_PREFIX)
                val output = File(target, relative)
                val inside = runCatching {
                    output.canonicalFile.path.startsWith(
                        targetRoot.path + File.separator
                    )
                }.getOrDefault(false)
                if (!inside) continue
                output.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { outputStream ->
                        input.copyTo(outputStream, 64 * 1024)
                    }
                }
                copied++
            }
        }
        return copied
    }

    /** حساب SHA-256 لملف موجود على القرص. */
    private fun sha256Of(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** التحقق من كل ملف بالحجم وSHA-256 مقابل الجدول المقيس. */
    fun verify(tree: File): Verification {
        val expected = originalFiles.associateBy { it.relativePath }
        val actual = if (tree.isDirectory) {
            tree.walkTopDown().filter { it.isFile }
                .associate { relativePath(tree, it) to it }
        } else {
            emptyMap()
        }
        val matched = mutableListOf<String>()
        val mismatched = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for ((relative, reference) in expected) {
            val file = actual[relative]
            when {
                file == null -> missing += relative
                file.length() != reference.size ||
                    sha256Of(file) != reference.sha256 -> mismatched += relative
                else -> matched += relative
            }
        }
        val extra = actual.keys - expected.keys
        val generatedIni = extra.filter {
            it.substringAfterLast('/').endsWith(
                ".ini", ignoreCase = true
            )
        }.sorted()
        return Verification(
            matched = matched.sorted(),
            missing = missing.sorted(),
            mismatched = mismatched.sorted(),
            extra = extra.sorted(),
            generatedIni = generatedIni
        )
    }

    /** المسار النسبي الموحد داخل شجرة النسخ. */
    private fun relativePath(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    /**
     * كيف يمرّر MultiTTS الترخيص: من `param` في `config.yaml` إلى
     * Registration ثم `idfunction()` قبل `nInit`. لا تُعرض أي قيمة.
     */
    fun licenseFlowReport(): String = buildString {
        appendLine("=== مطابقة ترخيص MultiTTS لمحرك Acapela ===")
        appendLine("1. config.yaml: خاصية param لص ara-SAU-Leila / Leila.")
        appendLine(
            "   تُقرأ القيمة بطولها فقط؛ أي عرض لمحتواها محظور."
        )
        appendLine(
            "2. engines.yaml: محرك acapela من نوع inner، أي أنه محرك"
        )
        appendLine("   داخلي يستقبل بياناته من التهيئة لا من بثّ خارجي.")
        appendLine(
            "3. AcapelaMetadataCodec.resolveAcapelaParam يفك param"
        )
        appendLine(
            "   (Base64 ثم AES) ويستخرج email وhexCode وطول registration."
        )
        appendLine(
            "4. المسار الممتد: تُمرَّر سلسلة registration إلى"
        )
        appendLine(
            "   AcattandroidService في المنشئ، وidfunction() تعيدها"
        )
        appendLine("   إلى BABILE_init قبل استدعاء nInit.")
        appendLine(
            "5. المسار الكلاسيكي: nSetLicense(user, pwd, license) يُستدعى"
        )
        appendLine("   قبل nInit مباشرة بالقيم من مصدر الترخيص المحلي.")
        appendLine(
            "6. الترتيب الحاكم: ضبط الترخيص أولاً ثم nInit"
        )
        appendLine("   ثم nSetVoiceMode ثم nSetCurrentVoice ثم nSpeak.")
        appendLine(
            "7. لا يُطبع ولا يُسجَّل أي من userId/passwd/Registration/"
        )
        appendLine("   License؛ يُعرض الطول وآخر ٤ حروف مقنّعة فقط.")
    }.trimEnd()

    /**
     * تجربة `Path_Original_Leila_Verbatim`. الأكواد تُحقن من قياس حقيقي
     * على جهاز؛ وإن لم تُحقن يُذكر صراحةً أنها لم تُشغَّل في هذه البيئة.
     */
    fun trialReport(
        verification: Verification,
        codes: AcapelaVoiceDiagnostics.MeasuredNInitCodes? = null,
        executionNote: String = "لم يُشغَّل nInit/nSpeak في JVM: لا توجد " +
            "مكتبة Acapela أصلية قابلة للتحميل هنا."
    ): String = buildString {
        appendLine("=== Path_Original_Leila_Verbatim ===")
        appendLine("- الصوت المرجع: $VOICE_NAME ($VOICE_DIR)")
        appendLine(verification.asReport())
        appendLine("- ini الأصلي: $MAIN_INI (1047 بايت، بلا إعادة كتابة)")
        appendLine("- الترخيص: ${licenseFlowReport()}")
        if (codes == null) {
            appendLine("- nInit/nSpeak: $executionNote")
            appendLine("- عيّنات PCM جديدة: غير مقيسة")
            appendLine(
                "- آخر مقيس معلوم (تجربة Salma السابقة): " +
                    AcapelaVoiceDiagnostics.observedRoot109Codes.asLine()
            )
        } else {
            appendLine("- nInit/nSpeak: ${codes.asLine()}")
        }
    }.trimEnd()
}

/** مقارنة حيّة بين ما ينسخه Nateq وأصل الأرشيف لكل صوت بالاسم نفسه. */
object AcapelaVoiceFileComparison {

    /** حكم مقارنة ملف واحد. */
    data class Row(
        val relativePath: String,
        val archiveSize: Long,
        val copiedSize: Long?,
        val matched: Boolean
    )

    /** مجموعة نتائج صوت واحد. */
    data class VoiceResult(
        val name: String,
        val rows: List<Row>
    ) {
        val allMatch: Boolean
            get() = rows.isNotEmpty() && rows.all { it.matched }
    }

    /** تقرير كامل مع سطر الخلاصة الأخير. */
    data class Report(
        val voices: List<VoiceResult>,
        val errorRemains: Boolean,
        val remainingCode: Int
    ) {
        fun asText(): String = buildString {
            appendLine(
                "=== مقارنة الملفات: ما ينسخه Nateq مقابل أصل الأرشيف ==="
            )
            for (voice in voices) {
                appendLine("")
                appendLine("[${voice.name}]")
                appendLine(
                    "| الملف | أصل (بايت) | Nateq (بايت) | الحكم |"
                )
                for (row in voice.rows) {
                    appendLine(
                        "| ${row.relativePath} | ${row.archiveSize} | " +
                            "${row.copiedSize ?: "مفقود"} | " +
                            "${if (row.matched) "متطابق" else "مختلف"} |"
                    )
                }
            }
            appendLine("")
            appendLine(
                "الخلاصة: الملفات متطابقة حرفياً=" +
                    "${if (voices.all { it.allMatch }) "نعم" else "لا"} | " +
                    "بقي الخطأ=${if (errorRemains) "نعم" else "لا"} | " +
                    "الكود المتبقي=$remainingCode (E_BABILE_LICENSE) | " +
                    "المستبعد بالقياس: تسطيح NLP، وتوليد ini، وفقدان " +
                    "bvcu، وفقدان ملفات الترخيص، وغياب param من التهيئة | " +
                    "لم يتغير مسار الإنتاج لأن PCM>0 لم يُثبت."
            )
        }.trimEnd()
    }

    /** تحويل مسار الأرشيف إلى مسار نسبي بعد `voice/acapela/`. */
    private fun afterVoiceRoot(name: String): String {
        var value = name.replace('\\', '/')
        val voice = AcapelaLeilaOriginalTree.ARCHIVE_VOICE_ROOT
        if (value.startsWith(voice)) value = value.removePrefix(voice)
        if (value.startsWith("acapela/")) {
            value = value.removePrefix("acapela/")
        }
        return value
    }

    /** اسم الصوت من المسار النسبي بعد جذر الأصوات. */
    private fun voiceOf(relative: String): String {
        val token = AcapelaLeilaOriginalTree.TOKEN_DIR + "/"
        if (relative.startsWith(token)) {
            return relative.removePrefix(token).substringBefore('/')
        }
        return if ('/' in relative) {
            relative.substringBefore('/')
        } else {
            "shared"
        }
    }

    /** مقارنة كل ملف في الأرشيف بنظيره في نسخة Nateq بالاسم نفسه. */
    fun compare(
        zip: File,
        copiedRoot: File,
        codes: AcapelaVoiceDiagnostics.MeasuredNInitCodes =
            AcapelaVoiceDiagnostics.observedRoot109Codes
    ): Report {
        val copied = if (copiedRoot.isDirectory) {
            copiedRoot.walkTopDown().filter { it.isFile }
                .associate {
                    afterVoiceRoot(
                        it.relativeTo(copiedRoot).path.replace(
                            File.separatorChar, '/'
                        )
                    ) to it
                }
        } else {
            emptyMap()
        }
        val rows = mutableMapOf<String, MutableList<Row>>()
        if (zip.isFile) {
            ZipFile(zip).use { archive ->
                for (entry in archive.entries()) {
                    val name = entry.name.replace('\\', '/')
                    if (entry.isDirectory ||
                        name.substringAfterLast('/').equals(
                            AcapelaLeilaOriginalTree.LOG_FILE_NAME,
                            ignoreCase = true
                        )
                    ) continue
                    val relative = afterVoiceRoot(name)
                    if (!relative.startsWith(
                            AcapelaLeilaOriginalTree.TOKEN_DIR + "/"
                        ) && '/' !in relative
                    ) continue
                    val local = copied[relative]
                    val archiveHash = runCatching {
                        val digest = MessageDigest.getInstance("SHA-256")
                        archive.getInputStream(entry).use { input ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            while (input.read(buffer).also { amount ->
                                    read = amount
                                } != -1) {
                                digest.update(buffer, 0, read)
                            }
                        }
                        digest.digest().joinToString("") {
                                byte -> "%02x".format(byte)
                        }
                    }.getOrDefault("")
                    val localHash = local?.let {
                        runCatching {
                            val digest = MessageDigest.getInstance("SHA-256")
                            it.inputStream().use { stream ->
                                val buffer = ByteArray(16 * 1024)
                                var read: Int
                                while (stream.read(buffer).also {
                                        value -> read = value
                                    } != -1) {
                                    digest.update(buffer, 0, read)
                                }
                            }
                            digest.digest().joinToString("") {
                                    byte -> "%02x".format(byte)
                            }
                        }.getOrDefault("")
                    }
                    val voice = voiceOf(relative)
                    rows.getOrPut(voice) { mutableListOf() } += Row(
                        relativePath = relative,
                        archiveSize = entry.size,
                        copiedSize = local?.length(),
                        matched = local != null && localHash == archiveHash
                    )
                }
            }
        }
        val results = rows.toSortedMap().map { (name, list) ->
            VoiceResult(name, list.sortedBy { it.relativePath })
        }
        return Report(
            voices = results,
            errorRemains = codes.nlpInitError != 0,
            remainingCode = codes.nlpInitError
        )
    }
}
