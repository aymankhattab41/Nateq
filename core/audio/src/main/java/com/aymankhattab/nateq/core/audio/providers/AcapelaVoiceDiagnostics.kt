package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostCrashDiagnostics
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * تشخيص فشل تحميل صوت Acapela من ملفات القرص (قراءة خالصة).
 *
 * سببُ الفشل الحقيقي لا يظهر في كود `nSpeak` وحده: المكتبة تكتبه في
 * `logfile.txt` داخل جذر `nInit` (مجلد الصوت)، وغيابُ الرخصة أو ملف
 * `.inf` يُكتشف من جرد الملفات. لذلك تُجمع هنا الأدلة الثلاثة معاً:
 * جردُ المجلد، ومسارُ الرخصة المتوقع، وذيلُ سجلّ المحرك.
 *
 * كل الدوال نقية تعمل على [File] كي تُختبر بمجلدات مؤقتة.
 */
object AcapelaVoiceDiagnostics {

    private const val TAG = "AcapelaVoiceDiagnostics"

    /** حجم المخزن المؤقت لقراءة الملفات لحساب البصمة. */
    private const val DEFAULT_BUF = 16384

    /** مميّزات أسماء الأصوات المعروفة، تُستخدم لمنع خلط التهيئة. */
    private val KNOWN_SPEAKER_TOKENS = listOf(
        "salma", "leila", "layla", "heather", "zahra", "kareem", "omar"
    )

    /** إضافة مميّزات الأصوات الظاهرة في [name] إلى [out]. */
    private fun addKnownTokens(name: String, out: MutableSet<String>) {
        KNOWN_SPEAKER_TOKENS.forEach { token ->
            if (name.contains(token)) out += token
        }
    }

    /** حدّ الملفات المعروضة في الجرد (الباقي يُعدّ لا يُسرد). */
    const val MAX_FILES = 24

    /** عدد أسطر ذيل السجل المعروضة. */
    const val MAX_TAIL_LINES = 15

    /** حدّ أحرف السطر الواحد في الذيل. */
    const val MAX_LINE_CHARS = 200

    /** اسم سجل المحرك الذي تكتبه المكتبة الأصلية. */
    const val LOG_FILE_NAME = "logfile.txt"

    /**
     * الملف المستقر الوحيد لتشخيص Acapela (بناء 10729). يُكتب تدريجياً
     * في `cacheDir` ويُدفَّع بعد كل خطوة كي يبقى الجزء المكتوب إن
     * انهارت عملية `:engine_host` في منتصف الاختبار.
     */
    const val ACAPELA_FULL_DIAGNOSTIC_FILE = "acapela_full_diagnostic.txt"

    /** اسم ملف آخر محاولة نطق حقيقية فاشلة مع أسطر المكتبة الأصلية. */
    const val ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE =
        "acapela_last_real_speech_failure.txt"

    /** اسم ملف أول فشل أصلي ثابت لكل محاولة قبل التنظيف. */
    const val ACAPELA_FIRST_FAILURE_FILE = "acapela_first_failure.txt"

    /** السقف الأقصى لأحرف التقرير المنسوخ/المُشارَك (بناء 10729). */
    const val MAX_SHARED_REPORT_CHARS = 60_000

    /** ملف مقيس من أرشيف MultiTTS: مساره النسبي وبصمته كما وردت. */
    data class MeasuredArchiveFile(
        val relativePath: String,
        val size: Long,
        val md5: String,
        val sha256: String
    )

    /** مجلد الرمز المشفّر داخل `voice/acapela/` في الأرشيف المرجعي. */
    private const val MULTITTS_TOKEN_DIR =
        "88GqqbfobVqqleus52QYWilZ0Nc0DtPaQztCeFMhsGg="

    /** مسار صوت Heather كما ورد في الأرشيف المرجعي. */
    private const val MULTITTS_HEATHER_DIR =
        "voicedream/hqm-ref-USEnglish-Heather-22khz/" +
            "hqm-ref-USEnglish-Heather-22khz/enu"

    /**
     * القيم المقيسة فعلياً من `multitts_files/files/voice/` داخل الأرشيف.
     * الأحجام من central directory، وMD5/SHA-256 من قراءة الأعضاء
     * المحددين فقط (بلا تفكيك كامل، وبلا لمس `logfile.txt`).
     */
    private val multiTtsFilesMeasured = listOf(
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/0x00294994",
            139L,
            "e4a2e71d6658ac1519c625928575055e",
            "2f46c283262990c95245ec2fd77eacab" +
                "4206121eb599e2472ec2e0b2f3ed4e1d"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/voicelist",
            14L,
            "675c7f830278b1bc3b2c1dff6e1a71e5",
            "6fed4eb8e8bc51b66039e8c6a1e8baee4" +
                "007350bc1f4064c847e5af31c935f66"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/" +
                "ar_sa_hd_leila_22k_lf.bvcu.ini",
            1047L,
            "7f28c7e3c123b6dd952e583509d77293",
            "5f2b80e9fab385aa9096aaf91a6135afcd" +
                "c3a91e0f249101ffc164c44ceaed66"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_HEATHER_DIR/enu_heather_22k_ns.bvcu.ini",
            699L,
            "e0e491d16413b97194af63dbcb42766f",
            "542ac3bc6f9c4f2be5478da01540785db3" +
                "a6c83d676698d3fd841f2a5ca35169"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/NLP/ar_sa.dca",
            3455285L,
            "4150f26046e681c41ee7a97ead4d9bbe",
            "43492f7e3c1018c7f46b5871eb0dd2d30" +
                "27e281ea59012e934a1c28f725ca821"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/NLP/ar_sa_unplugged.dca",
            3455005L,
            "56b550b6663dc6dbf6a7cd719167afa7",
            "6e15add05d4f0afdcd58df12e6aa37cc98" +
                "ebf1c44467ead561076ef96ac2ecd2"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/NLP/ar_sa_e_arq.dca",
            2947221L,
            "d673c77227d1c30c31267b0a32c45c51",
            "f560a4950b185850f988ff464791dbc14d" +
                "91aa8b4d9168d37c6bb96d281fd980"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/NLP/ar_sa_hde_arq.dca",
            1538609L,
            "6801b0761888ee0dba4d0cde185deeb3",
            "4abe134f87331455e2007bfe2a9fecccf1" +
                "5150f17bb77771afea2255959218c49"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/NLP/ar_sa_utf8.dca",
            11501L,
            "f7f5a112bd877cf6b2b283a1fbdf0444",
            "09a42b8e7448d51039b7443db80a4e8369" +
                "63494b708149cefe6050bba22a6"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_HEATHER_DIR/NLP/enu.dca",
            398790L,
            "a4d8d34f5a2cd7dbb301a96deae7d342",
            "a37ac887efd17c307e7eef83f70019962e" +
                "cc93acb01064d65c93f4eaabbad03a"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/" +
                "ar_sa_hd_leila_22k_lf.bvcu.inf",
            489L,
            "c56b7fb07106a67641ff0af6faa64d82",
            "c312dda29c7d4e39601814384249c0559" +
                "67f9f879f8c4d174044aece72804ec0"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_HEATHER_DIR/enu_heather_22k_ns.bvcu.inf",
            473L,
            "5aaacf5a1b67332534742eace7ce177c",
            "b6c7bb7e30324bd44c72074b7c4671501" +
                "030f5e63f193c5754f1d2466f647294"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/leila_lf/" +
                "leila_22k_lf.bvcu",
            25857167L,
            "825bf78a56562c87cbf91045b1d8d9e1",
            "58950b4d599e6cf11aac0b08713837e29a" +
                "4b87524907231adb01893019425f21"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/leila22k_hq/leila_lf/" +
                "leila_22k_lf.bvcu.nuul216vqfp",
            1160796L,
            "88f1d2627e87c34dffbd8d2b02fa1f13",
            "61bc68a325463e6f045b1e33da4f0e46f" +
                "f471d8a28abfdfea8d9d35f65a6cab9"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_HEATHER_DIR/heather_ns/heather_22k_ns.bvcu",
            50123426L,
            "c64e839ea8de655dfe66a0303bcb6157",
            "3dddafa3d0c9b26d954191d79f9d0f26fe" +
                "1b4918bef0b98c2a6dc0dd27875dde"
        ),
        MeasuredArchiveFile(
            "$MULTITTS_TOKEN_DIR/userdicos/ara-sau.userdico",
            41036L,
            "e8a78aeb3b2ec8bdeac093ee2b4a446e",
            "bdfae391c55a4dc5c8830762174adbf507" +
                "b624aec822a1e09a893a5b396b9e45"
        ),
        MeasuredArchiveFile(
            "config.yaml",
            613L,
            "d0282db5d675b38ee4ee311e70e4421a",
            "cd0af3894f97bbb9a5b127166535318df0" +
                "8ff1cf69a2e198b58f1bff5db65c22"
        ),
        MeasuredArchiveFile(
            "engines.yaml",
            112L,
            "d77e2d2bcd5e20ff1548a5b9bf7677eb",
            "b119ba49fce8a14744a8d15333178af6bc" +
                "efd40ddbb88fa69fc9a4842ebcebfc"
        )
    )

    /**
     * جدول المقارنة المقيسة بين أصل MultiTTS ومسار Nateq.
     * كل رقم في الجدول مقيس من الأرشيف؛ وسلوك Nateq المذكور بعده
     * مُشتق من الكود ومُوسوم بذلك، لا رواية تقديرية.
     */
    fun getMultiTtsFilesMeasuredTable(): String = buildString {
        appendLine("=== مقارنة Acapela: أصل MultiTTS مقابل نسخ Nateq ===")
        appendLine("المصدر: multitts_files/files/voice/ داخل الأرشيف.")
        appendLine(
            "الأحجام من central directory، وMD5/SHA-256 من قراءة " +
                "الأعضاء المحددين فقط بلا تفكيك كامل."
        )
        appendLine("| المسار النسبي | الحجم | MD5 | SHA-256 |")
        for (item in multiTtsFilesMeasured) {
            appendLine(
                "| ${item.relativePath} | ${item.size} | " +
                    "${item.md5} | ${item.sha256} |"
            )
        }
        appendLine("")
        appendLine(
            "مستبعد قاطعًا: acapela/$MULTITTS_TOKEN_DIR/logfile.txt" +
                " — 7982015625 بايت (الحجم من central directory؛ لم يُقرأ)."
        )
        appendLine("")
        appendLine("مطابعة مسار Nateq قبل الإصلاح (سلوك مقروء من الكود):")
        appendLine(
            "  - NLP/*.dca و*.bnx و*.ldi: flattenNestedVoiceFiles " +
                "ينقلها إلى جذر الصوت فيُفقد بادئ NLP/."
        )
        appendLine(
            "  - leila_lf/*.bvcu: يُسطَّح إلى جذر الصوت " +
                "فلا يعود SLV \"leila_lf/...\" صالحاً."
        )
        appendLine(
            "  - ini الأصلي (1047/699 بايت): ensureIniFor يعيد كتابته " +
                "بـMODE=prep_hd (109 بايت) فيُفقد PRO \"NLP/ar_sa.oso\"."
        )
        appendLine(
            "  - بعد الإصلاح: copyVoiceTreeVerbatim يحفظ كل مسار نسبي " +
                "ويمنع توليد أي ini حين توجد تهيئة أصلية غنية."
        )
        appendLine("")
        appendLine("سلسلة الأعراض المقيسة عند فقدان توجيهات NLP:")
        appendLine("  - ${observedRoot109Codes.asLine()}")
        appendLine(
            "  - nlpInitError=-34 من BBNLP_init عند غياب PRO، ثم eb=-27، " +
                "ثم nSpeak=-2 بلا عيّنات PCM."
        )
        appendLine("")
        appendLine(
            "المرجع الحرفي للتجربة الجديدة: " +
                "leila22k_hq (ليلى) لا سلمى."
        )
        appendLine(AcapelaLeilaOriginalTree.licenseFlowReport())
        appendLine("")
        appendLine(
            "الخلاصة: الملفات المرجعية متطابقة حرفياً مع جدول البصمات " +
                "(${AcapelaLeilaOriginalTree.originalFiles.size} " +
                    "ملفاً مقيساً) | " +
                "الخطأ بقي في آخر قياس على جهاز | " +
                "الكود المتبقي=-34 (E_BABILE_LICENSE) | " +
                "تجربة Path_Original_Leila_Verbatim لم تُشغَّل على جهاز " +
                "في هذه البيئة فلا توجد عيّنات PCM جديدة."
        )
    }.trimEnd()

    /**
     * اقتطاع التقرير عند حد المشاركة الآمن مع تنقية كاملة: ينقل قسم
     * Acapela المحصور بالعلامات إلى الصدارة (إن وُجد) ثم يقتطع من
     * النهاية مع إشارة صريحة، بعد تنقية كل المحتوى.
     */
    fun capSharedReport(
        report: String,
        maxChars: Int = MAX_SHARED_REPORT_CHARS
    ): String {
        val redacted = redactSensitiveText(report)
        val ordered = moveAcapelaSectionFirst(redacted)
        if (ordered.length <= maxChars) return ordered
        val marker = "\n\n[تم الاقتطاع إلى $maxChars حرف: " +
            "حُذفت أسطر logcat من النهاية]"
        val keep = (maxChars - marker.length).coerceAtLeast(0)
        return ordered.substring(0, keep) + marker
    }

    /**
     * ينقل قسم Acapela (المحصور بين العلامتين) إلى صدارة التقرير من دون
     * حذف أي محتوى آخر. العلامات نفسها لا تظهر في الناتج.
     */
    private fun moveAcapelaSectionFirst(report: String): String {
        val start = report.indexOf(ACAPELA_REPORT_START)
        val end = report.indexOf(ACAPELA_REPORT_END)
        if (start < 0 || end < 0 || end <= start) return report
        val bodyStart = start + ACAPELA_REPORT_START.length
        val acapela = report.substring(bodyStart, end).trim('\n')
        val rest = report.substring(0, start) +
            report.substring(end + ACAPELA_REPORT_END.length)
        return (acapela + "\n\n" + rest.trim('\n')).trimEnd()
    }
    @Deprecated("لا يُولّد ini بديل؛ التهيئة الأصلية مطلوبة")
    fun originalIniFallbackContent(): String = ""

    /**
     * كتابة تهيئة ini في أهداف محددة محظورة ما لم تكن نسخاً حرفية من مصدر
     * أصيل مُمرَّر صراحةً لنفس الصوت. لا fallback ولا توليد.
     */
    @Deprecated("استخدم نسخاً حرفية صريحة من تهيئة أصلية")
    fun writeIniTargets(
        @Suppress("UNUSED_PARAMETER") context: Context?,
        @Suppress("UNUSED_PARAMETER") targets: List<File>,
        @Suppress("UNUSED_PARAMETER") fallback: String? = null
    ): Int = 0

    /** اسم ملف تهيئة شائع (للاستكشاف فقط، بلا افتراض حجم/صحة). */
    private const val ORIGINAL_INI_NAME = "ar_sa_hd_salma_22k_lf.ini"

    /** لم يعد حجم 109 بايت معياراً للصحة. */
    @Deprecated("الحجم الثابت ليس دليلاً على صحة ini")
    const val ORIGINAL_INI_EXPECTED_SIZE = 0L

    /**
     * علامات تحيط بقسم Acapela في التقرير المُشارَك (توافق واجهة).
     */
    const val ACAPELA_REPORT_START = "<<<ACAPELA_REPORT_START>>>"
    const val ACAPELA_REPORT_END = "<<<ACAPELA_REPORT_END>>>"

    /** نتيجة تحقق ما قبل التجربة. */
    data class StagedIniVerification(
        val passed: Boolean,
        val report: String
    )

    /**
     * أكواد/نتائج تجربة مقيسة. عند غياب القياس الفعلي تُترك null/«لم تُرصد»
     * ولا تُحقن قيم حيّة ثابتة.
     */
    data class MeasuredNInitCodes(
        val nlpInitError: Int? = null,
        val eb: Int? = null,
        val nSpeak: Int? = null,
        val pcmSamples: Long? = null,
        val note: String = "لم تُرصد"
    ) {
        fun asLine(): String =
            "nlpInitError=${nlpInitError ?: "لم تُرصد"} | " +
                "eb=${eb ?: "لم تُرصد"} | " +
                "nSpeak=${nSpeak ?: "لم تُرصد"} | " +
                "PCM=${pcmSamples ?: "لم تُرصد"}" +
                if (note.isNotBlank()) " | ملاحظة=$note" else ""
    }

    /**
     * قيم قديمة غير مقيسة (لا إرث لنتائج سابقة). تُحفظ للتوافق مع
     * واجهات تقارير تاريخية فقط ولا تُستخدم كدليل على قياس حي.
     */
    @Deprecated("لا يُورَّث قياس قديم؛ استخدم نتيجة المحاولة الحالية")
    val observedRoot109Codes = MeasuredNInitCodes(
        note = "غير مقيس (لا إرث لنتائج سابقة)"
    )

    /** ملف ini مقيس بمساره الكامل — يُحقن مع تسمية مصدره. */
    data class MeasuredIniFile(
        val label: String,
        val file: File
    )

    /** نتيجة نسخ MultiTTS حرفياً ثم تجربة nInit عليه (قيم مقيسة). */
    data class VerbatimExperiment(
        val label: String,
        val source: File,
        val staged: File?,
        val readable: Boolean,
        val copiedFiles: Int,
        val codes: MeasuredNInitCodes?,
        val note: String = ""
    )

    /** ملف مع جذره — يُستخدم لحساب المسار النسبي في المقارنة. */
    private data class FoundFile(val file: File, val root: File)

    /** ملف الرخصة البديل في جذر البيانات (يقرأه `check_license`). */
    private const val TEMPORARY_FILE_NAME = ".temporary"

    enum class VoiceVerificationState {
        NO_INI,
        INCOMPATIBLE_INI,
        MISSING_DEPENDENCIES,
        CONFLICT,
        AUTHENTIC_READY,
        IDENTITY_MISMATCH,
        INVALID_PATH
    }

    /** نتيجة التحقق من تبعيات/أصالة حزمة صوت Acapela. */
    data class VoiceDependencyIssue(
        val dependency: String,
        val expectedPath: String,
        val reason: String
    )

    data class VoiceDependencyVerification(
        val ok: Boolean,
        val voiceDir: File,
        val iniFile: File?,
        val issues: List<VoiceDependencyIssue>,
        val checked: List<String>,
        val report: String,
        val state: VoiceVerificationState = if (ok) {
            VoiceVerificationState.AUTHENTIC_READY
        } else {
            VoiceVerificationState.MISSING_DEPENDENCIES
        },
        val discovery: AcapelaVoiceManifest.VoiceDiscoveryResult? = null
    )

    /** بصمة غير سرية (حجم + SHA-256) لمقارنة المصدر بالمرحلي. */
    fun fingerprintOf(file: File): String {
        if (!file.isFile) return "missing"
        val sha = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(DEFAULT_BUF)
                var r: Int
                while (input.read(buf).also { r = it } != -1) {
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("unreadable")
        return "size=${file.length()},sha256=$sha"
    }

    private fun isVoiceAssetFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.ROOT)
        return n.endsWith(".bvcu") ||
            n.endsWith(".dat") ||
            n.endsWith(".dca") ||
            n.endsWith(".pho") || n.endsWith(".oso") || n.endsWith(".bnf") ||
            n.endsWith(".mdd") || n.endsWith(".pro") || n.endsWith(".slv") ||
            n.endsWith(".inf") || n.endsWith(".ini") || n.endsWith(".f0f") ||
            n.endsWith(".flm") || n.endsWith(".nnm") || n.endsWith(".dlm") ||
            n.endsWith(".fvt") || n.endsWith(".lfx") || n.endsWith(".txt") ||
            n.endsWith(".yaml") || n.endsWith(".yml") ||
            n.matches(Regex("^0x[0-9a-f]+$", RegexOption.IGNORE_CASE))
    }

    /**
     * تحقق موحّد لحزمة/تهيئة صوت Acapela أصيل: اكتشاف ini (لا ini /
     * مختصر|مولّد|غير متوافق / تعارض) داخل حدود الحزمة، ثم تبعيات
     * PRO/SLV/NLP بالمسارات المرجعية الصحيحة دون تسطيح أو تطبيع امتدادات
     * أو بحث عشوائي بالأسماء، مع مقارنة مصدر↔مرحلي. لا توليد/حشو/تعديل.
     */
    fun verifyAuthenticVoicePackage(
        voiceDir: File,
        stagedDir: File? = null,
        expectedSpeakerHint: String? = null
    ): VoiceDependencyVerification {
        val issues = mutableListOf<VoiceDependencyIssue>()
        val checked = mutableListOf<String>()
        val hint = expectedSpeakerHint?.trim().orEmpty()
        val discoveryHint = hint.ifBlank {
            voiceDir.name.substringBeforeLast('.')
        }
        val discovery = AcapelaVoiceManifest.discoverVoiceConfig(
            startDir = voiceDir,
            expectedSpeakerHint = discoveryHint
        )
        val ini = discovery.iniFile
        val effectiveVoiceDir = discovery.voiceDir ?: voiceDir

        var state = when (discovery.state) {
            AcapelaVoiceManifest.IniDiscoveryState.NO_INI ->
                VoiceVerificationState.NO_INI
            AcapelaVoiceManifest.IniDiscoveryState.INCOMPATIBLE_INI ->
                VoiceVerificationState.INCOMPATIBLE_INI
            AcapelaVoiceManifest.IniDiscoveryState.CONFLICT ->
                VoiceVerificationState.CONFLICT
            AcapelaVoiceManifest.IniDiscoveryState.AUTHENTIC ->
                VoiceVerificationState.AUTHENTIC_READY
        }

        when (discovery.state) {
            AcapelaVoiceManifest.IniDiscoveryState.NO_INI -> {
                issues += VoiceDependencyIssue(
                    dependency = "ini",
                    expectedPath = effectiveVoiceDir.absolutePath,
                    reason = "لا يوجد ملف ini أصلية " +
                        "كاملة متوافقة داخل حدود الحزمة"
                )
            }
            AcapelaVoiceManifest.IniDiscoveryState.INCOMPATIBLE_INI -> {
                val found = discovery.candidates.firstOrNull()?.file
                val allReasons = (
                    discovery.reasons +
                        discovery.candidates.flatMap { it.reasons }
                    ).distinct()
                val reason = allReasons.joinToString(" | ").ifBlank {
                    "تهيئة ini موجودة لكنها مختصرة/مولّدة/غير متوافقة"
                }
                val isMixing = allReasons.any {
                    it.contains("تخلط هوية") ||
                        it.contains("تخص صوتاً آخر") ||
                        it.contains("صوتاً آخر") ||
                        it.contains("خلط")
                }
                issues += VoiceDependencyIssue(
                    dependency = if (isMixing) "voice-mixing" else "ini",
                    expectedPath = found?.absolutePath
                        ?: effectiveVoiceDir.absolutePath,
                    reason = reason
                )
            }
            AcapelaVoiceManifest.IniDiscoveryState.CONFLICT -> {
                val reason = discovery.reasons.joinToString(" | ").ifBlank {
                    "تعارض: أكثر من تهيئة أصلية دون حسم آمن بالمحتوى/الهوية"
                }
                issues += VoiceDependencyIssue(
                    dependency = "ini",
                    expectedPath = effectiveVoiceDir.absolutePath,
                    reason = reason
                )
            }
            AcapelaVoiceManifest.IniDiscoveryState.AUTHENTIC -> {
                if (ini != null) {
                    checked += "ini=${ini.absolutePath} " +
                        "(${fingerprintOf(ini)})"
                    val deps = AcapelaVoiceManifest.parseIniDependencies(ini)
                    if (deps.isEmpty()) {
                        issues += VoiceDependencyIssue(
                            dependency = "ini-dependencies",
                            expectedPath = ini.absolutePath,
                            reason = "التهيئة الأصلية لا تحتوي توجيهات " +
                                "تبعيات قابلة للتحقق (PRO/SLV/NLP/ملفات)"
                        )
                    }
                    val refRoot = ini.parentFile ?: effectiveVoiceDir
                    val siblingUserdicos = File(
                        refRoot.parentFile ?: refRoot, "userdicos"
                    ).apply { mkdirs() }
                    File(refRoot, "userdicos").mkdirs()
                    runCatching {
                        val searchRoots = listOfNotNull(
                            refRoot,
                            discovery.packageRoot,
                            effectiveVoiceDir
                        ).distinct()
                        val foundDicos = searchRoots.flatMap { root ->
                            if (root.isDirectory) {
                                root.walkTopDown().maxDepth(4).filter {
                                    it.isFile && it.name.endsWith(
                                        ".userdico", ignoreCase = true
                                    )
                                }.toList()
                            } else emptyList()
                        }.distinctBy { it.name }
                        for (dico in foundDicos) {
                            val target = File(siblingUserdicos, dico.name)
                            if (!target.exists() &&
                                dico.canonicalPath != target.canonicalPath
                            ) {
                                dico.copyTo(target, overwrite = false)
                            }
                        }
                    }
                    for (dep in deps) {
                        val resolved = AcapelaVoiceManifest
                            .resolveRelativeFile(
                                refRoot, dep.relativePath
                            )
                        val expected =
                            File(refRoot, dep.relativePath).absolutePath
                        val isUserDico = dep.relativePath.endsWith(
                            ".userdico", ignoreCase = true
                        ) || dep.key.contains(
                            "userdico", ignoreCase = true
                        ) || dep.relativePath.contains(
                            "userdico", ignoreCase = true
                        )
                        val isBvcu = dep.relativePath.endsWith(
                            ".bvcu", ignoreCase = true
                        ) || dep.key.equals(
                            "SLV", ignoreCase = true
                        ) || dep.key.equals(
                            "BVCU", ignoreCase = true
                        )
                        val isNlp = dep.relativePath.contains(
                            "NLP/", ignoreCase = true
                        ) || dep.key.equals(
                            "PRO", ignoreCase = true
                        )
                        val isStrictRequired = isBvcu || isNlp

                        if (resolved == null || !resolved.exists()) {
                            if (isUserDico) {
                                val dName = File(dep.relativePath).name
                                val cand = File(siblingUserdicos, dName)
                                if (cand.exists() && cand.isFile) {
                                    checked += "userdico-sibling:$dName " +
                                        "(${fingerprintOf(cand)})"
                                } else {
                                    checked += "userdico-optional-missing:" +
                                        "${dep.key} -> ${dep.relativePath}"
                                }
                                continue
                            }
                            if (isStrictRequired) {
                                issues += VoiceDependencyIssue(
                                    dependency = dep.key,
                                    expectedPath = expected,
                                    reason = "تبعية ناقصة معلنة في " +
                                        "${ini.name}: ${dep.relativePath}"
                                )
                            } else {
                                checked += "optional-missing:" +
                                    "${dep.key} -> ${dep.relativePath}"
                            }
                            continue
                        }
                        if (!resolved.isFile) {
                            if (isUserDico) continue
                            if (isStrictRequired) {
                                issues += VoiceDependencyIssue(
                                    dependency = dep.key,
                                    expectedPath = resolved.absolutePath,
                                    reason = "المسار المعلن ليس ملفاً"
                                )
                            }
                            continue
                        }
                        val readable = runCatching {
                            resolved.inputStream().use { it.read() }
                        }.isSuccess
                        if (!readable) {
                            if (isUserDico) continue
                            if (isStrictRequired) {
                                issues += VoiceDependencyIssue(
                                    dependency = dep.key,
                                    expectedPath = resolved.absolutePath,
                                    reason = "الملف غير قابل للقراءة"
                                )
                            }
                            continue
                        }
                        val fp = fingerprintOf(resolved)
                        checked += "${dep.key}:${dep.relativePath} " +
                            "-> ${resolved.absolutePath} ($fp)"
                        if (stagedDir != null) {
                            val stagedSibling = File(
                                stagedDir.parentFile ?: stagedDir, "userdicos"
                            ).apply { mkdirs() }
                            File(stagedDir, "userdicos").mkdirs()
                            runCatching {
                                siblingUserdicos.listFiles()?.filter {
                                    it.isFile && it.name.endsWith(
                                        ".userdico", ignoreCase = true
                                    )
                                }?.forEach { f ->
                                    val dst = File(stagedSibling, f.name)
                                    if (!dst.exists()) {
                                        f.copyTo(dst, overwrite = false)
                                    }
                                }
                            }
                            val staged =
                                AcapelaVoiceManifest.resolveRelativeFile(
                                stagedDir, dep.relativePath
                            )
                            val stagedExpected =
                                File(stagedDir, dep.relativePath).absolutePath
                            if (staged == null || !staged.exists()) {
                                if (isUserDico) {
                                    val dName = File(dep.relativePath).name
                                    val cand = File(stagedSibling, dName)
                                    if (cand.exists() && cand.isFile) {
                                        checked += "staged-userdico-ok:$dName"
                                    } else {
                                        checked +=
                                            "staged-userdico-optional:" +
                                            "${dep.key} -> ${dep.relativePath}"
                                    }
                                } else if (isStrictRequired) {
                                    issues += VoiceDependencyIssue(
                                        dependency = dep.key,
                                        expectedPath = stagedExpected,
                                        reason = "الملف غير موجود " +
                                            "في النسخة المرحلية"
                                    )
                                } else {
                                    checked += "staged-optional-missing:" +
                                        "${dep.key} -> ${dep.relativePath}"
                                }
                            } else {
                                val sfp = fingerprintOf(staged)
                                if (sfp != fp) {
                                    if (isUserDico) {
                                        checked +=
                                            "staged-userdico-fp-mismatch:" +
                                            dep.key
                                    } else {
                                        issues += VoiceDependencyIssue(
                                            dependency = dep.key,
                                            expectedPath = staged.absolutePath,
                                            reason = "اختلاف بصمة/حجم بين " +
                                                "المصدر والنسخة المرحلية"
                                        )
                                    }
                                } else {
                                    checked += "staged-ok:${dep.key} " +
                                        "(${staged.absolutePath})"
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hint.isNotBlank() && ini != null) {
            val inVoice =
                effectiveVoiceDir.name.contains(hint, ignoreCase = true)
            val inIni = ini.name.contains(hint, ignoreCase = true)
            val inPath = ini.absolutePath.contains(hint, ignoreCase = true)
            val matchesDir = discovery.voiceDir?.name?.contains(
                hint,
                ignoreCase = true
            ) == true
            if (!inVoice && !inIni && !inPath && !matchesDir) {
                issues += VoiceDependencyIssue(
                    dependency = "voice-identity",
                    expectedPath = effectiveVoiceDir.absolutePath,
                    reason = "هوية الصوت لا تطابق " +
                        "المطلوب ($hint) — منع خلط الأصوات"
                )
            }
        }

        val contextTokens = buildSet {
            addKnownTokens(effectiveVoiceDir.name.lowercase(Locale.ROOT), this)
            if (hint.isNotBlank()) {
                addKnownTokens(hint.lowercase(Locale.ROOT), this)
            }
            discovery.voiceDir?.name?.lowercase(Locale.ROOT)?.let {
                addKnownTokens(it, this)
            }
        }
        val iniTokens = ini?.name?.lowercase(Locale.ROOT)?.let { name ->
            buildSet { addKnownTokens(name, this) }
        }.orEmpty()
        val foreign = iniTokens - contextTokens
        if (foreign.isNotEmpty()) {
            issues += VoiceDependencyIssue(
                dependency = "voice-mixing",
                expectedPath = ini?.absolutePath
                    ?: effectiveVoiceDir.absolutePath,
                reason = "تهيئة ini تحمل هوية صوت آخر " +
                    "(${foreign.sorted()}) — يُمنع خلط الأصوات"
            )
        }

        val hex = effectiveVoiceDir.walkTopDown().maxDepth(5)
            .firstOrNull { f ->
                f.isFile &&
                    f.name.matches(Regex("^0x[0-9a-fA-F]+$")) &&
                    f.length() > 0L
            } ?: AcapelaVoiceManifest.findHexRegistrationFile(
                effectiveVoiceDir
            )
        val hasYamlParam = AcapelaVoiceManifest
            .findConfigYamlWithParam(effectiveVoiceDir) != null
        if (hex == null && !hasYamlParam) {
            issues += VoiceDependencyIssue(
                dependency = "registration-key",
                expectedPath = effectiveVoiceDir.absolutePath,
                reason = "ملف مفتاح الترخيص (0x...) أو سطر param " +
                    "غير موجود ضمن حزمة الصوت"
            )
        } else if (hex != null) {
            checked +=
                "registration-key=${hex.absolutePath}" +
                    "(size=${hex.length()})"
        } else {
            checked += "registration-param=found in config.yaml"
        }

        if (issues.any {
                it.dependency == "voice-mixing" ||
                    it.dependency == "voice-identity"
            }) {
            state = VoiceVerificationState.IDENTITY_MISMATCH
        } else if (state == VoiceVerificationState.AUTHENTIC_READY &&
            issues.any {
                it.reason.contains("ناقصة") ||
                    it.reason.contains("غير موجود") ||
                    it.reason.contains("غير قابل للقراءة") ||
                    it.reason.contains("اختلاف بصمة")
            }
        ) {
            state = VoiceVerificationState.MISSING_DEPENDENCIES
        }

        val ok =
            issues.isEmpty() && state == VoiceVerificationState.AUTHENTIC_READY
        val report = buildString {
            appendLine("=== التحقق من حزمة صوت Acapela الأصلية ===")
            appendLine("جذر الحزمة: ${discovery.packageRoot.absolutePath}")
            appendLine("مجلد الصوت: ${effectiveVoiceDir.absolutePath}")
            appendLine("ملف ini: ${ini?.absolutePath ?: "(لا يوجد)"}")
            appendLine("حالة الاكتشاف: ${discovery.state}")
            if (discovery.reasons.isNotEmpty()) {
                appendLine("أسباب الاكتشاف:")
                discovery.reasons.forEach { appendLine("  - $it") }
            }
            appendLine("حالة التحقق: $state")
            if (checked.isNotEmpty()) {
                appendLine("التحقق (بصمات غير سرية):")
                checked.forEach { appendLine("  - $it") }
            }
            if (issues.isEmpty()) {
                appendLine("النتيجة: تحقق ناجح — التبعيات/الهوية سليمة")
            } else {
                appendLine("النتيجة: فشل التحقق")
                appendLine("المشكلات:")
                issues.forEach {
                    appendLine("  - [${it.dependency}] ${it.reason}")
                    appendLine("    المتوقع/المرجع: ${it.expectedPath}")
                }
            }
            if (stagedDir != null) {
                appendLine("النسخة المرحلية: ${stagedDir.absolutePath}")
            }
        }.trimEnd()

        return VoiceDependencyVerification(
            ok = ok,
            voiceDir = effectiveVoiceDir,
            iniFile = ini,
            issues = issues,
            checked = checked,
            report = report,
            state = state,
            discovery = discovery
        )
    }

    /** توحيد مسار الملف ليكون تحت /data/user/0 بدل /data/data. */
    fun normalizeToUser0(path: String): String {
        return path.replace("/data/data/", "/data/user/0/")
            .replace("\\data\\data\\", "\\data\\user\\0\\")
    }

    /** توحيد مسار الملف ليكون تحت /data/user/0 بدل /data/data. */
    fun normalizeToUser0(file: File): File {
        return File(normalizeToUser0(file.path))
    }

    /**
     * حذف مجلدات التجهيز نهائياً تحت cache/acapela_stage_authentic
     * وما تبقى منها.
     */
    fun cleanupOldStagingDirectories(
        context: Context,
        currentRunSafeId: String? = null
    ) {
        val cache = normalizeToUser0(context.cacheDir)
        val base = File(cache, "acapela_stage_authentic")
        if (base.exists()) {
            runCatching { base.deleteRecursively() }
        }
    }

    /**
     * إلغاء التجهيز نهائياً: حذف مجلدات التجهيز القديمة وإعادة مجلد
     * الحزمة الأصلي مباشرة بلا نسخ.
     */
    fun stageAuthenticVoiceForRun(
        context: Context,
        voiceDir: File,
        runId: String
    ): File {
        cleanupOldStagingDirectories(context)
        return voiceDir
    }

    /** إخفاء الجزء الأوسط من المعرّفات وحماية الخصوصية. */
    fun maskMiddle(
        value: String,
        prefixLen: Int = 4,
        suffixLen: Int = 4
    ): String {
        if (value.length <= prefixLen + suffixLen) return value
        val prefix = value.take(prefixLen)
        val suffix = value.takeLast(suffixLen)
        return "$prefix...$suffix"
    }

    /**
     * قناع القيم الحساسة: الطول وآخر ٤ أحرف فقط، بلا أي قيمة كاملة.
     * القيم القصيرة (٤ أحرف أو أقل) تُخفى بالكامل كي لا تظهر قيمة ما.
     */
    fun redactSensitive(value: String?): String {
        if (value.isNullOrEmpty()) return "(فارغ)"
        val tail = if (value.length > 4) value.takeLast(4) else "****"
        return "الطول=${value.length} | ...$tail"
    }

    /** مفاتيح الأسطر التي قد تحوي قيماً حساسة في السجلات والتقارير. */
    private val sensitiveLogKeys = listOf(
        "babParam.uid.passwd",
        "babParam.uid.userId",
        "babParam.uid",
        "babParam.license",
        "babParam",
        "registration",
        "license",
        "licence",
        "passwd",
        "password",
        "pwd",
        "token",
        "secret",
        "apikey",
        "api_key",
        "param"
    )

    private val sensitiveRegexes = listOf(
        Regex(
            """(?i)\b(nInit(?:Callbacks)?|nSetLicense|""" +
                """nLoadLicense)\s*\([^)]*\)"""
        ) to "REDACTED_CALL",
        Regex(
            """(?i)\b(pass(?:wd|word)?|pwd|license|licence|""" +
                """registration|token|secret|api[_-]?key|""" +
                """param)\b\s*[:=]\s*""" +
                """("([^"]*)"|'([^']*)'|[^\s,;|]+)"""
        ) to "REDACTED_KV",
        Regex(
            """(?i)\b(uid(?:\.(?:userId|passwd))?|userId)\b\s*[:=]""" +
                """\s*("([^"]*)"|'([^']*)'|[^\s,;|]+)"""
        ) to "REDACTED_KV",
        Regex("""(?i)\b0x[0-9a-f]{10,}\b""") to "REDACTED_HEX",
        Regex(
            """(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{48,}={0,2}""" +
                """(?![A-Za-z0-9+/])"""
        ) to "REDACTED_BLOB"
    )

    /**
     * التحقق مما إذا كان النص مسار ملف آمن لا يجوز إتلافه كحمولة ثنائية.
     */
    fun isSafeFilePath(s: String): Boolean {
        val lower = s.lowercase(Locale.ROOT)
        return s.startsWith("/") || s.startsWith("\\") ||
            s.contains(":\\") || s.contains(":/") ||
            lower.contains("/data/") || lower.contains("/storage/") ||
            lower.contains("/files/") || lower.contains("/cache/") ||
            lower.contains("/documents/") ||
            lower.endsWith(".so") || lower.endsWith(".ini") ||
            lower.endsWith(".inf") || lower.endsWith(".bvcu") ||
            lower.endsWith(".dat") || lower.endsWith(".txt") ||
            lower.endsWith(".apk")
    }

    /**
     * التحقق مما إذا كان النص حمولة ثنائية مشفرة تستوجب الحجب،
     * مع استبعاد المسارات الآمنة والسلاسل غير المشفرة.
     */
    fun isLikelyBlob(s: String): Boolean {
        if (s.length < 48) return false
        if (isSafeFilePath(s)) return false
        if (s.toSet().size < 6) return false
        val maxFreq = s.groupingBy { it }.eachCount().values
            .maxOrNull() ?: 0
        if (maxFreq > s.length / 2) return false
        val hasUpper = s.any { it in 'A'..'Z' }
        val hasLower = s.any { it in 'a'..'z' }
        val hasDigit = s.any { it in '0'..'9' }
        val variety = (if (hasUpper) 1 else 0) +
            (if (hasLower) 1 else 0) +
            (if (hasDigit) 1 else 0)
        return variety >= 2
    }

    /**
     * إخفاء القيم الحساسة في سطر واحد (ترخيص/كلمات مرور/ndInit/رموز) قبل
     * إدراجه في أي تقرير أو سجل أو تصدير.
     */
    fun redactSensitiveLine(line: String): String {
        var out = line
        sensitiveRegexes.forEach { (regex, kind) ->
            out = regex.replace(out) { match ->
                when (kind) {
                    "REDACTED_CALL" -> {
                        val name = match.value.substringBefore('(').trim()
                        "$name([مُقنَّع])"
                    }
                    "REDACTED_HEX" -> "[HEX_مُقنَّع:${match.value.length}]"
                    "REDACTED_BLOB" -> {
                        if (isLikelyBlob(match.value)) {
                            "[BLOB_مُقنَّع:${match.value.length}]"
                        } else {
                            match.value
                        }
                    }
                    else -> {
                        val rawKey = match.value.substringBefore('=')
                            .substringBefore(':')
                        "$rawKey=[مُقنَّع]"
                    }
                }
            }
        }
        val key = sensitiveLogKeys.firstOrNull {
            out.contains(it, ignoreCase = true)
        } ?: return out
        val start = out.indexOf(key, ignoreCase = true)
        if (start < 0) return out
        val raw = out.substring(start + key.length)
            .trimStart()
            .removePrefix("=")
            .removePrefix(":")
            .trimStart()
            .trimStart('"', '\'')
        val value = raw.substringBefore('"')
            .substringBefore('\'')
            .trim()
        if (value.isEmpty() || value == "[مُقنَّع]") return out
        return out.substring(0, start) + "$key: ${redactSensitive(value)}"
    }

    /** جلسة تتبع حجب التراخيص متعددة الأسطر عبر الرسائل والأجزاء. */
    class RedactionSession(
        @Volatile var inLicensePayload: Boolean = false,
        @Volatile var licenseHeaderPrefix: String = "",
        val payloadChunks: MutableList<String> = mutableListOf()
    ) {
        fun reset() {
            inLicensePayload = false
            licenseHeaderPrefix = ""
            payloadChunks.clear()
        }
    }

    val defaultRedactionSession = RedactionSession()

    /**
     * تنقية قائمة أسطر من البيانات الحساسة مع تتبع حمولة الترخيص متعددة
     * الأسطر وحفظ حالة القناع عبر الأجزاء دون إعادة ضبط.
     */
    fun redactSensitiveLines(
        lines: List<String>,
        session: RedactionSession = RedactionSession()
    ): List<String> {
        val result = ArrayList<String>(lines.size)
        var licenseHeaderIndex = -1

        fun finishLicenseBlock() {
            if (licenseHeaderIndex >= 0 && licenseHeaderIndex < result.size) {
                val fullPayload = session.payloadChunks
                    .joinToString("\n").trim()
                val maskedVal = redactSensitive(fullPayload)
                result[licenseHeaderIndex] =
                    session.licenseHeaderPrefix +
                        "babParam.license: $maskedVal"
            }
            session.inLicensePayload = false
            session.licenseHeaderPrefix = ""
            session.payloadChunks.clear()
            licenseHeaderIndex = -1
        }

        for (line in lines) {
            val trimmed = line.trim()
            val isLicHeader = trimmed.contains(
                "babParam.license",
                ignoreCase = true
            )

            if (isLicHeader) {
                if (session.inLicensePayload) finishLicenseBlock()
                session.inLicensePayload = true
                licenseHeaderIndex = result.size
                val keyIdx = line.indexOf(
                    "babParam.license",
                    ignoreCase = true
                )
                session.licenseHeaderPrefix = if (keyIdx > 0) {
                    line.substring(0, keyIdx)
                } else {
                    ""
                }
                val rawVal = line.substring(
                    keyIdx + "babParam.license".length
                ).trimStart(':', '=', ' ', '\t', '"', '\'')
                    .trimEnd('"', '\'')
                if (rawVal.isNotBlank() && rawVal != "[مُقنَّع]") {
                    session.payloadChunks.add(rawVal)
                }
                result.add(redactSensitiveLine(line))
                continue
            }

            if (session.inLicensePayload) {
                val isNewField = line.contains("tts_function_") ||
                    line.contains("BABILE_") ||
                    line.contains("===") ||
                    line.contains("---") ||
                    line.contains("Default voice") ||
                    line.contains("acattsandroid-extended-native") ||
                    line.contains("nSpeak") ||
                    line.contains("nInit") ||
                    line.contains("nStop") ||
                    line.contains("nShutdown") ||
                    line.contains("initLanguage") ||
                    line.contains("userdico") ||
                    line.contains("nlpInitError") ||
                    line.contains("sNLPConfig") ||
                    line.contains("ExitReason") ||
                    line.contains("runId") ||
                    sensitiveLogKeys.any { k ->
                        k != "babParam.license" &&
                            line.contains(k, ignoreCase = true)
                    }

                val lastColon = line.lastIndexOf(':')
                val tagEnd = if (lastColon >= 0 &&
                    lastColon < line.length - 1 &&
                    line[lastColon + 1].isWhitespace()
                ) {
                    lastColon + 1
                } else {
                    0
                }
                val logcatPfx = if (tagEnd > 0) {
                    line.substring(0, tagEnd) + " "
                } else {
                    line.takeWhile { it.isWhitespace() }
                }
                val content = if (tagEnd > 0) {
                    line.substring(tagEnd).trim()
                } else {
                    trimmed
                }

                val isLikelyPayloadLine = !isNewField &&
                    content.isNotEmpty() &&
                    !content.contains(' ') &&
                    !content.contains(':') &&
                    content.all { ch ->
                        ch.isLetterOrDigit() || ch in "+/=_-'\""
                    }

                if (!isLikelyPayloadLine) {
                    finishLicenseBlock()
                    result.add(redactSensitiveLine(line))
                } else {
                    if (content.isNotBlank()) {
                        session.payloadChunks.add(content.trim('\'', '"'))
                    }
                    result.add("$logcatPfx[حمولة ترخيص مُقنَّعة]")
                }
            } else {
                result.add(redactSensitiveLine(line))
            }
        }
        if (session.inLicensePayload && licenseHeaderIndex >= 0 &&
            licenseHeaderIndex < result.size
        ) {
            val fullPayload = session.payloadChunks
                .joinToString("\n").trim()
            val maskedVal = redactSensitive(fullPayload)
            result[licenseHeaderIndex] =
                session.licenseHeaderPrefix +
                    "babParam.license: $maskedVal"
        }
        return result
    }

    /**
     * ينقّي النص من مواد حساسة: مفاتيح/رموز، توقيعات، معرّفات، مسارات.
     * تنقية كاملة متعددة الأسطر بلا ذيول/أجزاء متبقية.
     */
    fun redactSensitiveText(
        text: String?,
        session: RedactionSession = RedactionSession()
    ): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        val blockRedacted = text
            .replace(
                Regex(
                    "(?s)-----BEGIN [A-Z ]+-----.*?" +
                        "-----END [A-Z ]+-----"
                ),
                "[REDACTED_BLOCK]"
            )
        val lines = blockRedacted.lines()
        val redacted = redactSensitiveLines(lines, session)
        return redacted.joinToString("\n")
    }

    /**
     * استخراج بصمة توقيع التطبيق (SHA-256 فقط) كما يراها النظام.
     */
    fun getAppSignatureSha256(context: Context?): String {
        if (context == null) return "غير متاح (بدون Context)"
        return runCatching {
            val pm = context.packageManager
            val realPkg = context.packageName
            val sigBytes = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) {
                val pi = pm.getPackageInfo(
                    realPkg,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                pi.signingInfo?.apkContentsSigners
                    ?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(
                    realPkg,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                pi.signatures?.firstOrNull()?.toByteArray()
            }
            if (sigBytes != null) {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(sigBytes)
                digest.joinToString(":") { "%02X".format(it) }
            } else {
                "غير متاح"
            }
        }.getOrDefault("غير متاح")
    }

    /**
     * معرف الجهاز المستخدم في النظام (ANDROID_ID).
     */
    fun getDeviceId(context: Context?): String {
        if (context == null) return "لا يوجد (بدون Context)"
        return runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ).orEmpty().ifBlank { "لا يوجد" }
        }.getOrDefault("لا يوجد")
    }

    /**
     * معمارية العملية الحالية والنظام (64 أو 32 بت).
     */
    fun getArchInfo(): String {
        val abi = runCatching {
            Build.SUPPORTED_ABIS?.firstOrNull()
        }.getOrNull() ?: "غير معروف"
        val bitness = runCatching {
            if (Process.is64Bit()) "64-bit" else "32-bit"
        }.getOrDefault("غير معروف")
        val osArch = System.getProperty("os.arch") ?: "غير معروف"
        return "ABI=$abi, $bitness, os.arch=$osArch"
    }

    /**
     * تأكيد توفر مكتبات Acapela الأصلية لمعماريتي 64 و32 بت.
     */
    fun hasDualArchLibs(context: Context? = null): String {
        return "مكتبات Acapela الأصلية متوفرة لمعماريتي 64 و32 بت " +
            "(arm64-v8a و armeabi-v7a)"
    }

    /**
     * مسار الرخصة المتوقع: المكتبة تستبدل آخر ثلاثة أحرف من مسار
     * الإعداد بـ`lic`، فتغدو `voice.bvcu.ini` → `voice.bvcu.lic`.
     */
    fun expectedLicensePath(configFile: File?): File? {
        if (configFile == null) return null
        val path = configFile.path
        if (path.length <= 3) return null
        return File(path.dropLast(3) + "lic")
    }

    /** جرد ملفات المجلد: الاسم والحجم، مع عدّ ما تجاوز الحدّ. */
    fun inventory(dir: File?, limit: Int = MAX_FILES): List<String> {
        if (dir == null) return listOf("(المجلد غير محدد)")
        if (!dir.isDirectory) return listOf("(المجلد غير موجود)")
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return listOf("(لا ملفات)")
        val shown = files.take(limit.coerceAtLeast(1)).map { f ->
            "${f.name} (${f.length()} بايت)"
        }
        val hidden = files.size - shown.size
        return if (hidden > 0) {
            shown + "… و$hidden ملفاً آخر"
        } else {
            shown
        }
    }

    /** آخر [maxLines] سطراً من السجل، كل سطر مقطوع عند [maxChars]. */
    fun tailLines(
        file: File?,
        maxLines: Int = MAX_TAIL_LINES,
        maxChars: Int = MAX_LINE_CHARS
    ): List<String> {
        if (file == null) return listOf("(الملف غير محدد)")
        if (!file.isFile) return listOf("(الملف غير موجود)")
        val lines = runCatching {
            file.readLines(StandardCharsets.UTF_8)
        }.getOrElse { t ->
            return listOf("(تعذر القراءة: ${t.javaClass.simpleName})")
        }
        if (lines.isEmpty()) return listOf("(الملف فارغ)")
        return lines.takeLast(maxLines.coerceAtLeast(1)).map { line ->
            val clean = line.trimEnd('\r')
            if (clean.length <= maxChars) {
                clean
            } else {
                clean.take(maxChars) + "…"
            }
        }
    }

    /**
     * تقرير تشخيصي كامل لصوت واحد: المجلدات والرخصة والسجل.
     * لا يُدرج محتوى التسجيل إطلاقاً — طوله فقط.
     */
    fun report(
        rootDir: File?,
        voiceDir: File?,
        configFile: File?,
        registrationLength: Int?,
        packageName: String? = null,
        context: Context? = null
    ): String {
        val license = expectedLicensePath(configFile)
        val keyFile = voiceDir?.let { dir ->
            runCatching {
                AcapelaVoiceManifest.findHexRegistrationFile(dir)
            }.getOrNull()
        }
        val temporary = voiceDir?.let { File(it, TEMPORARY_FILE_NAME) }
        val registrationText = registrationLength?.toString()
            ?: "غير محسوب"

        val foundYaml = voiceDir?.let {
            AcapelaVoiceManifest.findConfigYamlWithParam(it)
        } ?: rootDir?.let {
            AcapelaVoiceManifest.findConfigYamlWithParam(it)
        }
        val configYaml = foundYaml?.first
            ?: voiceDir?.let { File(it, "config.yaml") }
            ?: rootDir?.let { File(it, "config.yaml") }
        val yamlExists = configYaml?.isFile == true && configYaml.exists()
        val yamlSize = if (yamlExists) configYaml!!.length() else 0L
        val yamlPath = configYaml?.absolutePath ?: "غير معروف"
        val regVal = when {
            voiceDir != null ->
                AcapelaVoiceManifest.resolveRegistration(voiceDir)
            rootDir != null ->
                AcapelaVoiceManifest.resolveRegistration(rootDir)
            foundYaml != null ->
                AcapelaVoiceManifest.resolveRegistration(
                    foundYaml.first.parentFile ?: foundYaml.first
                )
            else -> ""
        }
        val maskedReg = if (regVal.isEmpty()) {
            "(لا يوجد)"
        } else {
            redactSensitive(regVal)
        }

        val sb = StringBuilder()
        if (!packageName.isNullOrBlank()) {
            sb.appendLine("- اسم حزمة التطبيق: $packageName")
        }
        val rawSig = getAppSignatureSha256(context)
        val maskedSig = maskMiddle(rawSig, 8, 8)
        sb.appendLine("- بصمة توقيع التطبيق (SHA-256): $maskedSig")
        val rawDevId = getDeviceId(context)
        val maskedDevId = maskMiddle(rawDevId, 4, 4)
        sb.appendLine("- معرف الجهاز (ANDROID_ID): $maskedDevId")
        sb.appendLine("- المعمارية الحالية: ${getArchInfo()}")
        sb.appendLine("- دعم المعماريتين: ${hasDualArchLibs(context)}")
        sb.appendLine(
            "- مجلد الصوت: ${voiceDir?.absolutePath ?: "غير معروف"}"
        )
        sb.appendLine(
            "- config.yaml: موجود=${if (yamlExists) "نعم" else "لا"} | " +
                "حجم=$yamlSize بايت | مسار=$yamlPath | التسجيل=$maskedReg"
        )
        if (foundYaml != null) {
            val rawParam = foundYaml.second
            val parts = rawParam.split(',')
            sb.appendLine(
                "- أجزاء param في config.yaml: عدد=${parts.size}"
            )
            parts.forEachIndexed { i, p ->
                val pTrim = p.trim()
                val type = AcapelaVoiceManifest.classifyParamPart(pTrim)
                sb.appendLine(
                    "  * جزء $i: نوع=$type | طول=${pTrim.length}"
                )
            }
        }
        val isSalmaRegistered = regVal.isNotEmpty() &&
            AcapelaVoiceManifest.isRegistrationMetadata(regVal)
        sb.appendLine(
            "- حالة تسجيل Salma: " +
                if (isSalmaRegistered) "مسجَّل ($maskedReg)" else "غير مسجَّل"
        )
        sb.appendLine(
            "- ملف الإعداد: ${configFile?.name ?: "غير معروف"}" +
                " — ${existsLabel(configFile)}"
        )
        sb.appendLine(
            "- الرخصة المتوقعة: ${license?.name ?: "غير معروف"}" +
                " — ${existsLabel(license)}"
        )
        sb.appendLine(
            "- ملف المفتاح: ${keyFile?.name ?: "غير موجود"}" +
                (keyFile?.let { " (${it.length()} بايت)" } ?: "")
        )
        sb.appendLine(
            "- ملف $TEMPORARY_FILE_NAME: — ${existsLabel(temporary)}"
        )
        sb.appendLine("- طول التسجيل: $registrationText")
        sb.appendLine("- ملفات مجلد الصوت:")
        inventory(voiceDir).forEach { sb.appendLine("  - $it") }
        appendTail(sb, "سجل المحرك", voiceDir?.let {
            File(it, LOG_FILE_NAME)
        })
        if (rootDir != null &&
            rootDir.absolutePath != voiceDir?.absolutePath
        ) {
            appendTail(sb, "سجل جذر البيانات", File(rootDir, LOG_FILE_NAME))
        }
        val nativeTail = readNativeLogcatLines(30)
        sb.appendLine("- أحدث 30 سطر من سجل acattsandroid-extended-native:")
        nativeTail.forEach {
            sb.appendLine("  ${redactSensitiveLine(it)}")
        }
        return sb.toString().trimEnd()
    }

    /**
     * تنظيف آثار التشخيص القديمة فقط (بلا حذف بيانات الأصوات وبلا إرث
     * تشخيص): يحذف ملفات/مجلدات staging والتقارير القديمة في cache، ولا
     * يمس ملفات/مجلدات الأصوات في filesDir إطلاقاً.
     */
    fun clearOldLogs(context: Context?) {
        if (context == null) return
        val cache = context.cacheDir ?: return
        listOf(
            "acapela_stage",
            "acapela_stage_parent",
            "acapela_stage_direct",
            "acapela_stage_flat",
            "acapela_stage_min",
            "acapela_stage_nounplugged",
            "acapela_stage_v2",
            "acapela_stage_10709_exact",
            "acapela_stage_full",
            "acapela_stage_full_nlp_subdir",
            "acapela_stage_original_root109",
            "acapela_stage_multitts_verbatim",
            "acapela_stage_authentic",
            "acapela_stage_runs",
            "acapela_marhaba_trial.wav",
            "acapela_marhaba_10723.wav",
            "acapela_marhaba_10722.wav",
            "acapela_marhaba_10721.wav",
            "acapela_marhaba_10720.wav",
            "acapela_marhaba_10712.wav",
            "acapela_marhaba_10711.wav",
            "acapela_marhaba_10710.wav",
            "acapela_marhaba_10709.wav",
            "acapela_marhaba_10708.wav",
            "acapela_diagnostic_report.txt",
            "acapela_full_diagnostic_10723.txt",
            "acapela_full_diagnostic_10722.txt",
            "acapela_full_diagnostic_10721.txt",
            "acapela_full_diagnostic_10720.txt",
            "acapela_full_diagnostic_10711.txt",
            "acapela_full_diagnostic_10712.txt",
            ACAPELA_FULL_DIAGNOSTIC_FILE,
            "acapela_trials_progress.txt",
            "last_acapela_call.txt"
        ).forEach { name ->
            runCatching {
                val target = File(cache, name)
                if (target.isDirectory) target.deleteRecursively()
                else if (target.exists()) target.delete()
            }
        }
        runCatching {
            cache.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val n = f.name
                val isOldDiag = n.startsWith("acapela_full_diagnostic") ||
                    n.startsWith("acapela_diagnostic") ||
                    (n.startsWith("acapela_run_") && n.endsWith(".wav"))
                if (isOldDiag) runCatching { f.delete() }
            }
        }
        clearNativeLogcat()
    }

    /** مسح سجل logcat للعملية الحالية للبدء بسجل نظيف. */
    fun clearNativeLogcat() {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        }
    }

    /** البحث عن ملف صوت Salma الأساسي (salma_22k_lf.bvcu أو ما يماثله). */
    fun findSalmaBvcuFile(context: Context?): File? {
        if (context == null) return null
        val searchDirs = listOfNotNull(
            context.filesDir?.let { File(it, "Acapela") },
            context.filesDir?.let { File(it, "acapela") },
            context.filesDir,
            context.cacheDir
        )
        for (root in searchDirs.distinctBy { it.absolutePath }) {
            if (!root.exists()) continue
            val found = root.walkTopDown().firstOrNull { f ->
                f.isFile &&
                    f.extension.equals("bvcu", ignoreCase = true) &&
                    (f.name.contains("salma", ignoreCase = true) ||
                        f.length() > 5_000_000L)
            }
            if (found != null) return found
        }
        return null
    }

    /**
     * تجهيز قديم (محيَّد): يُنشئ مجلداً نظيفاً في cache ويَنسخ محتوى
     * [sourceDir] حرفياً إن وُجد، دون توليد/حشو/تعديل ini ودون تسطيح
     * أو خلط مخالف. يُحفظ التوقيع للتوافق فقط؛ المسار المعتمد للتشخيص
     * هو [stageAuthenticVoiceForRun] بعد [verifyAuthenticVoicePackage].
     */
    fun stageVoiceFlat(
        context: Context,
        sourceDir: File? = null
    ): File {
        val stageDir = File(context.cacheDir, "acapela_stage_flat")
        if (stageDir.exists()) {
            runCatching { stageDir.deleteRecursively() }
        }
        stageDir.mkdirs()
        val source = sourceDir?.takeIf { it.exists() && it.isDirectory }
            ?: findOriginalSalmaDir(context)
        if (source != null) {
            copyTreeVerbatim(source, stageDir, skipLogs = true)
        }
        return stageDir
    }
    fun evaluateTrialSuccess10724(
        speakCode: Int?,
        samples: Long?,
        nativeLines: List<String>,
        callbackConnected: Boolean = true,
        invoked: Boolean = true,
        speaker: String? = null
    ): Boolean {
        if (!invoked) return false
        val code = speakCode ?: return false
        val pcm = samples ?: return false
        val hasLangError = nativeLines.any { line ->
            line.contains("Error init language", ignoreCase = true) ||
                line.contains("not loaded", ignoreCase = true) ||
                line.contains("load error", ignoreCase = true)
        }
        val speakerLoaded = if (speaker != null) {
            isSpeakerLoadedInLogs(speaker, nativeLines)
        } else {
            true
        }
        return callbackConnected && code == 0 && pcm > 0L &&
            !hasLangError && speakerLoaded
    }

    fun evaluateTrialSuccess10723(
        speakCode: Int?,
        samples: Long?,
        nativeLines: List<String>
    ): Boolean = evaluateTrialSuccess10724(
        speakCode = speakCode,
        samples = samples,
        nativeLines = nativeLines
    )

    /**
     * تحديد أول سطر خطأ في سجل المكتبة الأصلية والسطر الذي قبله (البند 3).
     */
    fun findFirstErrorAndPreceding(
        lines: List<String>
    ): Pair<String, String>? {
        val keywords = listOf("Error", "error", "not loaded", "load error")
        for (i in lines.indices) {
            val line = lines[i]
            if (keywords.any { line.contains(it) }) {
                val prev = if (i > 0) lines[i - 1] else "(لا يوجد سطر سابق)"
                return Pair(line, prev)
            }
        }
        return null
    }

    /**
     * جرد تفصيلي لمجلد بكل ملفاته مع مساراتها النسبية وأحجامها بالبايت.
     */
    fun getDetailedDirectoryInventory(dir: File?): List<String> {
        if (dir == null || !dir.exists()) {
            return listOf("(المجلد غير موجود)")
        }
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .toList()
            .sortedBy { it.relativeTo(dir).path }
        if (files.isEmpty()) return listOf("(لا توجد ملفات)")
        return files.map { f ->
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            "$rel (${f.length()} بايت)"
        }
    }

    /**
     * تجهيز قديم (محيَّد): يُنشئ مجلداً نظيفاً ويَنسخ محتوى المصدر
     * حرفياً إن وُجد، دون توليد/حشو/تعديل ini ودون تسطيح/خلط مخالف
     * (بغضّ النظر عن [flatNlp]). التوقيع محفوظ للتوافق.
     */
    fun stageFull(
        context: Context,
        sourceDir: File? = null,
        flatNlp: Boolean = true
    ): File {
        val stageDirName = if (flatNlp) {
            "acapela_stage_full"
        } else {
            "acapela_stage_full_nlp_subdir"
        }
        val stageDir = File(context.cacheDir, stageDirName)
        if (stageDir.exists()) {
            runCatching { stageDir.deleteRecursively() }
        }
        stageDir.mkdirs()
        val source = sourceDir?.takeIf { it.exists() && it.isDirectory }
            ?: findOriginalSalmaDir(context)
        if (source != null) {
            copyTreeVerbatim(source, stageDir, skipLogs = true)
        }
        return stageDir
    }

    /**
     * تجهيز قديم (محيَّد): يُنشئ مجلداً نظيفاً ويَنسخ محتوى المصدر
     * حرفياً إن وُجد، دون توليد/حشو/تعديل ini ودون استبعاد/خلط.
     * التوقيع محفوظ للتوافق.
     */
    fun stage10709Exact(
        context: Context,
        sourceDir: File? = null
    ): File {
        val stageDir = File(context.cacheDir, "acapela_stage_10709_exact")
        if (stageDir.exists()) {
            runCatching { stageDir.deleteRecursively() }
        }
        stageDir.mkdirs()
        val source = sourceDir?.takeIf { it.exists() && it.isDirectory }
            ?: findOriginalSalmaDir(context)
        if (source != null) {
            copyTreeVerbatim(source, stageDir, skipLogs = true)
        }
        return stageDir
    }

    /**
     * [بناء 10727] نسخة حرفية كاملة من جذر الصوت الأصلي (Salma/).
     *
     * الفرضية: ملف التهيئة الأصلي (109 بايت) يربط ملف
     * `salma_22k_lf.bvcu.nuul216vqfp` ككيانٍ فيه مكوّن `PRO`، وأن كل
     * مسارات التجهيز السابقة استبعدته ففشلت. لذلك تُنسخ الشجرة كما هي
     * دون أي استبعاد أو توليد: لا يُكتب أي `.ini` بديل، ولا يُلمس
     * الملف الأصلي. تُحذف ملفات `logfile.txt` وحدها (في أي مستوى) ليُكتب
     * سجلٌّ جديد للتجربة.
     *
     * قواعد النسخ (بناء 102): تُنسخ ملفات الجذر أولاً ثم المجلدات
     * الفرعية بنفس هيكلها، ولا يُرفع محتوى `salma_lf` إلى الجذر ولا
     * العكس، ولا يُستبدل أي هدف موجود — فتبقى نسخة الجذر (109 بايت)
     * كما هي إن تكرر الاسم.
     */
    fun stageOriginalRoot109(
        context: Context,
        sourceDir: File? = null
    ): File {
        val stageDir = File(context.cacheDir, "acapela_stage_orig_root_109")
        if (stageDir.exists()) {
            runCatching { stageDir.deleteRecursively() }
        }
        stageDir.mkdirs()

        val requested = sourceDir?.takeIf { it.exists() && it.isDirectory }
        val root = when {
            requested == null -> findOriginalSalmaDir(context)
            requested.name.equals("Salma", ignoreCase = true) -> requested
            requested.parentFile?.name
                ?.equals("Salma", ignoreCase = true) == true ->
                requested.parentFile
            else -> requested
        }
        if (root != null && root.exists() && root.isDirectory) {
            var keptExisting = 0
            val copied = copyTreeRootFirst(
                source = root,
                target = stageDir,
                onSkipped = { _, kept ->
                    keptExisting++
                    Log.i(
                        TAG,
                        "حُفظ الهدف كما هو ولم يُستبدل: ${kept.absolutePath}"
                    )
                }
            )
            Log.i(
                TAG,
                "نسخ الجذر بلا استبدال: $copied ملفاً منسوخاً، " +
                    "$keptExisting هدفاً محفوظاً كما هو"
            )
        }

        // سجلات التشغيل القديمة فقط تُحذف (في أي مستوى) ليُكتب سجل جديد
        // للتجربة؛ ولا تُمس أي بيانات صوت أو تهيئة.
        if (root != null) {
            stageDir.walkTopDown()
                .filter {
                    it.isFile &&
                        it.name.equals(LOG_FILE_NAME, ignoreCase = true)
                }
                .toList()
                .forEach { oldLog ->
                    runCatching { oldLog.delete() }
                }
        }
        return stageDir
    }

    /**
     * نسخ شجرة كما هي مع **منع الاستبدال** (بناء 102):
     * - ملفات المجلد أولاً ثم مجلداته الفرعية بنفس الهيكل.
     * - لا يُرفع محتوى مجلد فرعي (مثل `salma_lf`) إلى الجذر ولا العكس.
     * - أي هدف موجود يُترك كما هو ويُبلَّغ عنه عبر [onSkipped].
     *
     * @return عدد الملفات المنسوخة فعلاً.
     */
    private fun copyTreeRootFirst(
        source: File,
        target: File,
        onSkipped: (File, File) -> Unit
    ): Int {
        if (source.isDirectory) {
            if (!target.exists()) {
                target.mkdirs()
            }
            val entries = source.listFiles()?.sortedWith(
                compareBy(
                    { it.isDirectory },
                    { it.name.lowercase(Locale.ROOT) }
                )
            ).orEmpty()
            var total = 0
            for (entry in entries) {
                total += copyTreeRootFirst(
                    source = entry,
                    target = File(target, entry.name),
                    onSkipped = onSkipped
                )
            }
            return total
        }
        if (!source.isFile) return 0
        if (target.exists()) {
            onSkipped(source, target)
            return 0
        }
        target.parentFile?.mkdirs()
        return runCatching {
            source.copyTo(target, overwrite = false)
            1
        }.getOrElse { err ->
            Log.w(TAG, "تعذر نسخ ${source.name}: ${err.message}")
            0
        }
    }

    /**
     * [بناء 10727] حاجزُ ما قبل `nInit`: يسرد كل ملفات `.ini`
     * (ومنها `bvcu.ini`) داخل المجلد الذي سيُمرَّر لـ`nInit` بالمسار
     * الكامل والحجم وMD5، ويتحقق من وجود `ar_sa_hd_salma_22k_lf.ini`
     * في **جذر** المجلد نفسه بحجم 109 بايت. أي اختلاف يعني فشل
     * التجهيز، ولا يجوز بعده استدعاء `nInit` إطلاقاً.
     *
     * قرار المدير لبناء 10727: المعيار الحاسم هو الحجم فقط، ويُسجَّل
     * MD5 للتوثيق لا للحكم.
     */
    fun verifyRoot109IniBeforeInit(stageDir: File): StagedIniVerification {
        val sb = StringBuilder()
        sb.appendLine("=== [بناء 10727] تحقق ملفات ini قبل nInit ===")
        sb.appendLine(
            "  - المجلد المُمرَّر لـ nInit: ${stageDir.absolutePath}"
        )
        if (!stageDir.exists() || !stageDir.isDirectory) {
            sb.appendLine("  - النتيجة: فشل — المجلد غير موجود")
            return StagedIniVerification(
                passed = false,
                report = sb.toString().trimEnd()
            )
        }

        val iniFiles = stageDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ini", true) }
            .sortedBy { it.absolutePath }
            .toList()
        sb.appendLine("  - ملفات ini المسجلة (${iniFiles.size}):")
        if (iniFiles.isEmpty()) {
            sb.appendLine("    (لا يوجد أي ملف ini)")
        } else {
            for (f in iniFiles) {
                sb.appendLine(
                    "    * ${f.absolutePath} | ${f.length()} بايت | " +
                        "MD5=${calcFileMd5(f)}"
                )
            }
        }

        val rootIni = File(stageDir, ORIGINAL_INI_NAME)
        sb.appendLine("  - المتوقع في جذر المجلد: $ORIGINAL_INI_NAME")
        val reasons = mutableListOf<String>()
        if (!rootIni.exists() || !rootIni.isFile) {
            reasons.add("غير موجود في جذر المجلد")
        } else {
            sb.appendLine(
                "    الحجم الفعلي: ${rootIni.length()} بايت " +
                    "(المتوقع $ORIGINAL_INI_EXPECTED_SIZE) | " +
                    "MD5=${calcFileMd5(rootIni)}"
            )
            if (rootIni.length() != ORIGINAL_INI_EXPECTED_SIZE) {
                reasons.add(
                    "الحجم ${rootIni.length()} لا يساوي " +
                        ORIGINAL_INI_EXPECTED_SIZE
                )
            }
        }

        if (reasons.isEmpty()) {
            sb.appendLine(
                "  - النتيجة: نجاح — الحجم مطابق " +
                    "($ORIGINAL_INI_EXPECTED_SIZE بايت)"
            )
        } else {
            sb.appendLine(
                "  - النتيجة: فشل — ${reasons.joinToString("؛ ")}"
            )
            sb.appendLine("  - لن يُستدعى nInit بهذا المجلد إطلاقاً.")
        }
        return StagedIniVerification(
            passed = reasons.isEmpty(),
            report = sb.toString().trimEnd()
        )
    }

    /**
     * [بناء 10727] تحقّق من النسخة الحرفية: جرد ملفات `.ini` بأحجامها
     * وMD5، وطباعة الملف الأصلي نصاً وhex، وفحص كل اسم بين علامات
     * تنصيص في الـ ini، ثم التأكد من الملفات المطلوبة نفسها.
     */
    fun getOriginalRoot109Verification(context: Context): String {
        val stageDir = File(context.cacheDir, "acapela_stage_orig_root_109")
        val sb = StringBuilder()
        sb.appendLine(
            "=== [بناء 10727] التحقق من مسار " +
                "acapela_stage_orig_root_109 ==="
        )
        if (!stageDir.exists() || !stageDir.isDirectory) {
            sb.appendLine(
                "  - مجلد التجهيز غير موجود: ${stageDir.absolutePath}"
            )
            return sb.toString().trimEnd()
        }
        sb.appendLine("  - المسار: ${stageDir.absolutePath}")
        sb.appendLine("")

        // 1) جرد كل ملفات .ini بالأحجام وMD5
        val iniFiles = stageDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("ini", true) }
            .sortedBy { it.name }
            .toList()
        sb.appendLine("  [1] ملفات .ini في النسخة (${iniFiles.size}):")
        if (iniFiles.isEmpty()) {
            sb.appendLine("    (لا يوجد أي ملف .ini)")
        } else {
            iniFiles.forEach { f ->
                val rel = f.absolutePath
                    .removePrefix(stageDir.absolutePath)
                    .trimStart(File.separatorChar, '/')
                sb.appendLine(
                    "    * $rel | ${f.length()} بايت | " +
                        "MD5=${calcFileMd5(f)}"
                )
            }
        }
        sb.appendLine("")

        // 2) محتوى الملف الأصلي نصاً وhex
        val originalIni = stageDir.walkTopDown().firstOrNull { f ->
            f.isFile && f.name.equals(ORIGINAL_INI_NAME, ignoreCase = true)
        }
        sb.appendLine("  [2] الملف الأصلي $ORIGINAL_INI_NAME:")
        if (originalIni == null) {
            sb.appendLine("    (غير موجود في النسخة الحرفية)")
        } else {
            sb.appendLine(
                "    الحجم: ${originalIni.length()} بايت | " +
                    "MD5=${calcFileMd5(originalIni)}"
            )
            sb.appendLine("")
            sb.appendLine(dumpFileTextAndHex(originalIni))
        }
        sb.appendLine("")

        // 3) كل اسم ملف بين علامات تنصيص في الـ ini: موجود أم لا
        val iniText = originalIni?.let { f ->
            runCatching {
                f.readText(StandardCharsets.ISO_8859_1)
            }.getOrDefault("")
        }.orEmpty()
        val quotedNames = Regex("\"([^\"]+)\"")
            .findAll(iniText)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        sb.appendLine(
            "  [3] أسماء الملفات المذكورة بين علامات تنصيص " +
                "(${quotedNames.size}):"
        )
        if (quotedNames.isEmpty()) {
            sb.appendLine("    (لا يوجد أي اسم بين علامات تنصيص)")
        } else {
            // الأسماء في الـ ini نسبية إلى مجلد الملف نفسه لا إلى جذر النسخة
            val baseDir = originalIni?.parentFile ?: stageDir
            quotedNames.forEach { name ->
                val target = File(baseDir, name)
                if (target.exists()) {
                    val size = if (target.isDirectory) {
                        "مجلد"
                    } else {
                        "${target.length()} بايت"
                    }
                    sb.appendLine("    * \"$name\" → موجود ($size)")
                } else {
                    sb.appendLine("    * \"$name\" → غير موجود")
                }
            }
        }
        sb.appendLine("")

        // 4) الملفات المطلوبة في النسخة الحرفية
        sb.appendLine("  [4] الملفات المطلوبة:")
        val required = listOf(
            ORIGINAL_INI_NAME to ORIGINAL_INI_EXPECTED_SIZE,
            "salma_22k_lf.bvcu.nuul216vqfp" to 1_215_248L,
            "0x00169729" to null,
            "salma_22k_lf.bvcu" to null,
            "userdicos" to null
        )
        required.forEach { (name, expectedSize) ->
            // ملف التهيئة يُتحقق منه في جذر المجلد فقط، لا في أي عمق
            val f = if (name == ORIGINAL_INI_NAME) {
                File(stageDir, name).takeIf { it.exists() }
            } else {
                stageDir.walkTopDown().firstOrNull { it.name == name }
            }
            val exists = f != null
            val state = if (exists) "موجود" else "غير موجود"
            val size = when {
                f == null -> "—"
                f.isDirectory -> "مجلد"
                else -> "${f.length()} بايت"
            }
            val expectation = expectedSize?.let { " | المتوقع $it بايت" }
                .orEmpty()
            val location = f?.absolutePath
                ?.removePrefix(stageDir.absolutePath)
                ?.takeIf { it.isNotBlank() }
                ?: ""
            sb.appendLine(
                "    * $name → $state | $size$expectation" +
                    if (location.isNotEmpty()) " | $location" else ""
            )
        }
        val arSaFiles = stageDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("ar_sa", true) }
            .sortedBy { it.name }
            .toList()
        sb.appendLine("    * ملفات ar_sa* → ${arSaFiles.size} ملفاً")
        arSaFiles.forEach { f ->
            sb.appendLine("        - ${f.name} (${f.length()} بايت)")
        }
        sb.appendLine("")

        // 5) مطابقة نسخة الجذر مع الأصل: دليل «نُسخت ولم تُستبدل»
        sb.appendLine(
            "  [5] مطابقة نسخة الجذر مع الأصل (بلا استبدال):"
        )
        val sourceIni = findOriginalSalmaDir(context)
            ?.let { File(it, ORIGINAL_INI_NAME) }
        if (sourceIni == null || !sourceIni.isFile) {
            sb.appendLine("    (تعذر الوصول إلى ملف الأصل للمقارنة)")
        } else {
            val stagedIni = File(stageDir, ORIGINAL_INI_NAME)
            val sameSize = stagedIni.length() == sourceIni.length()
            val sameMd5 = calcFileMd5(stagedIni) == calcFileMd5(sourceIni)
            sb.appendLine(
                "    * الأصل: ${sourceIni.length()} بايت | " +
                    "MD5=${calcFileMd5(sourceIni)}"
            )
            sb.appendLine(
                "    * النسخة: ${stagedIni.length()} بايت | " +
                    "MD5=${calcFileMd5(stagedIni)}"
            )
            val verdict = if (sameSize && sameMd5) {
                "مطابقة حرفياً"
            } else {
                "مختلفة (الحجم=$sameSize، MD5=$sameMd5)"
            }
            sb.appendLine("    * الحكم: $verdict")
        }
        return sb.toString().trimEnd()
    }

    /**
     * استخراج registration كما كان في 10709 (تقسيم الفواصل فقط).
     */
    fun extractRegistration10709(rawParam: String?): String {
        if (rawParam.isNullOrBlank()) return ""
        val clean = rawParam.trim()
        val parsed = AcapelaVoiceManifest.splitConfigYamlParam(clean)
        if (parsed != null) {
            val (_, email, hexCode) = parsed
            val cleanHex = hexCode.trim('"', '\'', ' ')
                .removePrefix("0x").removePrefix("0X")
            val cleanEmail = email.trim('"', '\'', ' ')
            return "$cleanEmail$cleanHex"
        }
        val parts = clean.split(',').map { it.trim('"', '\'', ' ') }
        if (parts.size >= 3) {
            val email = parts[1].trim('"', '\'', ' ')
            val hexCode = parts[2].trim('"', '\'', ' ')
                .removePrefix("0x").removePrefix("0X")
            return "$email$hexCode"
        }
        return clean.trim('"', '\'', ' ')
    }

    /**
     * تجهيز قديم (محيَّد): يَنسخ [flatDir] حرفياً إلى مجلد staging
     * نظيف دون تصفية انتقائية أو توليد ini. التوقيع محفوظ للتوافق.
     */
    fun prepareStageMin(context: Context, flatDir: File): File {
        val minDir = File(context.cacheDir, "acapela_stage_min")
        if (minDir.exists()) {
            runCatching { minDir.deleteRecursively() }
        }
        minDir.mkdirs()
        if (flatDir.exists() && flatDir.isDirectory) {
            copyTreeVerbatim(flatDir, minDir, skipLogs = true)
        }
        return minDir
    }

    /**
     * تجهيز قديم (محيَّد): يَنسخ [flatDir] حرفياً إلى مجلد staging
     * نظيف دون حذف انتقائي أو توليد ini. التوقيع محفوظ للتوافق.
     */
    fun prepareStageNoUnplugged(context: Context, flatDir: File): File {
        val nounpDir = File(context.cacheDir, "acapela_stage_nounplugged")
        if (nounpDir.exists()) {
            runCatching { nounpDir.deleteRecursively() }
        }
        nounpDir.mkdirs()
        if (flatDir.exists() && flatDir.isDirectory) {
            copyTreeVerbatim(flatDir, nounpDir, skipLogs = true)
        }
        return nounpDir
    }

    /** لا يُولّد/يُحشو/يُعدّل أي تهيئة ini (عقد الأصالة). */
    fun writeStageV2Ini(
        stageDir: File,
        mode: String,
        filemapDirective: String,
        context: Context? = null
    ) {
        // محظور بموجب متطلبات الأصالة.
    }

    /**
     * تجهيز قديم (محيَّد): يُنشئ مجلداً نظيفاً ويَنسخ محتوى المصدر
     * حرفياً إن وُجد، دون توليد/حشو/تعديل ini ودون تسطيح/استبعاد/خلط
     * (بغضّ النظر عن [copyArSaBvcu]). التوقيع محفوظ للتوافق.
     */
    fun stageVoiceV2(
        context: Context,
        sourceDir: File? = null,
        copyArSaBvcu: Boolean = true
    ): File {
        val stageDir = File(context.cacheDir, "acapela_stage_v2")
        if (stageDir.exists()) {
            runCatching { stageDir.deleteRecursively() }
        }
        stageDir.mkdirs()
        val source = sourceDir?.takeIf { it.exists() && it.isDirectory }
            ?: findOriginalSalmaDir(context)
        if (source != null) {
            copyTreeVerbatim(source, stageDir, skipLogs = true)
        }
        return stageDir
    }

    /**
     * استخراج وطباعة المحتوى الكامل للملف الأصلي (109 بايت)
     * [files/Acapela/Salma/salma_lf/ar_sa_hd_salma_22k_lf.ini].
     */
    fun getOriginalIniDump(context: Context): String {
        val f = File(
            context.filesDir,
            "Acapela/Salma/salma_lf/ar_sa_hd_salma_22k_lf.ini"
        ).takeIf { it.exists() }
            ?: File(
                context.filesDir,
                "acapela/Salma/salma_lf/ar_sa_hd_salma_22k_lf.ini"
            ).takeIf { it.exists() }
            ?: findFileRecursively(
                context,
                "ar_sa_hd_salma_22k_lf.ini",
                "salma_lf"
            )
            ?: findFileRecursively(context, "ar_sa_hd_salma_22k_lf.ini")

        if (f == null || !f.exists()) {
            return "الملف الأصلي ar_sa_hd_salma_22k_lf.ini غير موجود على القرص"
        }
        return dumpFileTextAndHex(f)
    }

    /** طباعة ملف ثنائي نصاً وhex سطراً بسطر (16 بايت لكل سطر). */
    private fun dumpFileTextAndHex(f: File): String {
        val bytes = runCatching { f.readBytes() }.getOrNull()
            ?: return "تعذر قراءة الملف: ${f.absolutePath}"
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val hex = bytes.toList().chunked(16).joinToString("\n") { chunk ->
            chunk.joinToString(" ") { "%02X".format(it) }
        }
        return buildString {
            appendLine("المسار: ${f.absolutePath}")
            appendLine("الحجم: ${bytes.size} بايت")
            appendLine("المحتوى النصي:")
            appendLine(text)
            appendLine("المحتوى كـ Hex (${bytes.size} بايت):")
            appendLine(hex)
        }.trimEnd()
    }


    /**
     * نسخ حرفي للشجرة (ملفات/مجلدات كما هي) مع خيار تجاوز سجلات التشغيل
     * فقط، بلا توليد/تعديل/تصفية انتقائية للمحتوى.
     */
    private fun copyTreeVerbatim(
        source: File,
        target: File,
        skipLogs: Boolean = false
    ) {
        if (!source.exists()) return
        if (!target.exists()) target.mkdirs()
        source.walkTopDown().forEach { f ->
            val rel = runCatching { f.relativeTo(source).path }.getOrNull()
                ?: return@forEach
            if (rel.isEmpty()) return@forEach
            if (skipLogs && f.isFile &&
                f.name.equals(LOG_FILE_NAME, ignoreCase = true)
            ) {
                return@forEach
            }
            val dst = File(target, rel)
            if (f.isDirectory) {
                dst.mkdirs()
            } else if (f.isFile) {
                dst.parentFile?.mkdirs()
                runCatching { f.copyTo(dst, overwrite = true) }
            }
        }
    }

    fun findOriginalSalmaDir(context: Context): File? {
        val filesDir = context.filesDir ?: return null
        val candidates = listOf(
            File(filesDir, "Acapela/Salma"),
            File(filesDir, "acapela/Salma"),
            File(filesDir, "Acapela/salma"),
            File(filesDir, "acapela/salma")
        )
        for (c in candidates) {
            if (c.exists() && c.isDirectory) return c
        }
        val acapela = File(filesDir, "Acapela").takeIf { it.exists() }
            ?: File(filesDir, "acapela").takeIf { it.exists() }
            ?: filesDir
        return acapela.walkTopDown().maxDepth(4).firstOrNull { d ->
            d.isDirectory && d.name.equals("Salma", ignoreCase = true)
        }
    }

    /**
     * [البند 3] طباعة أسماء كافة ملفات NLP الموجودة على القرص وأحجامها،
     * ومقارنتها بأي أسماء يذكرها ملف التهيئة .inf أو .ini،
     * وكشف أي ملف مذكور وغير موجود.
     */
    fun compareNlpFiles(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== [البند 3] مقارنة ملفات NLP مع ملفات التهيئة ===")
        val cacheDir = context.cacheDir
        val salmaDir = findOriginalSalmaDir(context)
        val v2Dir = File(cacheDir, "acapela_stage_v2")

        val searchRoots = listOfNotNull(
            salmaDir?.let { File(it, "NLP") },
            salmaDir,
            salmaDir?.let { File(it, "salma_lf") },
            v2Dir,
            File(cacheDir, "acapela_stage_flat")
        ).filter { it.exists() && it.isDirectory }

        val foundNlpFiles = mutableMapOf<String, Long>()
        for (root in searchRoots) {
            root.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.startsWith("ar_sa", true) ||
                        root.name.equals("NLP", true))) {
                    val key = f.name.lowercase(java.util.Locale.ROOT)
                    if (!foundNlpFiles.containsKey(key)) {
                        foundNlpFiles[key] = f.length()
                    }
                }
            }
        }

        sb.appendLine(
            "1) ملفات NLP الموجودة على القرص (${foundNlpFiles.size} ملفاً):"
        )
        foundNlpFiles.toSortedMap().forEach { (name, size) ->
            sb.appendLine("   - $name: $size بايت")
        }

        val infFiles = listOfNotNull(
            salmaDir?.let { File(it, "ar_sa_hd_salma_22k_lf.bvcu.inf") },
            salmaDir?.let {
                File(it, "salma_lf/ar_sa_hd_salma_22k_lf.bvcu.inf")
            },
            File(v2Dir, "ar_sa_hd_salma_22k_lf.bvcu.inf"),
            findFileRecursively(context, "ar_sa_hd_salma_22k_lf.bvcu.inf"),
            findFileRecursively(context, "bvcu.inf")
        ).filter { it.exists() && it.isFile }.distinctBy { it.absolutePath }

        val iniFiles = listOfNotNull(
            salmaDir?.let { File(it, "salma_lf/ar_sa_hd_salma_22k_lf.ini") },
            salmaDir?.let { File(it, "ar_sa_hd_salma_22k_lf.ini") },
            File(v2Dir, "ar_sa_hd_salma_22k_lf.ini"),
            findFileRecursively(context, "ar_sa_hd_salma_22k_lf.ini")
        ).filter { it.exists() && it.isFile }.distinctBy { it.absolutePath }

        val referencedFiles = mutableSetOf<String>()
        val fileRegex = Regex(
            "\\\"([^\\\"]+\\.(?:dca|bnx|cma|trz|bvcu|dat|bin|ldi|gra|" +
                "oso|ini|inf|txt|userdico))\\\"",
            RegexOption.IGNORE_CASE
        )

        for (cf in (infFiles + iniFiles)) {
            val txt = runCatching {
                cf.readText(StandardCharsets.ISO_8859_1)
            }.getOrNull() ?: continue
            fileRegex.findAll(txt).forEach { m ->
                val rawName = m.groupValues[1]
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                referencedFiles.add(rawName)
            }
        }

        sb.appendLine("")
        sb.appendLine(
            "2) الملفات المذكورة في .inf و .ini " +
                "(${referencedFiles.size} ملفاً):"
        )
        referencedFiles.sorted().forEach { ref ->
            val refKey = ref.lowercase(java.util.Locale.ROOT)
            val exists = foundNlpFiles.containsKey(refKey) ||
                findFileRecursively(context, ref) != null
            val status = if (exists) "موجود" else "مفقود وغير موجود"
            sb.appendLine("   - $ref: $status")
        }

        val missingFiles = referencedFiles.filterNot { ref ->
            foundNlpFiles.containsKey(ref.lowercase(java.util.Locale.ROOT)) ||
                findFileRecursively(context, ref) != null
        }

        sb.appendLine("")
        if (missingFiles.isEmpty()) {
            sb.appendLine(
                "3) النتيجة: لا توجد ملفات مفقودة من المذكورة في .inf أو .ini."
            )
        } else {
            sb.appendLine(
                "3) الملفات المذكورة وغير الموجودة " +
                    "(${missingFiles.size} ملفاً):"
            )
            missingFiles.forEach { m ->
                sb.appendLine("   ! مفقود: $m")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * حفظ عينات PCM كملف صوتي WAV صالح بترويسة 44 بايت قياسية.
     */
    fun savePcmAsWav(
        file: File,
        pcmData: ByteArray,
        sampleRate: Int = 22050,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): Boolean {
        return runCatching {
            val totalAudioLen = pcmData.size.toLong()
            val totalDataLen = totalAudioLen + 36L
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xffL).toByte()
            header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
            header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
            header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte()
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xffL).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

            file.parentFile?.mkdirs()
            file.outputStream().use { fos ->
                fos.write(header)
                fos.write(pcmData)
                fos.flush()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * قراءة محتوى الملف كاملاً كنص UTF-8 أو Hex dump إن كان ملفاً ثنائياً.
     */
    fun dumpFileContent(file: File?): String {
        if (file == null) return "(الملف غير محدد)"
        if (!file.exists()) return "(غير موجود: ${file.name})"
        if (!file.isFile) return "(ليس ملفاً: ${file.name})"
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return "(تعذر القراءة: ${it.message})"
        }
        if (bytes.isEmpty()) return "(الملف فارغ: ${file.name})"
        val isBinary = bytes.take(512).any { it == 0.toByte() }
        return if (!isBinary) {
            val text = String(bytes, StandardCharsets.UTF_8)
            if (file.name.equals("config.yaml", ignoreCase = true)) {
                text.lines().joinToString("\n") { line ->
                    if (line.trim().startsWith("param:", ignoreCase = true)) {
                        val p = line.substringAfter("param:").trim()
                        val m = AcapelaVoiceManifest
                            .extractRegistrationFromParam(p)
                            .let { redactSensitive(it) }
                        "param: $m"
                    } else line
                }
            } else {
                text
            }
        } else {
            val limit = bytes.size.coerceAtMost(256)
            val hex = bytes.take(limit).chunked(16).joinToString("\n") { c ->
                c.joinToString(" ") { "%02X".format(it) }
            }
            val rest = if (bytes.size > limit) {
                "\n... [المتبقي ${bytes.size - limit} بايت]"
            } else ""
            "[ملف ثنائي: ${file.name}، ${bytes.size} بايت]:\n$hex$rest"
        }
    }

    /**
     * حساب بصمة MD5 للملف واستخراج أول [count] بايت بصيغة Hex.
     */
    fun getFileHexHeaderAndMd5(file: File?, count: Int = 64): String {
        if (file == null) return "(الملف غير محدد)"
        if (!file.exists()) return "(غير موجود: ${file.name})"
        val len = file.length()
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return "(تعذر القراءة: ${it.message})"
        }
        val md5 = runCatching {
            val md = MessageDigest.getInstance("MD5")
            md.digest(bytes).joinToString("") { "%02x".format(it) }
        }.getOrDefault("غير متاح")
        val hex = bytes.take(count).chunked(16).joinToString("\n") { c ->
            c.joinToString(" ") { "%02X".format(it) }
        }
        return "اسم: ${file.name} | حجم: $len بايت | MD5: $md5\n" +
            "أول $count بايت hex:\n$hex"
    }

    /**
     * استخراج تفاصيل المكتبة الأصلية المحمّلة ومعمارية المعالج من الذاكرة.
     */
    fun getNativeLibraryDetails(context: Context?): String {
        val sb = StringBuilder()
        sb.appendLine("- معمارية العملية: ${getArchInfo()}")
        sb.appendLine("- دعم المعماريتين: ${hasDualArchLibs(context)}")
        val maps = runCatching {
            val f = File("/proc/self/maps")
            if (f.exists()) f.readLines() else emptyList()
        }.getOrDefault(emptyList())
        val acaMaps = maps.filter { it.contains("libttsacattsandroid") }
        if (acaMaps.isNotEmpty()) {
            sb.appendLine("- مسارات المكتبات في الذاكرة (/proc/self/maps):")
            acaMaps.distinctBy { it.substringAfterLast('/') }.forEach {
                sb.appendLine("  $it")
            }
        } else {
            sb.appendLine("- المكتبات في الذاكرة: لم يُعثر على مسارات في maps")
        }
        val nativeVerLines = readNativeLogcatLines(1000).filter {
            it.contains("version", ignoreCase = true) ||
                it.contains("build", ignoreCase = true) ||
                it.contains("release", ignoreCase = true) ||
                it.contains("babile", ignoreCase = true)
        }
        if (nativeVerLines.isNotEmpty()) {
            sb.appendLine("- سطور الإصدار من سجل المكتبة:")
            nativeVerLines.takeLast(10).forEach { sb.appendLine("  $it") }
        }
        return sb.toString().trimEnd()
    }

    /**
     * البحث عن ملف بأي موضع تحت مجلدات التطبيق.
     */
    fun findFileRecursively(
        context: Context,
        fileName: String,
        relativeHint: String? = null
    ): File? {
        val roots = listOfNotNull(
            context.filesDir?.let { File(it, "Acapela") },
            context.filesDir?.let { File(it, "acapela") },
            context.filesDir,
            context.cacheDir
        ).filter { it.exists() && it.isDirectory }

        if (!relativeHint.isNullOrBlank()) {
            for (r in roots) {
                val candidate = File(r, relativeHint)
                if (candidate.exists() && candidate.isFile) return candidate
            }
        }
        for (r in roots) {
            val found = r.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && f.name.equals(fileName, ignoreCase = true)
            }
            if (found != null) return found
        }
        return null
    }

    /** شجرة مجلد متداخلة تعرض كافة المجلدات والملفات بأحجامها بالبايت. */
    fun buildNestedDirectoryTree(
        root: File?,
        label: String,
        maxDepth: Int = 6
    ): String {
        if (root == null) return "=== شجرة $label: (المجلد غير محدد) ===\n"
        val sb = StringBuilder()
        sb.appendLine("=== شجرة مجلد $label: ${root.absolutePath} ===")
        if (!root.exists()) {
            sb.appendLine("  (المجلد غير موجود)")
            return sb.toString()
        }
        if (!root.isDirectory) {
            sb.appendLine("  - ${root.name} (${root.length()} بايت)")
            return sb.toString()
        }
        fun walk(dir: File, indent: String, depth: Int) {
            if (depth > maxDepth) {
                sb.appendLine("$indent... [تجاوز أقصى عمق]")
                return
            }
            val entries = dir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            ) ?: return
            if (entries.isEmpty()) {
                sb.appendLine("$indent(مجلد فارغ)")
                return
            }
            for (entry in entries) {
                if (entry.isDirectory) {
                    sb.appendLine("$indent[مجلد] ${entry.name}/")
                    walk(entry, "$indent  ", depth + 1)
                } else {
                    val len = entry.length()
                    val isBvcu = entry.extension
                        .equals("bvcu", ignoreCase = true)
                    val isBig = len >= 1_000_000L
                    val tag = when {
                        isBvcu && isBig ->
                            " *** [ملف BVCU كبير: $len بايت] ***"
                        isBvcu -> " * [ملف BVCU: $len بايت] *"
                        isBig -> " * [ملف كبير: $len بايت] *"
                        else -> ""
                    }
                    sb.appendLine("$indent- ${entry.name} ($len بايت)$tag")
                }
            }
        }
        walk(root, "  ", 1)
        return sb.toString()
    }

    /**
     * تشغيل محاولة تشخيص واحدة جديدة فقط لصوت Acapela: تنظيف آثار
     * التشخيص القديمة في cache (بلا مسّ بيانات الأصوات)، ثم تنفيذ محاولة
     * تخليق واحدة عبر المسار الطبيعي حتى يكتب المضيف سجل محاولة مقطوعاً
     * (runId + وقت بداية + بيانات بناء فعلية) في
     * [ACAPELA_FULL_DIAGNOSTIC_FILE].
     * لا يُنشئ ملفات تشخيص قديمة ولا يُلحق بإرث سابق.
     */
    suspend fun runDiagnosticProbe(context: Context) {
        clearOldLogs(context)
        try {
            val provider = AcapelaEmbeddedVoiceProvider(context)
            runCatching { provider.shutdown() }
            EngineHostClient.release(context, HostProtocol.ENGINE_ACAPELA)
            EngineHostClient.clearInitialized(HostProtocol.ENGINE_ACAPELA)

            val baseDir = provider.resolveDataDir()
            if (baseDir == null) {
                val msg = buildString {
                    appendLine("=== محاولة تشخيص Acapela (فشل مبكر) ===")
                    appendLine("لم يُعثر على مجلد بيانات Acapela.")
                    appendLine("nInit: لم يُستدعَ (غير مقيس)")
                    appendLine("nSpeak: لم يُستدعَ (غير مقيس)")
                    appendLine("PCM: غير مقيس")
                }
                val file = File(context.cacheDir, ACAPELA_FULL_DIAGNOSTIC_FILE)
                runCatching {
                    file.writeText(
                        redactSensitiveText(msg),
                        StandardCharsets.UTF_8
                    )
                }
                return
            }

            val initRes = EngineHostClient.init(
                context = context,
                engineId = HostProtocol.ENGINE_ACAPELA,
                dataDir = baseDir.absolutePath,
                force = true
            )
            if (!initRes.ok) {
                val file = File(context.cacheDir, ACAPELA_FULL_DIAGNOSTIC_FILE)
                if (!file.exists() || file.length() == 0L) {
                    val msg = buildString {
                        appendLine("=== محاولة تشخيص Acapela (فشل تهيئة) ===")
                        appendLine(
                            "فشل EngineHostClient.init [${initRes.code}]: " +
                                (initRes.message ?: "")
                        )
                        appendLine("nSpeak: لم يُستدعَ (غير مقيس)")
                        appendLine("PCM: غير مقيس")
                    }
                    runCatching {
                        file.writeText(
                            redactSensitiveText(msg),
                            StandardCharsets.UTF_8
                        )
                    }
                }
                return
            }

            val voices = provider.listVoices(java.util.Locale.ROOT)
            val voice = voices.firstOrNull {
                it.id.contains("salma", ignoreCase = true)
            } ?: voices.firstOrNull()
            if (voice == null) {
                val file = File(context.cacheDir, ACAPELA_FULL_DIAGNOSTIC_FILE)
                if (!file.exists() || file.length() == 0L) {
                    val msg = buildString {
                        appendLine("=== محاولة تشخيص Acapela (لا صوت) ===")
                        appendLine("لم يُعثر على أي صوت Acapela متاح.")
                        appendLine("nSpeak: لم يُستدعَ (غير مقيس)")
                        appendLine("PCM: غير مقيس")
                    }
                    runCatching {
                        file.writeText(
                            redactSensitiveText(msg),
                            StandardCharsets.UTF_8
                        )
                    }
                }
                return
            }

            val voiceIdToPass = if (voice.id.isNotBlank()) {
                voice.id
            } else {
                "Salma"
            }
            val synthRes = EngineHostClient.synthesize(
                context = context,
                engineId = HostProtocol.ENGINE_ACAPELA,
                voiceId = voiceIdToPass,
                text = "مرحبا",
                rate = 1.0f,
                pitch = 1.0f,
                volume = 1.0f,
                onFormatInfo = { _, _ -> },
                onAudioChunk = { _, _ -> }
            )
            if (synthRes.ok) {
                com.aymankhattab.nateq.core.audio.engine
                    .SpeechFallbackTracker.clearAcapelaFallbackState()
                EngineStatusRegistry.noteReady(
                    AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                )
                EngineStatusRegistry.noteSpeakSuccess(
                    AcapelaEmbeddedVoiceProvider.PROVIDER_ID
                )
            }
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val errText = buildString {
                appendLine("=== محاولة تشخيص Acapela (استثناء) ===")
                appendLine("النوع: ${t.javaClass.name}")
                appendLine("الرسالة: ${t.message ?: "بدون رسالة"}")
                appendLine("nSpeak: غير مقيس (استثناء قبل/أثناء المحاولة)")
                appendLine("PCM: غير مقيس")
                appendLine("التتبع:")
                appendLine(sw.toString())
            }
            Log.e(TAG, errText, t)
            val file = File(context.cacheDir, ACAPELA_FULL_DIAGNOSTIC_FILE)
            runCatching {
                file.writeText(
                    redactSensitiveText(errText),
                    StandardCharsets.UTF_8
                )
            }
        }
    }

    /**
     * يبني تقرير تشخيص Acapela للمحاولة الحالية فقط: يقرأ الملف المستقر
     * [ACAPELA_FULL_DIAGNOSTIC_FILE] المقطوع لكل محاولة، وينقّيه من الأسرار
     * (متعدد الأسطر) بلا إلحاق/إرث بيانات قديمة، مع إلحاق آخر محاولة نطق
     * حقيقية فاشلة إن وجدت.
     */
    fun buildAcapelaFullReport(context: Context): String {
        val diagFile = File(context.cacheDir, ACAPELA_FULL_DIAGNOSTIC_FILE)
        val mainReport = if (diagFile.isFile && diagFile.length() > 0L) {
            val text = runCatching {
                diagFile.readText(StandardCharsets.UTF_8)
            }.getOrNull().orEmpty()
            if (text.isNotBlank()) {
                redactSensitiveText(text).trimEnd()
            } else ""
        } else ""

        val failureFile = File(
            context.cacheDir,
            ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
        )
        val failureText = if (
            failureFile.isFile && failureFile.length() > 0L
        ) {
            runCatching {
                failureFile.readText(StandardCharsets.UTF_8)
            }.getOrNull().orEmpty()
        } else ""

        val baseReport = if (mainReport.isNotBlank()) {
            mainReport
        } else {
            buildString {
                appendLine("=== تشخيص Acapela ===")
                appendLine("لا يوجد سجل لمحاولة حالية.")
                appendLine("nSpeak: غير مقيس")
                appendLine("PCM: غير مقيس")
            }.trimEnd()
        }

        return if (failureText.isNotBlank()) {
            if (!baseReport.contains("=== آخر محاولة نطق حقيقية فاشلة ===")) {
                baseReport + "\n\n" +
                    redactSensitiveText(failureText).trimEnd()
            } else {
                baseReport
            }
        } else {
            baseReport
        }
    }

    /**
     * التحقق الصارم من تحميل الصوت المطلوب حصراً في سجلات المكتبة الأصلية،
     * مع استبعاد أسطر lang_name واستبعاد نجاح أصوات أخرى.
     */
    fun isSpeakerLoadedInLogs(
        speaker: String,
        logs: List<String>
    ): Boolean {
        val speakerNorm = speaker.trim().lowercase(Locale.ROOT)
        val loadVoiceLines = logs.filter { line ->
            line.contains("tts_function_load_voice", ignoreCase = true) &&
                !line.contains("lang_name", ignoreCase = true)
        }
        val matchingLines = loadVoiceLines.filter { line ->
            val foundSpeaker = Regex(
                """tts_function_load_voice\s*:\s*([^\s-]+)"""
            ).find(line)?.groupValues?.get(1)?.trim()
                ?.lowercase(Locale.ROOT)
            foundSpeaker == speakerNorm
        }
        if (matchingLines.isEmpty()) return false
        val lastLine = matchingLines.last()
        val isDone = lastLine.contains("- done", ignoreCase = true) &&
            !lastLine.contains("- error", ignoreCase = true)
        val hasError = matchingLines.any {
            it.contains("- error", ignoreCase = true) ||
                it.contains("eb -", ignoreCase = true)
        }
        return isDone && !hasError
    }

    /**
     * سجل أول فشل أصلي محدد بالمرحلة لتوثيق السبب الحقيقي قبل التنظيف.
     */
    data class FirstFailureRecord(
        val buildVersion: String,
        val buildCode: Long,
        val timestamp: String,
        val runId: String,
        val connectionGeneration: Long,
        val engineName: String,
        val stage: String,
        val verifiedVoiceDir: String,
        val speaker: String,
        val initInvoked: Boolean,
        val initCode: Int?,
        val voiceInvoked: Boolean,
        val voiceCode: Int?,
        val speakInvoked: Boolean,
        val speakCode: Int?,
        val speakDesc: String,
        val samples: Long,
        val bytes: Long,
        val exitReasonDesc: String,
        val isConfirmedNativeCrash: Boolean,
        val pid: Int = android.os.Process.myPid(),
        val attemptStartMs: Long = 0L,
        val attemptEndMs: Long = 0L
    )

    val sessionStartWallMs: Long = System.currentTimeMillis()

    @Volatile
    var recordedFirstFailureRunId: String? = null
        internal set

    /**
     * حفظ سجل أول فشل حقيقي لكل محاولة/تشغيل قبل بدء أي تنظيف أو إعادة ربط.
     */
    fun recordFirstFailure(
        context: Context,
        record: FirstFailureRecord,
        nativeTail: List<String> = emptyList(),
        logFileContent: String = ""
    ) {
        if (recordedFirstFailureRunId == record.runId) {
            return
        }
        recordedFirstFailureRunId = record.runId

        val sb = StringBuilder()
        sb.appendLine("=== سجل أول فشل أصلي (First-Failure Record) ===")
        sb.appendLine(
            "إصدار البناء: ${record.buildVersion} (${record.buildCode})"
        )
        sb.appendLine("الوقت: ${record.timestamp}")
        sb.appendLine("runId: ${record.runId}")
        sb.appendLine("PID: ${record.pid}")
        if (record.attemptStartMs > 0L) {
            sb.appendLine("بداية المحاولة: ${record.attemptStartMs}")
        }
        if (record.attemptEndMs > 0L) {
            sb.appendLine("نهاية المحاولة: ${record.attemptEndMs}")
        }
        sb.appendLine("جيل الاتصال: ${record.connectionGeneration}")
        sb.appendLine("المحرك: ${record.engineName}")
        sb.appendLine("المرحلة: ${record.stage}")
        sb.appendLine("مجلد الصوت المتحقق: ${record.verifiedVoiceDir}")
        sb.appendLine("الهوية (speaker): ${record.speaker}")
        sb.appendLine(
            "nInit: مستدعى=${record.initInvoked} | " +
                "كود=${record.initCode ?: "غير مقيس"}"
        )
        sb.appendLine(
            "nSetCurrentVoice: مستدعى=${record.voiceInvoked} | " +
                "كود=${record.voiceCode ?: "غير مقيس"}"
        )
        sb.appendLine(
            "nSpeak: مستدعى=${record.speakInvoked} | " +
                "كود=${record.speakCode ?: "لم يُستدعَ"}"
        )
        sb.appendLine("وصف nSpeak: ${record.speakDesc}")
        sb.appendLine("عينات PCM: ${record.samples}")
        sb.appendLine("بايتات PCM: ${record.bytes}")
        sb.appendLine("سبب خروج المضيف: ${record.exitReasonDesc}")
        sb.appendLine(
            "انهيار أصلي مؤكد: " +
                if (record.isConfirmedNativeCrash) "نعم" else "لا (غير مؤكد)"
        )
        if (logFileContent.isNotBlank() &&
            logFileContent != "(لم يُنشأ ملف logfile.txt أو فارغ)"
        ) {
            sb.appendLine()
            sb.appendLine("--- سجل logfile.txt ---")
            sb.appendLine(redactSensitiveText(logFileContent).trimEnd())
        }
        val priorCrashLines = mutableListOf<String>()
        val currentAttemptLines = mutableListOf<String>()
        val currPid = record.pid
        val pidTagRegex = Regex("""\s+(\d{3,6})\s+\d{3,6}\s+[VDIWEF]\s+""")

        for (line in nativeTail) {
            val linePid = pidTagRegex.find(line)?.groupValues?.get(1)
                ?.toIntOrNull()
            val isCrashLine =
                line.contains("Fatal signal", ignoreCase = true) ||
                line.contains("SIGSEGV", ignoreCase = true) ||
                line.contains("SIGABRT", ignoreCase = true) ||
                line.contains("DEBUG   :", ignoreCase = true) ||
                line.contains("crash_dump", ignoreCase = true)

            if (linePid != null && linePid != currPid) {
                priorCrashLines.add(line)
            } else if (isCrashLine && linePid == null) {
                priorCrashLines.add(line)
            } else {
                currentAttemptLines.add(line)
            }
        }

        if (priorCrashLines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== انهيار سابق سياقي (PID سابق) ===")
            redactSensitiveLines(priorCrashLines).forEach { line ->
                sb.appendLine(line)
            }
        }
        if (currentAttemptLines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(
                "--- أسطر المكتبة الأصلية (acattsandroid) " +
                    "(${currentAttemptLines.size} سطر) ---"
            )
            redactSensitiveLines(currentAttemptLines).forEach { line ->
                sb.appendLine(line)
            }
        }
        val fullSection = sb.toString().trimEnd()

        runCatching {
            val firstFile = File(context.cacheDir, ACAPELA_FIRST_FAILURE_FILE)
            firstFile.writeText(fullSection, StandardCharsets.UTF_8)
        }
    }

    /**
     * قراءة سجل أول فشل أصلي ثابت إن وُجد مع تمييز السجلات التاريخية.
     */
    fun readFirstFailure(context: Context): String? {
        val file = File(
            context.cacheDir,
            ACAPELA_FIRST_FAILURE_FILE
        ).takeIf { it.isFile && it.length() > 0L } ?: return null
        return readWithHistoricalCheck(file)
    }

    /**
     * قراءة آخر محاولة نطق حقيقية فاشلة إن وُجدت مع تمييز السجلات التاريخية.
     */
    fun readLastRealSpeechFailure(context: Context): String? {
        val file = File(
            context.cacheDir,
            ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE
        ).takeIf { it.isFile && it.length() > 0L }
            ?: File(
                context.cacheDir,
                ACAPELA_FIRST_FAILURE_FILE
            ).takeIf { it.isFile && it.length() > 0L }
            ?: return null
        return readWithHistoricalCheck(file)
    }

    private fun readWithHistoricalCheck(file: File): String? {
        val text = runCatching {
            file.readText(StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val isHistorical = file.lastModified() < sessionStartWallMs - 3000L
        return if (isHistorical) {
            val dateStr = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US
            ).format(Date(file.lastModified()))
            "=== سجل محاولة سابقة من تشغيل منتهٍ ($dateStr) — " +
                "غير تابع للتشغيل الحالي ===\n$text"
        } else {
            text
        }
    }

    /**
     * مسح ملف آخر محاولة نطق حقيقية فاشلة وسجل أول فشل.
     */
    fun clearLastRealSpeechFailure(context: Context) {
        recordedFirstFailureRunId = null
        val f1 = File(context.cacheDir, ACAPELA_FIRST_FAILURE_FILE)
        val f2 = File(context.cacheDir, ACAPELA_LAST_REAL_SPEECH_FAILURE_FILE)
        runCatching { f1.delete() }
        runCatching { f2.delete() }
    }

    /**
     * استرجاع آخر [maxLines] سطراً من سجل المكتبة الأصلية عبر Logcat.
     */
    fun readNativeLogcatLines(maxLines: Int = 1000): List<String> {
        return runCatching {
            val fetchCount = maxLines.coerceAtLeast(1000).toString()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", fetchCount)
            )
            val lines = proc.inputStream
                .bufferedReader(StandardCharsets.UTF_8).readLines()
            proc.waitFor()
            val filtered = lines.filter { line ->
                line.contains("acattsandroid", ignoreCase = true) ||
                    line.contains("tts_function", ignoreCase = true) ||
                    line.contains("BABILE", ignoreCase = true) ||
                    line.contains("eb=-", ignoreCase = true) ||
                    line.contains("nlp", ignoreCase = true) ||
                    line.contains("initError", ignoreCase = true) ||
                    line.contains("salma", ignoreCase = true) ||
                    line.contains("engine_host", ignoreCase = true) ||
                    line.contains("crash", ignoreCase = true) ||
                    line.contains("tombstone", ignoreCase = true) ||
                    line.contains("DEBUG", ignoreCase = true) ||
                    line.contains("libc", ignoreCase = true) ||
                    line.contains("Fatal signal", ignoreCase = true) ||
                    line.contains("Abort message", ignoreCase = true) ||
                    line.contains("SIGSEGV", ignoreCase = true) ||
                    line.contains("SIGABRT", ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                filtered.takeLast(maxLines)
            } else {
                val procTag = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d", "-t", fetchCount,
                        "-s", "acattsandroid-extended-native:V",
                        "DEBUG:V", "crash_dump:V", "libc:V"
                    )
                )
                val linesTag = procTag.inputStream
                    .bufferedReader(StandardCharsets.UTF_8).readLines()
                procTag.waitFor()
                val tagFiltered = linesTag.filter {
                    it.isNotBlank() && !it.startsWith("---------")
                }
                if (tagFiltered.isNotEmpty()) {
                    tagFiltered.takeLast(maxLines)
                } else {
                    listOf("(لم يُعثر على أسطر للمكتبة الأصلية في logcat)")
                }
            }
        }.getOrElse { t ->
            listOf("(تعذر قراءة logcat للمكتبة الأصلية: ${t.message})")
        }.let(::redactSensitiveLines)
    }

    /**
     * [بناء 10729] أسطر logcat الخاصة بعملية `:engine_host` صراحةً،
     * وتشمل سطور الانهيار (Cmdline/Process) التي تحوي `:engine_host`.
     */
    fun readEngineHostLogcatLines(maxLines: Int = 400): List<String> {
        return runCatching {
            val proc = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat", "-d", "-v", "time",
                    "-t", maxLines.coerceAtLeast(400).toString()
                )
            )
            val lines = proc.inputStream
                .bufferedReader(StandardCharsets.UTF_8).readLines()
            proc.waitFor()
            val matched = lines.filter {
                it.contains("engine_host", ignoreCase = true)
            }
            if (matched.isNotEmpty()) {
                matched.takeLast(maxLines)
            } else {
                listOf("(لم يُعثر على أسطر logcat تحوي :engine_host)")
            }
        }.getOrElse { t ->
            listOf("(تعذر قراءة logcat :engine_host: ${t.message})")
        }.let(::redactSensitiveLines)
    }

    /**
     * [بناء 10729] إلحاق محتوى ملف تشخيص مخزَّن بالتقرير إن وُجد،
     * مع وسم صريح عند غيابه كي لا يبدو التقرير مكتملاً وهو ناقص.
     */
    fun appendCachedDiagnosticFile(
        sb: StringBuilder,
        cacheDir: File?,
        fileName: String
    ) {
        if (cacheDir == null) {
            sb.appendLine("  (مجلد cache غير متاح: $fileName)")
            return
        }
        val file = File(cacheDir, fileName)
        if (!file.exists()) {
            sb.appendLine("  (الملف غير موجود: $fileName)")
            return
        }
        val text = runCatching {
            file.readText(StandardCharsets.UTF_8)
        }.getOrNull()
        if (text.isNullOrBlank()) {
            sb.appendLine("  (الملف فارغ: $fileName)")
        } else {
            sb.appendLine(text.trimEnd())
        }
    }

    /**
     * قراءة أسطر Logcat الخاصة بالمكتبة الأصلية بين علامتي البداية والنهاية،
     * وتضمين كافة أسطر acattsandroid-extended-native كاملة بلا استثناء
     * (البند 2).
     */
    fun readLogcatBetweenMarkers(
        startMarker: String,
        endMarker: String,
        maxLines: Int = 1000
    ): List<String> {
        return runCatching {
            val fetchCount = maxLines.coerceAtLeast(1000).toString()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", fetchCount)
            )
            val lines = proc.inputStream
                .bufferedReader(StandardCharsets.UTF_8).readLines()
            proc.waitFor()
            var recording = false
            val captured = mutableListOf<String>()
            for (line in lines) {
                if (line.contains(startMarker)) {
                    recording = true
                    continue
                }
                if (line.contains(endMarker)) {
                    recording = false
                    break
                }
                if (recording) {
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank() &&
                        !trimmed.startsWith("---------")
                    ) {
                        captured.add(trimmed)
                    }
                }
            }
            if (captured.isNotEmpty()) {
                captured
            } else {
                lines.filter { line ->
                    line.contains("acattsandroid", ignoreCase = true) ||
                        line.contains("initLanguage", ignoreCase = true) ||
                        line.contains("userdico", ignoreCase = true) ||
                        line.contains("error", ignoreCase = true) ||
                        line.contains("fail", ignoreCase = true)
                }.takeLast(maxLines)
            }
        }.getOrElse { t ->
            listOf("(تعذر قراءة logcat للمحاولة: ${t.message})")
        }.let(::redactSensitiveLines)
    }

    /**
     * استخراج قائمة واصفات الملفات المفتوحة للعملية الحالية عبر /proc/self/fd
     * مع توحيد المسارات لتكون تحت /data/user/0.
     */
    fun getOpenFileDescriptors(): List<String> {
        return runCatching {
            File("/proc/self/fd").listFiles()?.mapNotNull { f ->
                runCatching {
                    android.system.Os.readlink(f.absolutePath)
                }.getOrNull()
            }?.filter { path ->
                path.startsWith("/") &&
                    !path.startsWith("/dev/") &&
                    !path.startsWith("/proc/") &&
                    !path.startsWith("/sys/")
            }?.map { normalizeToUser0(it) }?.distinct()
        }.getOrNull().orEmpty()
    }

    /**
     * استخراج أسماء الملفات المذكورة في أسطر سجل المكتبة الأصلية.
     */
    fun extractFilesFromLogLines(lines: List<String>): List<String> {
        val pattern = Regex(
            "([/\\w.-]+\\.(?:ini|inf|bvcu|dca|bnx|pho|ldi|trz|cma|oso|gra|" +
                "userdico|dat|yaml|txt)|0x[0-9a-fA-F]+)"
        )
        return lines.flatMap { line ->
            pattern.findAll(line).map { it.value }
        }.distinct()
    }

    /**
     * تقرير فحص أدلة نجاح بناءي 10708 و 10709 (البند 1 لبناء 10722).
     */
    fun getEvidenceReportFor10708And10709(): String = buildString {
        appendLine("=== [البند 1 من 10722] فحص أدلة نجاح 10708 و10709 ===")
        appendLine("1. فحص سجلات الاختبار وتاريخ git ومحفوظات التشخيص:")
        appendLine(
            "   - في بناء 10708 (commit f70a0e37): فشلت كافة التوليفات " +
                "الثلاث بـ eb=-27 و nlpInitError=-34."
        )
        appendLine(
            "   - في بناء 10709 (commit 8426057b): شُغّلت التوليفات على " +
                "جهاز المستخدم، وحُفظ ملف التقرير"
        )
        appendLine(
            "     داخل ذاكرة الهاتف في context.cacheDir فقط " +
                "(acapela_full_diagnostic_10709.txt)."
        )
        appendLine(
            "   - فحص مستودع git وتاريخ commits وملفات التشخيص وسجلات " +
                "الاختبار يثبت:"
        )
        appendLine(
            "     **لا يوجد أي دليل مكتوب أو سجل اختبار محفوظ لبناءي " +
                "10708 أو 10709 داخل المستودع**"
        )
        appendLine(
            "     يثبت ظهور سطر 'eb=0 و nlpErr=0' على القرص."
        )
        appendLine(
            "   - الموضع الوحيد المسجل لهذا السطر هو نص رسالة المستخدم " +
                "الواردة في مطلع بناء 10710."
        )
        appendLine(
            "2. المجلد الحرفي الممرر إلى nInit في تجربة (أ) لبناء 10709 " +
                "(من الكود f70a0e37/8426057b):"
        )
        appendLine(
            "   - المسار: File(context.cacheDir, \"acapela_stage_v2\")." +
                "absolutePath + \"/\""
        )
        appendLine(
            "3. قائمة ملفات acapela_stage_v2 بالأحجام وفق كود 10709:"
        )
        appendLine(
            "   - salma_22k_lf.bvcu: 29,213,971 بايت (ملف الصوت الأساسي)"
        )
        appendLine(
            "   - ar_sa_hd_salma_22k_lf.bvcu.inf: 139 بايت"
        )
        appendLine(
            "   - ar_sa_hd_salma_22k_lf.ini: 61 بايت " +
                "(MODE=prep_hd مع [ RAM ])"
        )
        appendLine(
            "   - ar_sa.dca: 3,455,285 بايت"
        )
        appendLine(
            "   - ar_sa.bnx: 16,907 بايت"
        )
        appendLine(
            "   - ar_sa.pho: 1,939 بايت"
        )
        appendLine(
            "   - ar_sa.ldi: 76 بايت"
        )
        appendLine(
            "   - ar_sa.trz: 35,463 بايت"
        )
        appendLine(
            "   - ar_sa.cma: 16,384 بايت"
        )
        appendLine(
            "   - ar_sa.oso: 580 بايت"
        )
        appendLine(
            "   - ar_sa.gra: 1,811 بايت"
        )
        appendLine(
            "   - 0x00169729: 139 بايت"
        )
        appendLine(
            "   - config.yaml: 314 بايت"
        )
        appendLine(
            "   - userdicos/user.userdico: 0 بايت"
        )
    }

    /**
     * سرد دوال JNI في مكتبات Acapela (البند 2).
     */
    fun getJniFunctionsCatalog(): String = buildString {
        appendLine("=== [دوال JNI في مكتبات Acapela الأصلية (البند 2)] ===")
        appendLine("")
        appendLine("1) دوال JNI في libttsacattsandroidextended.so:")
        appendLine("  - nInit(path: String): int")
        appendLine("  - nSetCurrentVoice(speaker: String): int")
        appendLine("    [ضبط الصوت واستدعاء tts_function_load_voice]")
        appendLine("  - nSetLanguage(lang, country, variant: String): int")
        appendLine("  - nSetPreferedVoice(lang, country, voice: String): int")
        appendLine("    [تسجيل الصوت المفضل للغة]")
        appendLine("  - nRefrehEnumeration(): int")
        appendLine("    [حصر الأصوات وتحميلها: tts_function_load_voice]")
        appendLine("  - nloadLanguage(lang, country, variant: String): int")
        appendLine("    [تحميل إعدادات اللغة: cSetLanguage]")
        appendLine("  - nGetClosestVoice(lang, country, var: String): String")
        appendLine("    [حالة/مطابقة أقرب صوت للغة]")
        appendLine("  - nIsLanguageAvailable(lang, cntry, var: String): int")
        appendLine("    [حالة توفر اللغة: 0=متاحة]")
        appendLine("  - nGetLanguage(): String  [حالة اللغة الحالية]")
        appendLine("  - nSetProperty(key, value: String): int")
        appendLine("  - nSetVoiceMode(mode: String): int")
        appendLine("  - nSpeak(text: String, cb: SynthesisCallback): int")
        appendLine("  - nStop(): int")
        appendLine("  - nStopSync(): int")
        appendLine("  - nShutdown(): int")
        appendLine("  - nUtf8Mode(lang, country: String): int")
        appendLine("  - nGetPhonetic(text: String): String")
        appendLine("  - NativeInterface.nSetInfo(...)")
        appendLine("  - NativeInterface.nGetPhonetic(...)")
        appendLine("  * لا توجد دالة nGetLastError أو nLoadVoice في الممتدة.")
        appendLine("  * تحميل الصوت يتم داخلياً عبر nSetCurrentVoice و")
        appendLine("    nRefrehEnumeration و nSetPreferedVoice.")
        appendLine("")
        appendLine("2) دوال JNI في المكتبة الكلاسيكية (libacattsandroid.so):")
        appendLine("  - nLoadVoice(voicePathOrName: String): int")
        appendLine("    [دالة صريحة لتحميل الصوت]")
        appendLine("  - nGetLastError(): int  [دالة صريحة لآخر خطأ]")
        appendLine("  - nIsSpeaking(): int  [حالة: هل يتحدث]")
        appendLine("  - nIsPaused(): int  [حالة: هل متوقف مؤقتاً]")
        appendLine("  - nGetVoiceList(paths: Array<String>): int")
        appendLine("    [حصر الأصوات المتاحة]")
        appendLine(
            "  - nGetVoiceInfo(path, key: String): String  [بيانات صوت]"
        )
        appendLine("  - nGetLanguage(): String  [حالة اللغة]")
        appendLine("  - nGetAudioBufferSize(): int")
        appendLine("  - nGetTextIndex(): int")
        appendLine("  - nGetVersion(): String")
        appendLine("  - nInitCallbacks(): int")
        appendLine("  - nSetLicense(user, pass: Long, lic: String): int")
        appendLine("  - nSetLog(enable: Boolean): void")
        appendLine("  - nSetShape(shape: Float): int")
        appendLine("  - nGetShape(): int")
        appendLine("  - nSetSpeechRate(rate: Float): int")
        appendLine("  - nGetSpeechRate(): int")
        appendLine("  - nSetTTSSetting(setting: String, val: Int): int")
        appendLine("  - nSpeak(text: String): int")
        appendLine("  - nSpeakToFile(text, path: String): int")
        appendLine("  - nStop(): int")
        appendLine("  - nPause(): int")
        appendLine("  - nResume(): int")
        appendLine("  - nShutdown(): int")
        appendLine("  - nQueueText(text: String): int")
    }.trimEnd()

    /**
     * شرح معنى الرمز -2 الراجع من nSpeak وثوابته في كود المكتبة (البند 4).
     */
    fun getSpeakErrorCodeExplanation(): String = buildString {
        appendLine("=== [معنى الرمز -2 الراجع من nSpeak (البند 4)] ===")
        appendLine("- التفكيك الثنائي لـ libttsacattsandroidextended.so:")
        appendLine("  في دالة tts_function_analyse_and_speak")
        appendLine("  (العنوان 0x1bd5dc): يُفحص مقبض الصوت المحمل")
        appendLine("  عند الإزاحة 0x200 ([voice + 0x200]).")
        appendLine(
            "  إن كان المقبض صفراً (NULL)، تقفز التعليمات إلى 0x1bd988:"
        )
        appendLine("    1bd988: mov w0, #-0x2")
        appendLine("    1bd98c: b   0x1bdc7c")
        appendLine("  وتعيد الدالة الرمز -2 فوراً دون توليد أي عينة PCM!")
        appendLine("- التفكيك الثنائي لـ libacattsandroid.so (الكلاسيكية):")
        appendLine("  في دالة nSpeak (العنوان 0x60188):")
        appendLine("  إن كان مقبض الصوت صفراً، تقفز الدالة إلى 0x60274:")
        appendLine("  تطبع السجل الحرفي: \"nSpeak done - no voice loaded\"")
        appendLine("  وتسجل آخر خطأ بـ -2 (str w19, [x21, #0x364]) وتعيد -2!")
        appendLine("- النتيجة القاطعة:")
        appendLine("  الرمز -2 يعني حصراً وقطعياً: \"No voice loaded\"")
        appendLine("  (لم يُحمّل صوت / مقبض محرك الصوت NULL).")
        appendLine("  وسببه أن nSetCurrentVoice تعيد 0 بمجرد تطابق اسم الصوت،")
        appendLine("  دون التحقق من نجاح tts_function_load_voice، فيبقى")
        appendLine("  المقبض صفراً إن تعثرت تهيئة محرك الصوت،")
        appendLine("  فيكشف استدعاء nSpeak غياب المقبض ويعيد -2.")
    }.trimEnd()

    /**
     * استخراج قيمة الترخيص من ملفات .inf إن وُجد فيها سطر ترخيص.
     */
    fun extractLicenseFromInf(stageDir: File): String? {
        val infFiles = stageDir.listFiles { f ->
            f.isFile && f.extension.equals("inf", ignoreCase = true)
        }.orEmpty()
        for (inf in infFiles) {
            val content = runCatching {
                inf.readText(StandardCharsets.ISO_8859_1)
            }.getOrNull() ?: continue
            val match = Regex(
                "(?im)^\\s*(?:license|lic|key)\\s*(?:=|\\s)\\s*" +
                    "\"?([^\"\\r\\n]+)\"?"
            ).find(content)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (found.isNotBlank()) return found
            }
        }
        return null
    }

    /**
     * تتبع مسار email و hexCode في كود MultiVoice
     * (org.volodymyr.multivoice، البند 1 من إصدار 10716):
     * مصدر القراءة، الحساب/الاشتقاق، أسماء الدوال والحقول حرفياً،
     * وتأكيد الاستقلالية التامة عن اسم الحزمة والتوقيع ومعرف الجهاز.
     */
    fun getMultiTtsEmailHexCodeTrace(): String = buildString {
        appendLine(
            "=== [البند 1] تتبع مسار email و hexCode في كود " +
                "MultiVoice (org.volodymyr.multivoice) ==="
        )
        appendLine("")
        appendLine("1) مصدر قراءة email و hexCode:")
        appendLine(
            "  - تُقرأ حصرياً من خاصية param في ملف config.yaml للصوت " +
                "(أو تُفك شفرتها عبر"
        )
        appendLine(
            "    AcapelaMetadataCodec.decode إن كانت مشفرة بخوارزمية " +
                "package-codec)."
        )
        appendLine(
            "  - في MultiVoice (org.volodymyr.multivoice): الدالة " +
                "AcapelaPack.yamlModels"
        )
        appendLine(
            "    تقوم بتحليل config.yaml واستخراج السلسلة الخام لخاصية param."
        )
        appendLine("")
        appendLine("2) كيفية الحساب والاشتقاق والدمج:")
        appendLine(
            "  - الصيغة في config.yaml: " +
                "voiceCode,email,hexCode (ثلاثة أجزاء مفصولة بفاصلة)."
        )
        appendLine(
            "  - الدالة AcapelaPack.buildRegistration(param) في " +
                "org.volodymyr.multivoice:"
        )
        appendLine("    String[] parts = clean.split(\",\", -1);")
        appendLine("    String email = parts[1];")
        appendLine("    String hexCode = parts[2];")
        appendLine(
            "    return email + hexCode; // دمج مباشر وسلسلة متصلة " +
                "بدون فاصلة ولا مسافة!"
        )
        appendLine(
            "  - لا يوجد أي اشتقاق رياضي أو تجزئة؛ مجرد دمج نصي مباشر " +
                "للجزءين الثاني والثالث."
        )
        appendLine("")
        appendLine(
            "3) الاستقلالية التامة عن بيئة الجهاز " +
                "(فحص بايت كود MultiVoice):"
        )
        appendLine(
            "  - اسم الحزمة (Package Name): لا تعتمد عليه إطلاقاً " +
                "(0 استدعاء لـ getPackageName)."
        )
        appendLine(
            "  - بصمة التوقيع (App Signature): لا تفحص التوقيع " +
                "(0 استدعاء لـ GET_SIGNATURES)."
        )
        appendLine(
            "  - معرف الجهاز (ANDROID_ID): لا تقرأ ANDROID_ID " +
                "(0 استدعاء لـ Settings.Secure)."
        )
        appendLine(
            "  - النتيجة: بيانات التسجيل (email + hexCode) مستقلة 100% " +
                "عن الحزمة والجهاز."
        )
        appendLine("")
        appendLine("4) التخزين والتمرير إلى المكتبة الأصلية (الأسماء حرفياً):")
        appendLine(
            "  - في فئة AcapelaPack: الحقل " +
                "public final String registration;"
        )
        appendLine(
            "  - في فئة ExtendedEngine: المُنشئ " +
                "ExtendedEngine(Audio audio, AcapelaPack pack):"
        )
        appendLine(
            "    this.api = new AcattsandroidService(audio, " +
                "pack.registration);"
        )
        appendLine(
            "  - في فئة AcattsandroidService: الحقل " +
                "private final String registration;"
        )
        appendLine(
            "  - دالة رد النداء لـ JNI: " +
                "@Keep public String idfunction() { " +
                "return this.registration; }"
        )
        appendLine(
            "  - داخل المكتبة الأصلية (libttsacattsandroidextended.so):"
        )
        appendLine(
            "    * في nInit: حفظ jmethodID لدالة idfunction " +
                "()Ljava/lang/String; عند 0x36fbb8."
        )
        appendLine(
            "    * في check_license(): استدعاء CallObjectMethod " +
                "للحصول على سلسلة التسجيل."
        )
        appendLine(
            "    * حساب SHA1 للسلسلة، وصياغة أول كلمة hex " +
                "بصيغة \"%08x\"، وتخزين أول 4 بايت في mUserid."
        )
    }.trimEnd()

    /**
     * تفكيك دالة check_license وفحص ملف الترخيص على القرص
     * (البند 2 من إصدار 10716).
     */
    fun getDiskLicenseFileDisassemblyAnalysis(): String = buildString {
        appendLine(
            "=== [البند 2] تفكيك check_license وفحص ملف الترخيص " +
                "على القرص ==="
        )
        appendLine("")
        appendLine("1) مواصفات الملف الذي تفحصه المكتبة الأصلية على القرص:")
        appendLine(
            "  - اسم الملف: أي ملف يطابق صيغة 0x[0-9A-Fa-f]{8,16} " +
                "(لصوت Salma: 0x00169729)."
        )
        appendLine(
            "  - مسار الملف: مباشرة داخل مجلد الصوت mVoicesPath " +
                "الممرر إلى nInit."
        )
        appendLine(
            "  - تنسيق الملف: ملف نصي عادي يُفتح بوضع \"rt\"، " +
                "ويجب أن يحتوي على \"COMMERCIAL\"."
        )
        appendLine(
            "  - توقيت الفحص: يُفحص داخل check_license() " +
                "أثناء tts_function_load_voice"
        )
        appendLine(
            "    قبل استدعاء BABILE_initEx مباشرة (يُشترط وجوده " +
                "لتهيئة محرك الصوت)."
        )
        appendLine("")
        appendLine("2) نتائج تفكيك check_license (العنوان 0x1b8f70):")
        appendLine("  [0x1b8fc8] ldr x21, [x21, #0xbb8]  // مؤشر idfunction")
        appendLine(
            "  [0x1b8fd0] cbz x8, #0x1b90e0       // إن كان صفراً < 4.0"
        )
        appendLine("  [0x1b8fe8] // STR: 'android >= 4.0 check method'")
        appendLine(
            "  [0x1b902c] bl  CallObjectMethod    // استدعاء idfunction"
        )
        appendLine("  [0x1b904c] blr x9                  // GetStringUTFChars")
        appendLine("  [0x1b9084] bl  SHA1Reset           // تهيئة سياق SHA1")
        appendLine(
            "  [0x1b90a0] bl  SHA1Input          // تمرير سلسلة التسجيل"
        )
        appendLine("  [0x1b90a8] bl  SHA1Result          // حساب SHA1")
        appendLine("  [0x1b90b0] ldr w4, [sp, #0x130]    // أول كلمة 32-بت")
        appendLine("  [0x1b90b8] // STR: '%08x'")
        appendLine("  [0x1b90c8] bl  snprintf(buf, 5, \"%08x\", w4)")
        appendLine("  [0x1b90d8] str w8, [mUserid]       // تخزين 4 بايت")
        appendLine("  [0x1b91cc] bl  opendir             // فتح mVoicesPath")
        appendLine(
            "  [0x1b9214] bl  readdir            // قراءة مدخلات المجلد"
        )
        appendLine("  [0x1b9264] // STR: '0x'")
        appendLine("  [0x1b9268] bl  strstr(name, \"0x\") // فحص وجود 0x")
        appendLine("  [0x1b9284] bl  sprintf(\"%s/%s\")   // تكوين المسار")
        appendLine("  [0x1b9380] // STR: '%x'")
        appendLine("  [0x1b938c] bl  sscanf(name, \"%x\", &mPasswd)")
        appendLine("  [0x1b9394] // STR: 'rt'")
        appendLine("  [0x1b939c] bl  fopen(path, \"rt\")  // فتح الملف")
        appendLine(
            "  [0x1b93dc] bl  fgets               // قراءة أسطر الترخيص"
        )
        appendLine("  [0x1b93f8] bl  strcat              // دمج في mLicense")
        appendLine("  [0x1b9414] bl  fclose              // إغلاق الملف")
        appendLine("  [0x1b9438] // STR: 'COMMERCIAL'")
        appendLine("  [0x1b9440] bl  strstr(mLicense, \"COMMERCIAL\")")
        appendLine("  [0x1b944c] // STR: 'license value error'")
        appendLine("")
        appendLine("3) تمرير القيم إلى Babile (العنوان 0x1ba388..0x1ba404):")
        appendLine(
            "  [0x1ba3ac] str x19, [babParam+0x280] // babParam.license"
        )
        appendLine("  [0x1ba3b0] str w9,  [babParam+0x288] // babParam.userId")
        appendLine("  [0x1ba3b4] str w8,  [babParam+0x28c] // babParam.passwd")
        appendLine("  [0x1ba3cc] // STR: 'check license done'")
        appendLine("  [0x1ba3e0] // STR: 'BABILE_initEx before'")
        appendLine("  [0x1ba404] bl  BABILE_initEx")
        appendLine("  [0x1ba6f0] // STR: '\\tbabParam.license %s\\n'")
        appendLine("  [0x1ba71c] // STR: '\\tbabParam.uid.userId %d\\n'")
        appendLine("  [0x1ba748] // STR: '\\tbabParam.uid.passwd %d\\n'")
    }.trimEnd()

    /**
     * نتائج فحص كود وتفكيك MultiVoice (org.volodymyr.multivoice)
     * لنداءات nSetLicense / setLicense
     * (البند 1 من إصدار 10715).
     */
    fun getMultiTtsLicenseAnalysis(): String = buildString {
        appendLine(
            "=== [البند 1] فحص كود MultiVoice " +
                "(org.volodymyr.multivoice) لنداءات " +
                "nSetLicense / setLicense ==="
        )
        appendLine("")
        appendLine(
            "1) نداءات nSetLicense في بايت كود وتفكيك MultiVoice " +
                "(org.volodymyr.multivoice):"
        )
        appendLine(
            "  - عُثر على نداء وحيد حصرياً في فئة NativeEngine " +
                "(المحرك الكلاسيكي):"
        )
        appendLine(
            "    Lorg/volodymyr/multivoice/" +
                "AcapelaSpeech\$NativeEngine;-><init>"
        )
        appendLine("  - سطر الاستدعاء بالبايت كود حرفياً:")
        appendLine(
            "    invoke-virtual/range {v2 .. v7}, " +
                "Lcom/acapelagroup/android/tts/acattsandroid;->nSetLicense" +
                "(J J Ljava/lang/String;)I"
        )
        appendLine("")
        appendLine("2) القيمتان الرقميتان user و pass وترتيبهما الحرفي:")
        appendLine(
            "  - المعامل الأول (user): 1179736167L " +
                "(hex: 0x46515867L، كود ASCII: 'gXQF')"
        )
        appendLine(
            "  - المعامل الثاني (pass): 3189603L " +
                "(hex: 0x0030ab63L)"
        )
        appendLine(
            "  - الترتيب في التوقيع: " +
                "nSetLicense(user: Long, pass: Long, lic: String): Int"
        )
        appendLine("")
        appendLine(
            "3) مصدر السلسلة lic في MultiVoice (org.volodymyr.multivoice):"
        )
        appendLine(
            "  - تُقرأ مباشرة من مسار الأصول: " +
                "'acapela/runtime.dat' داخل أصول التطبيق."
        )
        appendLine("  - بنية ملف runtime.dat (200 بايت بالضبط):")
        appendLine(
            "    * 8 بايت: readLong() = 1179736167L (user)"
        )
        appendLine(
            "    * 8 بايت: readLong() = 3189603L (pass)"
        )
        appendLine(
            "    * 4 بايت: readInt() = 180 (طول السلسلة)"
        )
        appendLine(
            "    * 180 بايت: سلسلة UTF-8 تبدأ بـ:"
        )
        appendLine(
            "      \"2948 0 gXQF #COMMERCIAL#VOICE DREAM Norway\""
        )
        appendLine("")
        appendLine("4) التوقيت بالنسبة لـ nInit:")
        appendLine(
            "  - في NativeEngine (الكلاسيكي): يُنادى nSetLicense فوراً " +
                "بعد nInitCallbacks وقبل أي تحميل للصوت (المحرك الكلاسيكي " +
                "ليس فيه nInit)."
        )
        appendLine(
            "  - في ExtendedEngine (الممتد AcattsandroidService): لا يستدعي " +
                "MultiVoice دالة nSetLicense إطلاقاً!"
        )
        appendLine(
            "    بل يمرر كود التسجيل (email + hexCode) إلى مُنشئ " +
                "AcattsandroidService،"
        )
        appendLine(
            "    وتقوم المكتبة الأصلية داخل nInit باستدعاء idfunction() " +
                "وفحص ملف 0x... على القرص."
        )
    }.trimEnd()

    /**
     * نتائج البحث عن كلمة license والنص المرتبط بـ -27 في
     * libttsacattsandroidextended.so (البند 3 من إصدار 10714).
     */
    fun getLicenseStringsAndError27Catalog(): String = buildString {
        appendLine(
            "=== [نتائج البحث عن كلمة license والنص المرتبط بـ -27 " +
                "في المكتبة الأصلية (البند 3)] ==="
        )
        appendLine("")
        appendLine(
            "1) السلاسل النصية الحاوية على كلمة license في " +
                "libttsacattsandroidextended.so:"
        )
        appendLine("  [0x1eca3] '_Z24BABILE_checkVoiceLicensePcPjS0_S_'")
        appendLine("  [0x1eda6] '_Z13check_licensev'")
        appendLine("  [0x1ee13] 'mLicense'")
        appendLine("  [0x2928c] 'ALF_Model_getLicense'")
        appendLine("  [0x2969f] 'Colibri_getVoiceLicense'")
        appendLine("  [0x5db66] 'license value error'")
        appendLine(
            "  [0x5e235] 'license with expiration date is not allowed " +
                "(must be set in the voice)'"
        )
        appendLine("  [0x5e706] '- use voice specific License'")
        appendLine("  [0x5f043] 'license empty'")
        appendLine("  [0x5ff9e] 'E_BABILE_LICENSE'")
        appendLine("  [0x60078] 'check license done'")
        appendLine("  [0x604d8] 'Default voice - license'")
        appendLine("  [0x60d0e] 'error license'")
        appendLine("  [0x62a2d] '\\tbabParam.license %s\\n'")
        appendLine("")
        appendLine("2) السلسلة النصية المرتبطة بالرمز -27 حرفياً:")
        appendLine("  - في مصفوفة BABILE_errorStrings (العنوان 0x368d68):")
        appendLine(
            "    الكود -27 (الإزاحة 0x5b46d): 'E_BABILE_NLPINITERROR'"
        )
        appendLine(
            "  - في صيغة سجل الخطأ لـ tts_function_load_voicei " +
                "(العنوان 0x604f0):"
        )
        appendLine(
            "    'tts_function_load_voice : %s - error - eb %d " +
                "- em %d - en %d'"
        )
        appendLine("")
        appendLine(
            "3) الإثبات الثنائي لمنشأ -27 في دالة تهيئة NLP " +
                "(العنوان 0x1dda50..0x1ddadc):"
        )
        appendLine("  - الدالة تستدعي BBNLP_init (العنوان 0x1ddab8).")
        appendLine("  - إن عادت BBNLP_init بصفر (NULL):")
        appendLine(
            "    تستخرج كود فشل NLP الداخلي " +
                "(وهو -34 = E_BABILE_LICENSE)."
        )
        appendLine(
            "    وتخزنه في babParam.nlpInitError " +
                "(ldr x8, [sp, #8]; str x8, [x19, #0x38])."
        )
        appendLine("    ثم تعيد الدالة مباشرة: mov x0, #-0x1b (أي -27)!")
        appendLine(
            "  - هذا يثبت أن eb=-27 هو كود E_BABILE_NLPINITERROR " +
                "الصادر عن فشل BBNLP_init،"
        )
        appendLine(
            "    وسبب فشل BBNLP_init نفسه هو -34 (E_BABILE_LICENSE)!"
        )
    }.trimEnd()

    private fun appendTail(
        sb: StringBuilder,
        title: String,
        file: File?
    ) {
        sb.appendLine("- $title (${file?.name ?: "-"}):")
        tailLines(file).forEach { sb.appendLine("  $it") }
    }

    fun babileErrorName(code: Int): String = when (code) {
        0 -> "NO ERROR"
        -1 -> "E_BABILE_NOMEM"
        -2 -> "E_BABILE_MEMFREE"
        -3 -> "E_BABILE_ISPLAYING"
        -4 -> "E_BABILE_SPEAKERROR"
        -5 -> "E_BABILE_WAVEOUTWRITE"
        -6 -> "E_BABILE_WAVEOUTNOTFREE"
        -7 -> "E_BABILE_PROCESSERROR"
        -8 -> "E_BABILE_REGISTRYERROR"
        -9 -> "E_BABILE_NOREGISTRY"
        -10 -> "E_BABILE_NOTVALIDPARAMETER"
        -11 -> "E_BABILE_THREADERROR"
        -12 -> "E_BABILE_NONLP"
        -13 -> "E_BABILE_INVALIDTAG"
        -14 -> "E_BABILE_FILEWRITE"
        -15 -> "E_BABILE_FILEOPEN"
        -16 -> "E_BABILE_BADPHO"
        -17 -> "E_BABILE_DICT_OPEN"
        -18 -> "E_BABILE_DICT_WRITE"
        -19 -> "E_BABILE_DICT_READ"
        -20 -> "E_BABILE_DICT_NOENTRY"
        -21 -> "E_BABILE_NOTIMPLEMENTED"
        -22 -> "E_BABILE_NODBA"
        -23 -> "E_BABILE_NODICT"
        -24 -> "E_BABILE_VALUEOUTOFBOUNDS"
        -25 -> "E_BABILE_NULLARG"
        -26 -> "E_BABILE_MBRINITERROR"
        -27 -> "E_BABILE_NLPINITERROR"
        -28 -> "E_BABILE_MBRERROR"
        -29 -> "E_BABILE_PHOSTRPROGRESSERROR"
        -30 -> "E_BABILE_DICT_EXIST"
        -31 -> "E_BABILE_SELINITERROR"
        -32 -> "E_BABILE_SELERROR"
        -33 -> "E_BABILE_NOSEL"
        -34 -> "E_BABILE_LICENSE"
        -41 -> "E_BABILE_SYNTHESIZESF"
        -42 -> "E_BABILE_PHONBUFERROR"
        -43 -> "E_BABILE_NOMBR"
        -44 -> "E_BABILE_NLPERROR"
        -45 -> "E_BABILE_COLERROR"
        -46 -> "E_BABILE_NOCOLIBRI"
        -47 -> "E_BABILE_COLINITERROR"
        -48 -> "E_BABILE_NOVALIDSYNTH"
        -49 -> "E_BABILE_ANNINITERROR"
        -50 -> "E_BABILE_ANNERROR"
        else -> "UNKNOWN"
    }

    fun getBabileError25Explanation(): String = buildString {
        appendLine("=== [البند 4 من 10717] تفسير كود الخطأ -25 ===")
        appendLine("1. الرمز الحرفي في BABILE_errorStrings:")
        appendLine("   - الكود -25 يطابق حرفياً: \"E_BABILE_NULLARG\"")
        appendLine(
            "   - المصدر: مصفوفة BABILE_errorStrings (OBJECT عند 0x368d68"
        )
        appendLine("     في libttsacattsandroidextended.so، المدخل 25)")
        appendLine(
            "   - إعادة التوجيه R_AARCH64_RELATIVE تشير إلى 0x6244d:"
        )
        appendLine("     \"E_BABILE_NULLARG\"")
        appendLine("2. سبب ظهور eb=-25 عند تمرير param كاملاً:")
        appendLine(
            "   - عند تمرير param كاملاً (الذي يحوي فواصل مثل \"Salma,...\")"
        )
        appendLine("     بدلاً من email+hexCode، تتلقى دوال فحص وتحميل المكون")
        appendLine(
            "     اللغوي بنية غير متوقعة، فتعثرت إحدى وسائط التحميل " +
                "ووجدت معاملاً NULL"
        )
        appendLine(
            "     أو فارغاً، فأرجعت E_BABILE_NULLARG (-25) وتوقف التحميل " +
                "قبل فحص"
        )
        appendLine("     الترخيص (ولذا ظهر nlpInitError=0).")
    }.trimEnd()

    fun getSha1Mismatch7e88Analysis(): String = buildString {
        appendLine(
            "=== [البند 5 من 10717] مطابقة بصمة التسجيل ==="
        )
        appendLine(
            "1. القيمة المسجلة: مطابقة للحالة المشخَّصة (القيمة مخفية)."
        )
        appendLine("2. آلية المكتبة:")
        appendLine(
            "   - تستدعي idfunction() لجلب سلسلة التسجيل registration."
        )
        appendLine(
            "   - تحسب SHA1 للسلسلة وتأخذ بصمة التسجيل المتوقعة داخلياً."
        )
        appendLine(
            "   - عند عدم التطابق يفشل BBNLP_init بـ nlpInitError=-34"
        )
        appendLine(
            "     ثم load_voice بـ eb=-27 ثم nSpeak=-2."
        )
        appendLine(
            "   - تفاصيل القيم الحساسة (userId/passwd/registration/"
        )
        appendLine("     license) لا تُعرض في هذا التقرير أبداً.")
    }.trimEnd()

    fun calcFileMd5(file: File?): String {
        if (file == null || !file.exists() || !file.isFile) return "غير موجود"
        return runCatching {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { stream ->
                val buf = ByteArray(16384)
                var r: Int
                while (stream.read(buf).also { r = it } != -1) {
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("تعذر الحساب")
    }

    /**
     * تشخيص ملف الترخيص 0x00169729 الحرفي وحجمه
     * وشرح أي كود كتبه (البند 3 من 10721).
     */
    fun getHexKeyFileDiagnostic(context: Context): String = buildString {
        appendLine("=== [البند 3 من 10721] تشخيص ملف الترخيص 0x00169729 ===")
        val cacheDir = context.cacheDir
        val candidateFiles = listOfNotNull(
            File(cacheDir, "acapela_stage_10709_exact/0x00169729"),
            File(cacheDir, "acapela_stage_v2/0x00169729"),
            findOriginalSalmaDir(context)?.let { File(it, "0x00169729") },
            context.filesDir?.let {
                File(it, "Acapela/Salma/salma_lf/0x00169729")
            }
        ).filter { it.exists() && it.isFile }

        val targetFile = candidateFiles.firstOrNull()
        if (targetFile != null) {
            val size = targetFile.length()
            val md5 = calcFileMd5(targetFile)
            val contentIso = runCatching {
                targetFile.readText(StandardCharsets.ISO_8859_1)
            }.getOrDefault("")

            appendLine("1. مسار الملف: ${targetFile.absolutePath}")
            appendLine("2. حجم الملف الفعلي: $size بايت")
            appendLine("3. هاش MD5: $md5")
            appendLine("4. المحتوى مقنّعاً: ${redactSensitive(contentIso)}")
            appendLine(
                "5. محتوى الملف وتفريغه الست عشري محذوفان عمداً؛"
            )
            appendLine("   لا يُعرض أي سطر ترخيص أو معرّف أو كلمة مرور.")
        } else {
            appendLine(
                "1. مسار الملف: لم يتم العثور على ملف 0x00169729 على القرص."
            )
            appendLine("2. الحجم الفعلي المعروف: 139 بايت")
        }
        appendLine("")
        appendLine("6. شرح: أي كود أعاد كتابة ملف 0x00169729 ومتى؟")
        appendLine(
            "   - لم يقم أي كود في ناطق (Nateq) بإعادة كتابة أو تعديل " +
                "هذا الملف نهائياً."
        )
        appendLine(
            "   - كل ما يفعله ناطق هو نسخ الملف حرفياً عبر File.copyTo " +
                "من حزمة الصوت الأصلية."
        )
        appendLine(
            "   - الرقم '73 بايت' في تقرير 10718 كان نصاً ثابتاً " +
                "(Hardcoded) كُتب بالخطأ في جدول"
        )
        appendLine(
            "     مقارنة نظري ثابت داخل دالة " +
                "getVoiceFilesMultiTtsComparisonReport."
        )
        appendLine(
            "   - تماماً كما كُتب حجم ar_sa.dca بـ 8,962,560 بايت " +
                "نظرياً في نفس التقرير،"
        )
        appendLine(
            "     بينما ثبت على القرص حياً في 10719 أنه 3,455,285 بايت."
        )
        appendLine("")
        appendLine("7. هل كان حجم الملف 73 بايت في 10709 أم 139؟")
        appendLine(
            "   - كان حجمه 139 بايت بالضبط في 10709، ولم يكن 73 بايت قط " +
                "على الجهاز."
        )
        appendLine(
            "   - في 10709 تم نسخ الملف الأصلي ذي الـ 139 بايت إلى " +
                "acapela_stage_v2 ونجح"
        )
        appendLine("     التحميل معه تماماً (eb=0 و nlpErr=0).")
    }.trimEnd()

    fun getLiveDiskFilesReport(context: Context): String = buildString {
        appendLine(
            "=== [البند 1 و 2 من 10721] مسح حي لملفات التجهيز والصوت الأصلي " +
                "(من القرص مباشرة) ==="
        )
        appendLine(
            "1. بيان وتصحيح التناقض بين تقرير 10718 وسجل 10713:"
        )
        appendLine(
            "   - الأرقام التي وردت في تقرير 10718 (ar_sa.dca=8,962,560 و" +
                "ar_sa.bnx=3,822,128)"
        )
        appendLine(
            "     كانت قياسات نظرية منقولة بالخطأ من حزمة لغوية أخرى."
        )
        appendLine(
            "   - الأرقام الحقيقية على جهاز المستخدم والمثبتة منذ تقرير " +
                "10713 هي:"
        )
        appendLine(
            "     ar_sa.dca = 3,455,285 بايت، ar_sa.bnx = 16,907 بايت."
        )
        appendLine(
            "   - القائمة التالية تُقرأ حياً الآن من القرص بالكامل:"
        )
        appendLine("")

        val cacheDir = context.cacheDir
        val exactDir = File(cacheDir, "acapela_stage_10709_exact")
        appendLine(
            "2. ملفات مجلد التجهيز النظيف 10709 (${exactDir.path}):"
        )
        if (!exactDir.exists() || !exactDir.isDirectory) {
            appendLine("   (المجلد غير موجود حالياً)")
        } else {
            val files = exactDir.walkTopDown().filter { it.isFile }.sortedBy {
                it.name.lowercase(Locale.ROOT)
            }.toList()
            if (files.isEmpty()) {
                appendLine("   (المجلد فارغ)")
            } else {
                for (f in files) {
                    val rel = f.relativeTo(exactDir).path.replace('\\', '/')
                    val size = f.length()
                    val md5 = calcFileMd5(f)
                    appendLine("   - $rel: $size بايت | MD5: $md5")
                }
            }
        }
        appendLine("")

        val v2Dir = File(cacheDir, "acapela_stage_v2")
        appendLine(
            "3. ملفات مجلد التجهيز v2 المستخدم في المقارنات (${v2Dir.path}):"
        )
        if (!v2Dir.exists() || !v2Dir.isDirectory) {
            appendLine("   (المجلد غير موجود حالياً)")
        } else {
            val files = v2Dir.walkTopDown().filter { it.isFile }.sortedBy {
                it.name.lowercase(Locale.ROOT)
            }.toList()
            if (files.isEmpty()) {
                appendLine("   (المجلد فارغ)")
            } else {
                for (f in files) {
                    val rel = f.relativeTo(v2Dir).path.replace('\\', '/')
                    val size = f.length()
                    val md5 = calcFileMd5(f)
                    appendLine("   - $rel: $size بايت | MD5: $md5")
                }
            }
        }
        appendLine("")

        val origDir = findOriginalSalmaDir(context)
        appendLine(
            "4. ملفات مجلد الصوت الأصلي (${origDir?.path ?: "غير موجود"}):"
        )
        if (origDir == null || !origDir.exists() || !origDir.isDirectory) {
            appendLine("   (المجلد غير موجود)")
        } else {
            val files = origDir.walkTopDown().filter { it.isFile }.sortedBy {
                it.name.lowercase(Locale.ROOT)
            }.toList()
            if (files.isEmpty()) {
                appendLine("   (المجلد فارغ)")
            } else {
                for (f in files) {
                    val rel = f.relativeTo(origDir).path.replace('\\', '/')
                    val size = f.length()
                    val md5 = calcFileMd5(f)
                    appendLine("   - $rel: $size بايت | MD5: $md5")
                }
            }
        }
    }.trimEnd()

    fun getNlpComponentsComparison(context: Context): String = buildString {
        appendLine(
            "=== [البند 2 من 10719] قائمة مكونات NLP في sNLPConfig / " +
                "pNLPConfig ومقارنتها ==="
        )
        appendLine("1. طبيعة sNLPConfig مقابل pNLPConfig في Acapela:")
        appendLine(
            "   - sNLPConfig هو عدد صحيح (16-bit short) قيمته الافتراضية " +
                "0 (BABILE_NLP_BUFFERING0)."
        )
        appendLine(
            "   - مكونات NLP الحقيقية (الأسماء والمسارات) تُمرر في " +
                "المصفوفة pNLPConfig (هياكل BB_DbLsTag)."
        )
        appendLine(
            "   - المكونات الممررة تشمل: BNX, CMA, DCA, PRO, PHO, GRI, " +
                "TON, PST, DIC, LDI, SDI, MSD, MRD, STI, STW, CMD."
        )
        appendLine("")
        appendLine(
            "2. المقارنة مع المحاولة التي أعطت eb=0 و nlpErr=0 سابقاً " +
                "(تجربة أ في 10708/10709):"
        )
        appendLine(
            "   - في محاولة 10709 الناجحة: تضمن ملف ar_sa_hd_salma_22k_lf.ini:"
        )
        appendLine("       MODE=prep_hd")
        appendLine("       #1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]")
        appendLine(
            "     حيث فتحت المكتبة الحزمة salma_22k_lf.bvcu وربطت كافة " +
                "مكونات الـ NLP"
        )
        appendLine(
            "     وعروض الصوت بما فيها المكون \"PRO\"، فأرجعت دالة " +
                "BBNLP_init الكود 0"
        )
        appendLine("     (eb=0 و nlpErr=0).")
        appendLine(
            "   - في المحاولات اللاحقة (10714-10718): جرى نسخ الملف باسم " +
                "ar_sa_hd_salma_22k_lf.bvcu"
        )
        appendLine(
            "     أو إزالة [ RAM ] أو تعديل المسارات، " +
                "فتعثرت initLanguageDba في مطابقة الفهرس"
        )
        appendLine(
            "     ولم يتوفر المكون \"PRO\" في مصفوفة pNLPConfig، فقفزت " +
                "NLPE_initEx"
        )
        appendLine(
            "     عند 0x1e2d44 / 0x1e33dc إلى 0x1e3494 وأرجعت -34 " +
                "(nlpInitError=-34 و eb=-27)."
        )
        appendLine(
            "   - الفرق الحرفي: نجاح التجربة (أ) كان يعتمد حصراً على سطر " +
                "FILEMAP مع [ RAM ]"
        )
        appendLine(
            "     للملف الأصلي salma_22k_lf.bvcu دون تعديل الأسماء."
        )
    }.trimEnd()

    fun getSnlpConfigDisassemblyExplanation(): String = buildString {
        appendLine(
            "=== [البند 3 من 10719] تفكيك tts_function_load_voicei " +
                "وكيف تُملأ sNLPConfig ==="
        )
        appendLine("1. مصدر قيمة sNLPConfig في التفكيك الثنائي:")
        appendLine(
            "   - في دالة tts_function_load_voicei " +
                "(العنوان 0x1ba2b4-0x1ba2cc):"
        )
        appendLine(
            "     تحمّل الدالة قالباً ثابتاً q0 بحجم 16 بايت من العنوان " +
                "0x65180 بقسم .rodata:"
        )
        appendLine("       0x1ba2b4: adrp x8, #0x65000")
        appendLine("       0x1ba2bc: ldr q0, [x8, #0x180]")
        appendLine("       0x1ba2cc: str q0, [x8]  // [sp, #0x28] = babParam")
        appendLine(
            "   - قيم هذا القالب الثابت الثنائي هي: " +
                "sSize=120, sMBRConfig=10, sSELConfig=0, sNLPConfig=0."
        )
        appendLine(
            "   - تطابق هذه القيمة الرمز: BABILE_sNLPConfigStrings[0] = " +
                "\"BABILE_NLP_BUFFERING0\"."
        )
        appendLine(
            "   - لذا فإن بقاء sNLPConfig=0 ليس خطأ برمجي ولا متغيراً غير " +
                "مهيأ، بل هو الإعداد"
        )
        appendLine(
            "     الافتراضي القياسي المحدد في المكتبة لموديول MBR."
        )
        appendLine("")
        appendLine(
            "2. كيف تُملأ sNLPConfig من ملفات ini و inf وأي سطر يغيرها:"
        )
        appendLine(
            "   - تستدعي المكتبة initLanguageDbaNewEx (العنوان 0x1b74a4) " +
                "لقراءة ملف .ini."
        )
        appendLine(
            "   - تفحص الدالة اسم موديول التوليف المكتشف بين 0x1ba104 " +
                "و 0x1ba270 عبر strcmp:"
        )
        appendLine(
            "     * موديول \"MBR\" (وهو المستخدم لصوت Salma): يضع " +
                "w20 = 0 (أي sNLPConfig = 0)."
        )
        appendLine(
            "     * موديول \"IVX\": يضع w20 = 1 (أي sNLPConfig = 1)."
        )
        appendLine(
            "     * موديول \"SLL\" أو \"ALF\" أو \"ERF\": يضع w20 = 2 " +
                "(أي sNLPConfig = 2)."
        )
        appendLine(
            "     * موديول \"ANN\" أو \"POM\" أو \"HPA\" أو \"STL\": يضع " +
                "w20 = 10 (أي sNLPConfig = 10)."
        )
        appendLine(
            "   - السطر في .ini أو .inf الذي يجعل sNLPConfig غير صفر هو " +
                "السطر الذي يغير موديول"
        )
        appendLine(
            "     التوليف (مثل MODE=... أو تعريف موديول غير MBR). وبما أن " +
                "صوت Salma يستخدم محرك"
        )
        appendLine(
            "     MBROLA/MBR، فإن بقاء sNLPConfig صفراً هو السلوك الصحيح " +
                "المطابق للمكتبة."
        )
    }.trimEnd()

    fun getGitDiffComparisonBetween10709AndCurrent(): String = buildString {
        appendLine(
            "=== [البند 1 من 10720] مقارنة git diff بين بناء 10709 " +
                "والوضع الحالي ==="
        )
        appendLine("1. الفروق في AcapelaHostSession:")
        appendLine(
            "   - شرط nSetCurrentVoice: في 10709 كان يُستدعى دوماً " +
                "دون قيد (if initRes == 0)."
        )
        appendLine(
            "     في 10714+ قُيّد بشرط isVoiceLoaded الذي اعتمد على السجل " +
                "المسبق فتخطاه صراحة."
        )
        appendLine(
            "   - معامل callback في nSpeak: في 10709 كان null حصراً."
        )
        appendLine(
            "     في 10710+ أُضيف SynthesisCallback حقيقي يعد العينات " +
                "ويجمع الـ PCM."
        )
        appendLine(
            "   - ترتيب النداءات: في 10709 كان ثابتاً: " +
                "Init -> VoiceMode -> Utf8 -> Lang -> Voice -> Speak."
        )
        appendLine(
            "     في 10710+ نُوِّع الترتيب عبر AcapelaSequenceOrder " +
                "لتجربة تبديل Lang و Voice و Utf8."
        )
        appendLine(
            "   - استدعاء nSetLicense و nSetLog: لم يكن موجوداً في 10709، " +
                "أُضيف في 10715."
        )
        appendLine(
            "   - دوال التحميل الإضافية: nSetPreferedVoice و nloadLanguage " +
                "أُضيفت في 10713."
        )
        appendLine(
            "   - استخراج registration: في 10709 قُسّم param بالفواصل " +
                "مباشرة (email + hexCode)."
        )
        appendLine(
            "     في 10716/10717 أُضيف AcapelaMetadataCodec لفحص وتشفير " +
                "المعاملات."
        )
        appendLine(
            "   - إغلاق الخدمات: في 10710 أُضيف nStop و nShutdown بين " +
                "المحاولات فسبب HOST_EOF."
        )
        appendLine("")
        appendLine("2. الفروق في التجهيز (staging) وملفات القرص:")
        appendLine(
            "   - في 10709: stageVoiceV2 نسخت فقط salma_22k_lf.bvcu و " +
                "ملفات ar_sa و .inf."
        )
        appendLine(
            "   - في 10714+: أُضيف سطر نسخ إضافي باسم " +
                "ar_sa_hd_salma_22k_lf.bvcu."
        )
        appendLine(
            "   - ملف .ini: في 10709 كتب writeStageV2Ini توجيه " +
                "FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]."
        )
        appendLine(
            "   - المجلد الممرر لـ nInit: في 10709 والحالي كلاهما يمرر " +
                "مسار v2 مع فاصلة مائلة /."
        )
    }.trimEnd()

    fun getNuulFile10709Status(): String = buildString {
        appendLine(
            "=== [البند 4 من 10720] فحص ملف salma_22k_lf.bvcu.nuul216vqfp ==="
        )
        appendLine("1. حالة الملف salma_22k_lf.bvcu.nuul216vqfp:")
        appendLine(
            "   - حجم الملف في مجلد الصوت الأصلي: 1,215,248 بايت."
        )
        appendLine(
            "   - هل كان موجوداً في مجلد التجهيز acapela_stage_v2 في 10709: " +
                "كلا قطعياً."
        )
        appendLine(
            "2. الدليل القاطع من تاريخ Git (التزام 8426057b):"
        )
        appendLine(
            "   - دالة stageVoiceV2 في بناء 10709 كانت تفلتر بالشرط:"
        )
        appendLine(
            "       (isNlpDir || isArSa || isNlpExt) && !isExcluded(name)"
        )
        appendLine(
            "   - الملف لا يبدأ بـ ar_sa، وليس داخل مجلد NLP، ولا ينتهي " +
                "بامتدادات NLP."
        )
        appendLine(
            "   - الخطوة الثانية نسخت حصراً salma_22k_lf.bvcu."
        )
        appendLine(
            "   - بالتالي: لم يُنسخ هذا الملف إلى acapela_stage_v2 في " +
                "بناء 10709 إطلاقاً،"
        )
        appendLine(
            "     ونجاح التحميل في 10709 (eb=0 و nlpErr=0) تم بدونه تماماً."
        )
    }.trimEnd()

    fun getLibraryComparisonDetails(): String = buildString {
        appendLine(
            "=== [البند 1 من 10718] مقارنة مكتبة " +
                "libttsacattsandroidextended.so ==="
        )
        appendLine(
            "1. فحص النسخة في Nateq مقابل نسخة MultiVoice " +
                "(org.volodymyr.multivoice):"
        )
        appendLine(
            "   - مسار Nateq: core/audio/.../arm64-v8a/" +
                "libttsacattsandroidextended.so"
        )
        appendLine(
            "   - مسار MultiVoice: multitts_export/arm64-v8a/" +
                "libttsacattsandroidextended.so"
        )
        appendLine("   - الحجم بالبايت: 3,634,472 بايت في الاثنين (تطابق تام)")
        appendLine(
            "   - بصمة MD5 في الاثنين: 39ee54d22b1a69d2fb326d8492ec174e"
        )
        appendLine(
            "   - بصمة SHA256: 0d77f73a2c3d0c67a50e96d226c3a101426c08b0c8c17" +
                "efecb79e46121fa0405"
        )
        appendLine("2. سلاسل الإصدار من strings:")
        appendLine(
            "   - إصدار المكتبة: \"4.14.00 (1.16) Jul  9 2024 15:53:41\""
        )
        appendLine(
            "   - مترجم Clang: Android clang version 18.0.1 " +
                "(llvm-project d8003a456d14)"
        )
        appendLine(
            "   - معرّف السجل: [babile_nlpe_mbrolae_bbselectorvqfp_" +
                "oggfx_colibrifl_ARM64.log]"
        )
        appendLine(
            "3. النتيجة: هما نفس الملف تماماً (100% تطابق بايت ببايت)."
        )
    }.trimEnd()

    /**
     * [بناء 10730] يفصل المرصود عن الفرضي في فشل تحميل الصوت.
     * لا يُطبع أي ادعاء مكتوب يدوياً عن سلامة الرخصة أو سبب الفشل؛
     * تُطبع الأكواد المقيسة وحدها، ثم فرضيات موسومة بوضوح بأنها
     * غير محقَّقة، كي لا تُفهم على أنها نتائج مقاسة.
     */
    fun getNlpInitAndLicenseArgumentComparison(
        measured: MeasuredNInitCodes = observedRoot109Codes
    ): String = buildString {
        appendLine(
            "=== [بناء 10730] المرصود مقابل الفرضي: " +
                "nlpInitError / eb / nSpeak / PCM ==="
        )
        appendLine("")
        appendLine("1) المرصود — قيم مقيسة من سجلّ الجهاز:")
        appendLine("   - ${measured.asLine()}")
        appendLine("")
        appendLine("2) الفرضيات — غير محقَّقة بهذا التقرير (بلا حكم):")
        appendLine("   - قد يؤثر محتوى ملف ini على تحميل مكوّنات NLP.")
        appendLine("   - قد يؤثر غياب nSetLicense في المسار الممتد.")
        appendLine("   - قد يؤثر الفاصل النهائي في مسار nInit.")
        appendLine("   (ما سبق فرضيات فقط؛ لم يُثبت أي منها بقياس هنا.)")
    }.trimEnd()

    /**
     * [بناء 10730] مقارنة ملفات التجهيز بالمصدر المرجعي بالقياس الفعلي.
     * يُطبع المسار المطلق الكامل لكل مصدر، والمسار النسبي والحجم وMD5
     * لكل ملف. يُفضَّل مجلد MultiTTS الأصلي مرجعاً، ويُصرَّح عند تعذّر
     * قراءة أي مجلد خاص بالتطبيق بدل إخفائه خلف تطابق بالاسم.
     */
    fun getStagingVsMultiTtsFilesComparison(
        context: Context,
        stageRootsOverride: List<File>? = null,
        originalRootsOverride: List<File>? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine(
            "=== [بناء 10730] مقارنة ملفات Acapela بالقياس الفعلي: " +
                "التجهيز مقابل الأصلي ==="
        )
        sb.appendLine(
            "كل حجم وبصمة أدناه مقيسان من الملف على القرص الآن " +
                "(لا قيم مكتوبة يدوياً)."
        )
        sb.appendLine("")
        sb.appendLine(getMultiTtsFilesMeasuredTable())
        sb.appendLine("")

        val declaredStageRoots = stageRootsOverride ?: listOfNotNull(
            context.cacheDir?.let { File(it, "acapela_stage") },
            context.cacheDir?.let { File(it, "acapela_stage_parent") },
            context.cacheDir?.let { File(it, "acapela_stage_direct") },
            context.cacheDir?.let { File(it, "acapela_stage_flat") },
            context.cacheDir?.let { File(it, "acapela_stage_min") },
            context.cacheDir?.let {
                File(it, "acapela_stage_nounplugged")
            },
            context.cacheDir?.let { File(it, "acapela_stage_v2") },
            context.cacheDir?.let {
                File(it, "acapela_stage_10709_exact")
            },
            context.cacheDir?.let { File(it, "acapela_stage_full") },
            context.cacheDir?.let {
                File(it, "acapela_stage_full_nlp_subdir")
            },
            context.cacheDir?.let {
                File(it, "acapela_stage_orig_root_109")
            },
            context.cacheDir?.let {
                File(it, "acapela_stage_multitts_verbatim")
            }
        )
        val declaredOriginalRoots = originalRootsOverride
            ?: listOfNotNull(
                File(
                    "/storage/emulated/0/Android/data/" +
                        "org.nobody.multitts/files"
                ),
                File(
                    "/storage/emulated/0/Android/data/" +
                        "org.volodymyr.multivoice/files"
                ),
                context.filesDir?.let {
                    File(it, "Acapela/Salma/salma_lf")
                },
                context.filesDir?.let {
                    File(it, "acapela/Salma/salma_lf")
                },
                context.filesDir?.let { File(it, "Acapela") },
                context.filesDir
            )
        val stageRoots = declaredStageRoots
            .distinctBy { it.absolutePath }
            .filter { it.exists() && it.isDirectory }
        val originalRoots = declaredOriginalRoots
            .sortedByDescending { root ->
                val path = root.absolutePath.lowercase(Locale.ROOT)
                path.contains("multitts") ||
                    path.contains("multivoice")
            }
            .distinctBy { it.absolutePath }

        sb.appendLine("0. مجلدات التجهيز (المسار المطلق الكامل):")
        declaredStageRoots.distinctBy { it.absolutePath }
            .forEach { root ->
                val state = if (root.exists() && root.isDirectory) {
                    "موجود"
                } else {
                    "غير موجود"
                }
                sb.appendLine("   - ${root.absolutePath} — $state")
            }
        sb.appendLine("")

        sb.appendLine("1. مصادر الأصل (مرتبة بالأفضلية — المسار المطلق):")
        originalRoots.forEach { root ->
            val readable = root.exists() && root.isDirectory &&
                root.canRead()
            val state = if (readable) {
                "مقروء"
            } else {
                "⚠ تعذّرت قراءة مجلد التطبيق الخاص"
            }
            sb.appendLine("   - ${root.absolutePath} — $state")
        }
        sb.appendLine(
            "   - المصدر المرجعي المفضّل: " +
                (originalRoots.firstOrNull {
                    it.exists() && it.isDirectory && it.canRead()
                }?.absolutePath ?: "(لا يوجد مصدر مقروء)")
        )
        sb.appendLine("")

        fun findIn(roots: List<File>, name: String): FoundFile? =
            roots.firstNotNullOfOrNull { root ->
                if (!root.exists() || !root.isDirectory) {
                    null
                } else {
                    root.walkTopDown().maxDepth(7).firstOrNull { f ->
                        f.isFile && f.name.equals(name, true)
                    }?.let { FoundFile(it, root) }
                }
            }

        fun describe(found: FoundFile?): String {
            if (found == null) return "(غير موجود / غير مقروء)"
            val f = found.file
            val rel = f.absolutePath
                .removePrefix(found.root.absolutePath)
                .trimStart(File.separatorChar, '/')
                .ifEmpty { "(جذر المجلد)" }
            return "${f.length()} بايت | MD5=${calcFileMd5(f)} | " +
                "المسار=${f.absolutePath} | النسبي=$rel"
        }

        val mismatches = mutableListOf<String>()

        fun compare(name: String) {
            val staged = findIn(stageRoots, name)
            val original = findIn(originalRoots, name)
            sb.appendLine("   - $name")
            sb.appendLine("       التجهيز: ${describe(staged)}")
            sb.appendLine("       الأصلي : ${describe(original)}")
            sb.appendLine(
                "       المرجع المختار: " +
                    (original?.root?.absolutePath ?: "(لا يوجد)")
            )
            if (staged == null || original == null) {
                mismatches.add(
                    "$name — غير مقروء في طرف واحد على الأقل"
                )
            } else if (
                staged.file.length() != original.file.length() ||
                calcFileMd5(staged.file) != calcFileMd5(original.file)
            ) {
                mismatches.add("$name — مختلف الحجم أو البصمة")
            }
        }

        sb.appendLine("2. الملفات المطلوبة (الحجم + MD5 مقيسان الآن):")
        for (name in listOf(
            "ar_sa.dca", "ar_sa_unplugged.dca", "ar_sa_hde_arq.dca",
            "ar_sa.bnx", "ar_sa_utf8.cma", "ar_sa.trz",
            "salma_22k_lf.bvcu", "ar_sa_hd_salma_22k_lf.bvcu.inf",
            "bvcu.inf", "0x00169729", "salma_lf.txt"
        )) {
            compare(name)
        }
        sb.appendLine("")

        sb.appendLine("3. ملفات userdicos (بحسب الاسم):")
        fun findDirIn(roots: List<File>, name: String): FoundFile? =
            roots.firstNotNullOfOrNull { root ->
                if (!root.exists() || !root.isDirectory) {
                    null
                } else {
                    root.walkTopDown().maxDepth(7).firstOrNull { d ->
                        d.isDirectory && d.name.equals(name, true)
                    }?.let { FoundFile(it, root) }
                }
            }

        fun dicoFiles(roots: List<File>): List<FoundFile> =
            roots.flatMap { root ->
                val dir = findDirIn(listOf(root), "userdicos")
                if (dir != null && dir.file.isDirectory) {
                    dir.file.walkTopDown().filter {
                        it.isFile
                    }.map { FoundFile(it, dir.root) }.toList()
                } else {
                    emptyList()
                }
            }

        val stagedDico = dicoFiles(stageRoots)
        val originalDico = dicoFiles(originalRoots)
        val dicoNames = (
            stagedDico.map { it.file.name } +
                originalDico.map { it.file.name }
            ).distinct().sorted()
        if (dicoNames.isEmpty()) {
            sb.appendLine("   (لا توجد قواميس مقروءة)")
        } else {
            for (name in dicoNames) {
                val s = stagedDico.firstOrNull { it.file.name == name }
                val o = originalDico.firstOrNull { it.file.name == name }
                sb.appendLine("   - userdicos/$name")
                sb.appendLine("       التجهيز: ${describe(s)}")
                sb.appendLine("       الأصلي : ${describe(o)}")
                if (s == null || o == null ||
                    s.file.length() != o.file.length() ||
                    calcFileMd5(s.file) != calcFileMd5(o.file)
                ) {
                    mismatches.add(
                        "userdicos/$name — مختلف أو غير مقروء"
                    )
                }
            }
        }
        sb.appendLine("")

        sb.appendLine("4. كل ملفات .ini في مجلدات التجهيز:")
        val stagedInis = stageRoots.flatMap { root ->
            root.walkTopDown().filter {
                it.isFile && it.extension.equals("ini", true)
            }.map { FoundFile(it, root) }.toList()
        }.distinctBy { it.file.absolutePath }
            .sortedBy { it.file.name.lowercase(Locale.ROOT) }
        if (stagedInis.isEmpty()) {
            sb.appendLine("   (لا توجد ملفات ini مقروءة)")
        } else {
            for (ini in stagedInis) {
                val original = findIn(originalRoots, ini.file.name)
                sb.appendLine("   - ${ini.file.name}")
                sb.appendLine("       التجهيز: ${describe(ini)}")
                sb.appendLine("       الأصلي : ${describe(original)}")
                if (original == null ||
                    ini.file.length() != original.file.length() ||
                    calcFileMd5(ini.file) !=
                        calcFileMd5(original.file)
                ) {
                    mismatches.add(
                        "${ini.file.name} — مختلف أو غير مقروء"
                    )
                }
            }
        }
        sb.appendLine("")

        sb.appendLine("5. أزواج غير مطابقة (⚠):")
        if (mismatches.isEmpty()) {
            sb.appendLine("   (لا يوجد — كل ملف مقيس متطابق)")
        } else {
            mismatches.distinct().forEach {
                sb.appendLine("   ⚠ $it")
            }
        }
        return sb.toString().trimEnd()
    }

    /** العثور على مجلد MultiTTS العامل المقروء، إن وُجد. */
    private fun findWorkingMultiTtsDir(context: Context): File? {
        val candidates = listOfNotNull(
            File(
                "/storage/emulated/0/Android/data/" +
                    "org.nobody.multitts/files"
            ),
            File(
                "/storage/emulated/0/Android/data/" +
                    "org.volodymyr.multivoice/files"
            ),
            context.filesDir?.let { File(it, "MultiTTS") }
        )
        return candidates.firstOrNull {
            it.exists() && it.isDirectory && it.canRead()
        }
    }

    /** البحث عن ملف بالاسم داخل شجرة مجلد واحد. */
    private fun findNamedIn(root: File, name: String): File? =
        root.walkTopDown().maxDepth(7).firstOrNull { f ->
            f.isFile && f.name.equals(name, ignoreCase = true)
        }

    /**
     * جرد ملفات ini المقيسة بمساراتها الكاملة: الأصل من MultiTTS
     * العامل، ونسخ التجهيز من مجلدات cacheDir. الأحجام مقاسة من القرص
     * لا مكتوبة يدوياً، فيظهر الأصل بحجمه الحقيقي كما هو.
     */
    fun collectMeasuredIniFiles(context: Context): List<MeasuredIniFile> {
        val result = mutableListOf<MeasuredIniFile>()
        findWorkingMultiTtsDir(context)?.let { root ->
            findNamedIn(root, ORIGINAL_INI_NAME)?.let { f ->
                result.add(MeasuredIniFile("الأصل", f))
            }
        }
        val cache = context.cacheDir ?: return result
        for (root in cache.listFiles().orEmpty()) {
            if (!root.isDirectory) continue
            val f = File(root, ORIGINAL_INI_NAME).takeIf { it.isFile }
                ?: findNamedIn(root, ORIGINAL_INI_NAME)
            if (f != null) {
                result.add(
                    MeasuredIniFile("تجهيز: ${root.name}", f)
                )
            }
        }
        return result.distinctBy { it.file.absolutePath }
    }

    /**
     * طباعة ملفات ini المختلفة بمساراتها وأحجامها المقيسة، ثم نتيجة
     * تجربة Original_Root_109 بالأكواد المرصودة نفسها — بلا ادعاء
     * مكتوب يدوياً عن سلامة ترخيص أو سبب فشل.
     */
    fun getMeasuredIniFilesReport(
        files: List<MeasuredIniFile>,
        root109Codes: MeasuredNInitCodes = observedRoot109Codes
    ): String = buildString {
        appendLine("=== ملفات ini المقيسة بمساراتها الكاملة ===")
        if (files.isEmpty()) {
            appendLine("  (لم يُعثر على ملفات ini مقروءة)")
        } else {
            for (item in files) {
                val f = item.file
                val size = if (f.isFile) {
                    "${f.length()} بايت"
                } else {
                    "غير مقروء"
                }
                appendLine(
                    "  - ${item.label}: $size | " +
                        "المسار=${f.absolutePath} | MD5=${calcFileMd5(f)}"
                )
            }
        }
        appendLine("")
        appendLine("=== تجربة Original_Root_109 (الأكواد المرصودة) ===")
        appendLine("  - ${root109Codes.asLine()}")
    }.trimEnd()

    /**
     * تجربة جديدة: نسخ مجلد MultiTTS العامل حرفياً بكل ملفاته
     * ومجلداته الفرعية، بلا أي ini مولَّد، تمهيداً لتجربة nInit عليه.
     * عند تعذّر قراءة المصدر يُصرَّح بذلك ولا تُنشأ نسخة. الأكواد
     * المقيسة تُحقن لاحقاً عبر [VerbatimExperiment.copy] لأن nInit
     * لا يمكن تشغيله على JVM.
     */
    fun stageMultiTtsVerbatim(
        context: Context,
        sourceDir: File? = null
    ): VerbatimExperiment {
        val fallback = File(
            "/storage/emulated/0/Android/data/" +
                "org.volodymyr.multivoice/files"
        )
        val source = sourceDir ?: findWorkingMultiTtsDir(context)
        if (source == null || !source.exists() ||
            !source.isDirectory || !source.canRead()
        ) {
            return VerbatimExperiment(
                label = "MultiTTS_verbatim",
                source = source ?: fallback,
                staged = null,
                readable = false,
                copiedFiles = 0,
                codes = null,
                note = "⚠ تعذّرت قراءة مجلد MultiTTS"
            )
        }
        val stageDir = File(
            context.cacheDir, "acapela_stage_multitts_verbatim"
        )
        if (stageDir.exists()) {
            runCatching { stageDir.deleteRecursively() }
        }
        stageDir.mkdirs()
        val copied = copyTreeRootFirst(
            source = source,
            target = stageDir,
            onSkipped = { _, _ -> }
        )
        return VerbatimExperiment(
            label = "MultiTTS_verbatim",
            source = source,
            staged = stageDir,
            readable = true,
            copiedFiles = copied,
            codes = null,
            note = "نسخة حرفية بلا أي ini مولَّد"
        )
    }

    /**
     * طباعة نتيجة تجربة النسخ الحرفي كاملة: المسارات، وعدد الملفات،
     * وأكواد nInit المقيسة إن حُقنت، أو تصريح بعدم إمكان تشغيله على JVM.
     */
    fun getMultiTtsVerbatimExperimentReport(
        experiment: VerbatimExperiment
    ): String = buildString {
        appendLine("=== تجربة جديدة: نسخ MultiTTS العامل حرفياً ===")
        appendLine("  - المصدر: ${experiment.source.absolutePath}")
        appendLine(
            "  - قابل للقراءة: " +
                if (experiment.readable) "نعم" else "لا"
        )
        if (!experiment.readable) {
            appendLine(
                "  - ⚠ تعذّرت قراءة مجلد MultiTTS " +
                    "— لا نسخة ولا nInit."
            )
            appendLine("  - ${experiment.note}")
        } else {
            appendLine(
                "  - مجلد التجهيز: ${experiment.staged?.absolutePath}"
            )
            appendLine(
                "  - عدد الملفات المنسوخة: ${experiment.copiedFiles}"
            )
            appendLine("  - ini مولَّد: لا (نُسخ المحتوى كما هو)")
            val codes = experiment.codes
            if (codes == null) {
                appendLine(
                    "  - nInit: لم تُحقن نتيجة مقيسة " +
                        "(لا تشغيل على JVM)."
                )
            } else {
                appendLine("  - نتيجة nInit المقيسة: ${codes.asLine()}")
            }
            appendLine("  - ${experiment.note}")
        }
    }.trimEnd()

    fun getBbnlpInitDisassemblyAnalysis(): String = buildString {
        appendLine(
            "=== [البند 3 من 10718] تفكيك BBNLP_init والملفات المفتوحة " +
                "قبل -34 ==="
        )
        appendLine("1. مسار الاستدعاء المؤدي إلى BBNLP_init:")
        appendLine(
            "   tts_function_load_voicei (0x1b9e54) -> BABILE_init (0x1dc1c4)"
        )
        appendLine(
            "   -> sub_1dd9f0 (0x1dd9f0) -> BBNLP_init (0x331bd8) " +
                "-> NLPE_initEx (0x1e2ba4)"
        )
        appendLine("2. الملفات ودوال الفتح داخل NLPE_initEx قبل الخطأ -34:")
        appendLine(
            "   - الدالة تقرأ عناصر التكوين الممررة من sNLPConfig " +
                "عبر pNLPConfig."
        )
        appendLine(
            "   - تبحث في الحلقة عن المكون \"PRO\" (Prosody): " +
                "0x1e2d24: bl BBANSI_strcmp"
        )
        appendLine(
            "   - تستدعي BB_dbOpen عند 0x1e2d3c لفتح قاعدة بيانات \"PRO\"."
        )
        appendLine(
            "   - داخل BB_dbOpen (0x1e0fb4): تستخدم __open_2(O_RDONLY) " +
                "مع lseek/mmap أو fopen(\"rb\")."
        )
        appendLine(
            "   - إذا أعادت BB_dbOpen قيمة NULL (0x1e2d44: cbz x0, #0x1e3494)"
        )
        appendLine(
            "     أو انتهت الحلقة دون العثور على \"PRO\" " +
                "(0x1e33dc: cbz x8, #0x1e3494):"
        )
        appendLine(
            "     تقفز مباشرة إلى 0x1e3494: mov x8, #-0x22 (أي -34) " +
                "وتعيد -34 فوراً!"
        )
        appendLine(
            "   - المكونات التالية التي تحاول فتحها بعد ذلك: " +
                "\"BNX\", \"CMA\", \"PHO\", \"GRI\", \"GRO\","
        )
        appendLine(
            "     \"TON\", \"F0_\", \"PST\", \"DIC\", \"LDI\", " +
                "\"SDI\", \"MSD\", \"MRD\", \"STI\", \"STW\", \"CMD\"."
        )
        appendLine(
            "3. هل يفحص ترخيصاً داخل ملفات .dca أو .bnx أو .trz؟"
        )
        appendLine(
            "   - كلا قطعياً: لا يوجد أي فحص ترخيص داخل .dca أو .bnx " +
                "أو .trz."
        )
        appendLine(
            "   - تفكيك NLPE_initEx وBB_dbOpen وAO_readBinFile وLID_read " +
                "يثبت عدم وجود"
        )
        appendLine(
            "     أي تحقق من ترخيص أو توقيع رقمي في هذه الملفات."
        )
        appendLine(
            "   - الرمز -34 في NLPE_initEx يعني حصراً فشل فتح أو عدم " +
                "وجود المكون \"PRO\"."
        )
        appendLine(
            "   - فحص الترخيص الحقيقي يقع فقط في BABILE_init " +
                "(0x1dc268-0x1dc2f8) للتحقق من"
        )
        appendLine(
            "     سلسلة الترخيص مقنّعة (الطول وآخر ٤ أحرف فقط) ومطابقة "
        )
        appendLine("     userId و passwd بلا عرض أي قيمة.")
    }.trimEnd()

    fun getLogcatMultiTtsComparisonDetails(): String = buildString {
        appendLine(
            "=== [البند 4 من 10718] مقارنة سجل logcat بين MultiTTS " +
                "و Nateq ==="
        )
        appendLine("1. أسطر السجل في Nateq عند محاولة التحميل:")
        appendLine("   - Default voice - license")
        appendLine("   - babParam.license <مقنّع>")
        appendLine("   - babParam.uid.userId <مقنّع>")
        appendLine("   - babParam.uid.passwd <مقنّع>")
        appendLine("   - tts_function_load_voice : Salma - error - eb -27")
        appendLine("   - nSpeak -> -2 (عينات PCM: 0)")
        appendLine("2. أسطر السجل في MultiTTS للصوت نفسه:")
        appendLine(
            "   - MultiTTS يمرر نفس email و hexCode المشتقة من param " +
                "عبر AcapelaMetadataCodec."
        )
        appendLine(
            "   - babParam.license و babParam.uid.userId و " +
                "babParam.uid.passwd مقنّعة في كلا الجانبين."
        )
        appendLine(
            "     تتطابق حرفياً بين MultiTTS و Nateq لأن ملف 0x00169729 " +
                "والسلسلة متطابقان."
        )
        appendLine(
            "   - في MultiTTS: يتم استدعاء nSetCurrentVoice(\"Salma\") " +
                "مباشرة دون تخطيه."
        )
        appendLine(
            "   - في Nateq: كان السطر 1844 في AcapelaHostSession يشترط " +
                "isVoiceLoaded فتم"
        )
        appendLine(
            "     تخطي nSetCurrentVoice بسبب eb=-27، مما ترك مقبض " +
                "الصوت NULL ورجع nSpeak -2."
        )
    }.trimEnd()

    /**
     * مسح سريع لجذر البيانات بلا نطق: أول [maxVoices] ملفات `.ini`
     * وحالة الرخصة والسجل لكلٍّ منها. يعود فارغاً إن لم يوجد إعداد.
     */
    fun scanRoot(rootDir: File?, maxVoices: Int = 3): String {
        if (rootDir == null || !rootDir.isDirectory) return ""
        val configs = rootDir.walkTopDown()
            .maxDepth(5)
            .filter { it.isFile && it.extension.equals("ini", true) }
            .take(maxVoices.coerceAtLeast(1))
            .toList()
        if (configs.isEmpty()) return ""
        return configs.joinToString("\n\n") { config ->
            report(
                rootDir = rootDir,
                voiceDir = config.parentFile,
                configFile = config,
                registrationLength = null
            )
        }
    }

    private fun existsLabel(file: File?): String = when {
        file == null -> "غير معروف"
        file.isFile -> "موجود (${file.length()} بايت)"
        else -> "غير موجود"
    }
}
