package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

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

    /** حدّ الملفات المعروضة في الجرد (الباقي يُعدّ لا يُسرد). */
    const val MAX_FILES = 24

    /** عدد أسطر ذيل السجل المعروضة. */
    const val MAX_TAIL_LINES = 15

    /** حدّ أحرف السطر الواحد في الذيل. */
    const val MAX_LINE_CHARS = 200

    /** اسم سجل المحرك الذي تكتبه المكتبة الأصلية. */
    const val LOG_FILE_NAME = "logfile.txt"

    /** ملف الرخصة البديل في جذر البيانات (يقرأه `check_license`). */
    private const val TEMPORARY_FILE_NAME = ".temporary"

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
        val regVal = if (yamlExists && foundYaml != null) {
            AcapelaVoiceManifest.extractRegistrationFromParam(foundYaml.second)
        } else if (yamlExists) {
            val vDir = configYaml!!.parentFile ?: configYaml
            AcapelaVoiceManifest.readParamFromConfigYaml(vDir)?.let {
                AcapelaVoiceManifest.extractRegistrationFromParam(it)
            } ?: voiceDir?.let {
                AcapelaVoiceManifest.resolveRegistration(it)
            }.orEmpty()
        } else {
            ""
        }
        val maskedReg = if (regVal.length > 8) {
            regVal.take(4) + "*".repeat(regVal.length - 8) +
                regVal.takeLast(4)
        } else if (regVal.isNotEmpty()) {
            "****"
        } else {
            "(لا يوجد)"
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
        nativeTail.forEach { sb.appendLine("  $it") }
        return sb.toString().trimEnd()
    }

    /** مسح ملفات السجل القديمة وسجل Logcat للبدء من نظيف. */
    fun clearOldLogs(context: Context?) {
        if (context == null) return
        val cache = context.cacheDir
        val files = context.filesDir
        listOfNotNull(
            cache?.let { File(it, "acapela_stage") },
            cache?.let { File(it, "acapela_stage_parent") },
            cache?.let { File(it, "acapela_stage_direct") },
            cache?.let { File(it, "acapela_stage_flat") },
            cache?.let { File(it, "acapela_stage_min") },
            cache?.let { File(it, "acapela_stage_nounplugged") },
            cache?.let { File(it, "acapela_stage_v2") },
            cache?.let { File(it, "acapela_marhaba_trial.wav") },
            cache?.let { File(it, "acapela_marhaba_10708.wav") },
            files?.let { File(it, "Acapela") },
            files?.let { File(it, "acapela") }
        ).forEach { dir ->
            if (dir.exists()) {
                if (dir.isFile) {
                    runCatching { dir.delete() }
                } else {
                    dir.walkTopDown().filter {
                        it.isFile && it.name.equals(LOG_FILE_NAME, true)
                    }.forEach { f -> runCatching { f.delete() } }
                }
            }
        }
        runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        }
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
     * تجهيز مجلد مسطح [cache/acapela_stage_flat] بدون مجلدات فرعية،
     * يضم ملف الصوت وكافة ملفات وحدة معالجة اللغة (NLP) وملفات الإعداد
     * ومجلد [userdicos/].
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
        val userdicos = File(stageDir, "userdicos")
        userdicos.mkdirs()

        val candidateRoots = listOfNotNull(
            sourceDir,
            sourceDir?.parentFile,
            context.filesDir?.let { File(it, "Acapela") },
            context.filesDir?.let { File(it, "acapela") },
            context.filesDir,
            context.cacheDir?.let { File(it, "acapela_stage_parent") },
            context.cacheDir?.let { File(it, "acapela_stage_direct") },
            context.cacheDir
        ).filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }

        // 1. نسخ كافة ملفات Salma/NLP/ وملفات ar_sa* مباشرة في جذر المجلد
        for (root in candidateRoots) {
            root.walkTopDown().maxDepth(6).forEach { file ->
                if (file.isFile) {
                    val name = file.name
                    val parentName = file.parentFile?.name.orEmpty()
                    val isNlpDir = parentName.equals("NLP", ignoreCase = true)
                    val isArSa = name.startsWith("ar_sa", ignoreCase = true)
                    val isNlpExt = name.endsWith(".dca", ignoreCase = true) ||
                        name.endsWith(".bnx", ignoreCase = true) ||
                        name.endsWith(".ldi", ignoreCase = true) ||
                        name.endsWith(".trz", ignoreCase = true) ||
                        name.endsWith(".cma", ignoreCase = true) ||
                        name.endsWith(".dic", ignoreCase = true) ||
                        name.endsWith(".bnf", ignoreCase = true)
                    if (isNlpDir || isArSa || isNlpExt) {
                        val dest = File(stageDir, name)
                        if (!dest.exists() || dest.length() != file.length()) {
                            runCatching { file.copyTo(dest, overwrite = true) }
                        }
                    }
                }
            }
        }

        // 2. ملف الصوت salma_22k_lf.bvcu وملفات .bvcu
        val bvcu = findSalmaBvcuFile(context)
            ?: candidateRoots.firstNotNullOfOrNull { root ->
                root.walkTopDown().maxDepth(5).firstOrNull { f ->
                    f.isFile && f.extension.equals("bvcu", ignoreCase = true)
                }
            }
        if (bvcu != null && bvcu.isFile) {
            val destNamed = File(stageDir, "salma_22k_lf.bvcu")
            if (!destNamed.exists() || destNamed.length() != bvcu.length()) {
                runCatching {
                    Files.createLink(destNamed.toPath(), bvcu.toPath())
                }.onFailure {
                    runCatching { bvcu.copyTo(destNamed, overwrite = true) }
                }
            }
            val destOrig = File(stageDir, bvcu.name)
            if (!destOrig.exists() || destOrig.length() != bvcu.length()) {
                runCatching {
                    Files.createLink(destOrig.toPath(), bvcu.toPath())
                }.onFailure {
                    runCatching { bvcu.copyTo(destOrig, overwrite = true) }
                }
            }
        }

        // 3. ملفات الإعداد .inf و .ini وتوليد bvcu.inf و bvcu.ini
        for (root in candidateRoots) {
            root.walkTopDown().maxDepth(5).forEach { f ->
                if (f.isFile && (
                        f.extension.equals("inf", ignoreCase = true) ||
                        f.extension.equals("ini", ignoreCase = true)
                    )
                ) {
                    val dest = File(stageDir, f.name)
                    if (!dest.exists() || dest.length() != f.length()) {
                        runCatching { f.copyTo(dest, overwrite = true) }
                    }
                }
            }
        }
        val infFiles = stageDir.listFiles { f ->
            f.isFile && f.extension.equals("inf", ignoreCase = true)
        }.orEmpty()
        val bvcuInf = File(stageDir, "bvcu.inf")
        if (!bvcuInf.exists() && infFiles.isNotEmpty()) {
            runCatching { infFiles.first().copyTo(bvcuInf, overwrite = true) }
        }
        stageDir.listFiles { f ->
            f.isFile && f.extension.equals("inf", ignoreCase = true)
        }?.forEach { inf ->
            AcapelaVoiceManifest.ensureIniFor(inf)
        }
        val iniFiles = stageDir.listFiles { f ->
            f.isFile && f.extension.equals("ini", ignoreCase = true)
        }.orEmpty()
        val bvcuIni = File(stageDir, "bvcu.ini")
        val salmaIni = File(stageDir, "ar_sa_hd_salma_22k_lf.ini")
        if (iniFiles.isNotEmpty()) {
            val srcIni = iniFiles.first()
            if (!bvcuIni.exists()) {
                runCatching { srcIni.copyTo(bvcuIni, overwrite = true) }
            }
            if (!salmaIni.exists()) {
                runCatching { srcIni.copyTo(salmaIni, overwrite = true) }
            }
        }

        // 4. ملف config.yaml
        val foundYaml = AcapelaVoiceManifest.findConfigYamlWithParam(
            candidateRoots
        )
        val yamlSrc = foundYaml?.first ?: candidateRoots.firstNotNullOfOrNull {
            it.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && f.name.equals("config.yaml", ignoreCase = true)
            }
        }
        if (yamlSrc != null && yamlSrc.isFile) {
            val destYaml = File(stageDir, "config.yaml")
            if (!destYaml.exists() || destYaml.length() != yamlSrc.length()) {
                runCatching { yamlSrc.copyTo(destYaml, overwrite = true) }
            }
        }

        // 5. ملف salma_lf.txt
        val txtSrc = candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && (
                    f.name.equals("salma_lf.txt", ignoreCase = true) ||
                    f.name.endsWith("_lf.txt", ignoreCase = true)
                )
            }
        }
        if (txtSrc != null && txtSrc.isFile) {
            val destTxt = File(stageDir, "salma_lf.txt")
            if (!destTxt.exists() || destTxt.length() != txtSrc.length()) {
                runCatching { txtSrc.copyTo(destTxt, overwrite = true) }
            }
            val destOrigTxt = File(stageDir, txtSrc.name)
            if (!destOrigTxt.exists()) {
                runCatching { txtSrc.copyTo(destOrigTxt, overwrite = true) }
            }
        }

        // 6. ملف 0x00169729
        val hexSrc = candidateRoots.firstNotNullOfOrNull { r ->
            AcapelaVoiceManifest.findHexRegistrationFile(r)
        }
        if (hexSrc != null && hexSrc.isFile) {
            val destNamedHex = File(stageDir, "0x00169729")
            if (!destNamedHex.exists() ||
                destNamedHex.length() != hexSrc.length()
            ) {
                runCatching { hexSrc.copyTo(destNamedHex, overwrite = true) }
            }
            val destOrigHex = File(stageDir, hexSrc.name)
            if (!destOrigHex.exists()) {
                runCatching { hexSrc.copyTo(destOrigHex, overwrite = true) }
            }
        }

        // 7. ملف user.userdico ومجلد userdicos/
        for (root in candidateRoots) {
            root.walkTopDown().maxDepth(5).forEach { f ->
                if (f.isFile &&
                    f.name.endsWith(".userdico", ignoreCase = true)
                ) {
                    val dstSub = File(userdicos, f.name)
                    if (!dstSub.exists() || dstSub.length() != f.length()) {
                        runCatching { f.copyTo(dstSub, overwrite = true) }
                    }
                    if (f.name.equals("user.userdico", ignoreCase = true)) {
                        val dstRoot = File(stageDir, "user.userdico")
                        if (!dstRoot.exists() ||
                            dstRoot.length() != f.length()
                        ) {
                            runCatching { f.copyTo(dstRoot, overwrite = true) }
                        }
                    }
                    if (f.name.equals("ara-sau.userdico", ignoreCase = true)) {
                        val dstRoot = File(stageDir, "ara-sau.userdico")
                        if (!dstRoot.exists() ||
                            dstRoot.length() != f.length()
                        ) {
                            runCatching { f.copyTo(dstRoot, overwrite = true) }
                        }
                    }
                }
            }
        }

        val araDico = File(userdicos, "ara-sau.userdico")
        val userDico = File(userdicos, "user.userdico")
        val userDicoRoot = File(stageDir, "user.userdico")
        if (!userDico.exists() && userDicoRoot.exists()) {
            runCatching { userDicoRoot.copyTo(userDico, overwrite = true) }
        } else if (userDico.exists() && !userDicoRoot.exists()) {
            runCatching { userDico.copyTo(userDicoRoot, overwrite = true) }
        }
        if (!araDico.exists() && userDico.exists()) {
            runCatching { userDico.copyTo(araDico, overwrite = true) }
        }

        return stageDir
    }

    /**
     * تجهيز نسخة مصغرة [cache/acapela_stage_min] تحتوي فقط على الملفات
     * الأساسية لصوت Salma وملفات ar_sa المحددة.
     */
    fun prepareStageMin(context: Context, flatDir: File): File {
        val minDir = File(context.cacheDir, "acapela_stage_min")
        if (minDir.exists()) {
            runCatching { minDir.deleteRecursively() }
        }
        minDir.mkdirs()

        val allowedExact = setOf(
            "salma_22k_lf.bvcu",
            "bvcu.inf",
            "bvcu.ini",
            "config.yaml",
            "ar_sa.dca",
            "ar_sa.bnx",
            "ar_sa.ldi",
            "ar_sa.trz",
            "ar_sa_iv.trz.gra",
            "ar_sa_oov.trz.gra",
            "ar_sa_utf8.cma",
            "ar_sa_utf8.dca",
            "ar_sa_cp.cma",
            "ar_sa.oso"
        )

        flatDir.listFiles { f -> f.isFile }?.forEach { f ->
            val n = f.name
            val matchPrefix = n.startsWith("ar_sa_hd_salma_22k_lf.", true)
            if (n in allowedExact || matchPrefix) {
                val dst = File(minDir, n)
                runCatching { f.copyTo(dst, overwrite = true) }
            }
        }

        val userdicosSrc = File(flatDir, "userdicos")
        if (userdicosSrc.isDirectory) {
            val userdicosDst = File(minDir, "userdicos")
            userdicosDst.mkdirs()
            userdicosSrc.listFiles()?.forEach { f ->
                runCatching { f.copyTo(File(userdicosDst, f.name), true) }
            }
        }
        return minDir
    }

    /**
     * تجهيز نسخة [cache/acapela_stage_nounplugged] مع حذف كافة ملفات
     * unplugged و e_arq و hde_arq.
     */
    fun prepareStageNoUnplugged(context: Context, flatDir: File): File {
        val nounpDir = File(context.cacheDir, "acapela_stage_nounplugged")
        if (nounpDir.exists()) {
            runCatching { nounpDir.deleteRecursively() }
        }
        nounpDir.mkdirs()

        flatDir.listFiles()?.forEach { f ->
            if (f.isFile) {
                val n = f.name.lowercase(java.util.Locale.ROOT)
                val isBlocked = n.contains("unplugged") ||
                    n.contains("e_arq") ||
                    n.contains("hde_arq")
                if (!isBlocked) {
                    runCatching { f.copyTo(File(nounpDir, f.name), true) }
                }
            } else if (f.isDirectory) {
                val dstSub = File(nounpDir, f.name)
                dstSub.mkdirs()
                f.listFiles()?.forEach { subF ->
                    runCatching {
                        subF.copyTo(File(dstSub, subF.name), true)
                    }
                }
            }
        }
        return nounpDir
    }

    /**
     * كتابة ملفات .ini الثلاثة بنفس المحتوى حرفياً سطرين فقط:
     * MODE = "..."
     * #1 FILEMAP "salma_22k_lf.bvcu" ...
     */
    fun writeStageV2Ini(
        stageDir: File,
        mode: String,
        filemapDirective: String
    ) {
        val content = "MODE = \"$mode\"\n$filemapDirective\n"
        val targets = listOf(
            File(stageDir, "ar_sa_hd_salma_22k_lf.ini"),
            File(stageDir, "ar_sa_hd_salma_22k_lf.bvcu.ini"),
            File(stageDir, "bvcu.ini")
        )
        for (f in targets) {
            runCatching {
                f.writeText(content, StandardCharsets.ISO_8859_1)
            }
        }
    }

    /**
     * تجهيز مجلد مسطح [cache/acapela_stage_v2] يحتوي فقط على:
     * ملفات ar_sa* وsalma_22k_lf.bvcu و.inf وconfig.yaml وsalma_lf.txt
     * و0x00169729 وuserdicos/.
     * ويستثني 3073.INI وMSTTSLocArEG.ini وlogfile.txt وأي ملف Microsoft
     * أو .nuul.
     */
    fun stageVoiceV2(
        context: Context,
        sourceDir: File? = null
    ): File {
        val stageDir = File(context.cacheDir, "acapela_stage_v2")
        if (stageDir.exists()) {
            runCatching { stageDir.deleteRecursively() }
        }
        stageDir.mkdirs()
        val userdicos = File(stageDir, "userdicos")
        userdicos.mkdirs()

        fun isExcluded(name: String): Boolean {
            val lower = name.lowercase(java.util.Locale.ROOT)
            return lower == "3073.ini" ||
                lower == "msttslocalreg.ini" ||
                lower == "logfile.txt" ||
                lower.contains("microsoft") ||
                lower.contains("mstts") ||
                lower.endsWith(".nuul")
        }

        val candidateRoots = listOfNotNull(
            sourceDir,
            sourceDir?.parentFile,
            context.filesDir?.let { File(it, "Acapela") },
            context.filesDir?.let { File(it, "acapela") },
            context.filesDir,
            context.cacheDir?.let { File(it, "acapela_stage_flat") },
            context.cacheDir?.let { File(it, "acapela_stage_parent") },
            context.cacheDir?.let { File(it, "acapela_stage_direct") },
            context.cacheDir
        ).filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }

        // 1. نسخ ملفات ar_sa* ومحتويات Salma/NLP/
        for (root in candidateRoots) {
            root.walkTopDown().maxDepth(6).forEach { file ->
                if (file.isFile) {
                    val name = file.name
                    val parentName = file.parentFile?.name.orEmpty()
                    val isNlpDir = parentName.equals("NLP", true)
                    val isArSa = name.startsWith("ar_sa", true)
                    val isNlpExt = name.endsWith(".dca", true) ||
                        name.endsWith(".bnx", true) ||
                        name.endsWith(".ldi", true) ||
                        name.endsWith(".trz", true) ||
                        name.endsWith(".cma", true) ||
                        name.endsWith(".oso", true) ||
                        name.endsWith(".gra", true)
                    if ((isNlpDir || isArSa || isNlpExt) && !isExcluded(name)) {
                        val dest = File(stageDir, name)
                        if (!dest.exists() || dest.length() != file.length()) {
                            runCatching { file.copyTo(dest, overwrite = true) }
                        }
                    }
                }
            }
        }

        // 2. ملف الصوت salma_22k_lf.bvcu
        val bvcu = findSalmaBvcuFile(context)
            ?: candidateRoots.firstNotNullOfOrNull { root ->
                root.walkTopDown().maxDepth(5).firstOrNull { f ->
                    f.isFile && f.extension.equals("bvcu", true)
                }
            }
        if (bvcu != null && bvcu.isFile) {
            val destNamed = File(stageDir, "salma_22k_lf.bvcu")
            if (!destNamed.exists() || destNamed.length() != bvcu.length()) {
                runCatching {
                    Files.createLink(destNamed.toPath(), bvcu.toPath())
                }.onFailure {
                    runCatching { bvcu.copyTo(destNamed, overwrite = true) }
                }
            }
        }

        // 3. ملفات .inf
        val infSrc = candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile &&
                    f.name.equals("ar_sa_hd_salma_22k_lf.bvcu.inf", true)
            }
        } ?: candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && f.extension.equals("inf", true) &&
                    !isExcluded(f.name)
            }
        }
        if (infSrc != null && infSrc.isFile) {
            val destInf1 = File(stageDir, "ar_sa_hd_salma_22k_lf.bvcu.inf")
            runCatching { infSrc.copyTo(destInf1, overwrite = true) }
            val destInf2 = File(stageDir, "bvcu.inf")
            runCatching { infSrc.copyTo(destInf2, overwrite = true) }
        }

        // 4. ملف config.yaml
        val yamlSrc = AcapelaVoiceManifest.findConfigYamlWithParam(
            candidateRoots
        )?.first ?: candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && f.name.equals("config.yaml", true)
            }
        }
        if (yamlSrc != null && yamlSrc.isFile) {
            val destYaml = File(stageDir, "config.yaml")
            runCatching { yamlSrc.copyTo(destYaml, overwrite = true) }
        }

        // 5. ملف salma_lf.txt
        val txtSrc = candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && (
                    f.name.equals("salma_lf.txt", true) ||
                    f.name.endsWith("_lf.txt", true)
                )
            }
        }
        if (txtSrc != null && txtSrc.isFile) {
            val destTxt = File(stageDir, "salma_lf.txt")
            runCatching { txtSrc.copyTo(destTxt, overwrite = true) }
        }

        // 6. ملف 0x00169729
        val hexSrc = candidateRoots.firstNotNullOfOrNull { r ->
            AcapelaVoiceManifest.findHexRegistrationFile(r)
        }
        if (hexSrc != null && hexSrc.isFile) {
            val destHex = File(stageDir, "0x00169729")
            runCatching { hexSrc.copyTo(destHex, overwrite = true) }
        }

        // 7. ملفات userdicos/
        for (root in candidateRoots) {
            root.walkTopDown().maxDepth(5).forEach { f ->
                if (f.isFile && f.name.endsWith(".userdico", true)) {
                    val dstSub = File(userdicos, f.name)
                    if (!dstSub.exists() || dstSub.length() != f.length()) {
                        runCatching { f.copyTo(dstSub, overwrite = true) }
                    }
                }
            }
        }
        val araDico = File(userdicos, "ara-sau.userdico")
        val userDico = File(userdicos, "user.userdico")
        if (!araDico.exists() && userDico.exists()) {
            runCatching { userDico.copyTo(araDico, overwrite = true) }
        }

        // 8. ضمان خلو المجلد من أي ملف مستبعد
        stageDir.listFiles()?.forEach { f ->
            if (f.isFile && isExcluded(f.name)) {
                runCatching { f.delete() }
            }
        }

        // 9. كتابة ملفات .ini الثلاثة بالمحتوى الافتراضي الأولي
        writeStageV2Ini(
            stageDir = stageDir,
            mode = "MODE=prep_hd",
            filemapDirective = "#1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]"
        )

        return stageDir
    }

    /**
     * استخراج وطباعة المحتوى الكامل للملف الأصلي 61 بايت
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
                            .let { maskMiddle(it, 4, 4) }
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
     * تشغيل محاولة نطق واحدة بعد مسح السجل لتوليد وتحديث كافة السجلات.
     */
    suspend fun runDiagnosticProbe(context: Context) {
        clearOldLogs(context)
        stageVoiceFlat(context)
        runCatching {
            val provider = AcapelaEmbeddedVoiceProvider(context)
            val voices = provider.listVoices(java.util.Locale.ROOT)
            val voice = voices.firstOrNull {
                it.id.contains("salma", ignoreCase = true)
            } ?: voices.firstOrNull() ?: VoiceDescriptor(
                id = "salma22k_hq",
                providerId = AcapelaEmbeddedVoiceProvider.PROVIDER_ID,
                displayName = "Salma",
                locale = java.util.Locale.forLanguageTag("ar-SA")
            )
            provider.synthesize(
                text = "تجربة نطق ناطق",
                voice = voice,
                speechRate = 1.0f,
                pitch = 1.0f,
                volume = 1.0f,
                onFormatInfo = { _, _ -> },
                onAudioChunk = { _, _ -> },
                enginePackage = "acapela_embedded",
                voiceLocale = java.util.Locale.forLanguageTag("ar-SA"),
                desiredVoiceName = "salma22k_hq",
                category = "default"
            )
        }
    }

    /**
     * بناء تقرير Acapela الشامل الذي يضم كافة الأقسام المطلوبة (1..6).
     */
    fun buildAcapelaFullReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("=== تقرير تشخيص Acapela المتقدم (إصدار 10708) ===")
        sb.appendLine("==================================================")
        sb.appendLine("")

        // ── [البند 1] فحص الملف الأصلي 61 بايت (نص و Hex) ───────────────
        sb.appendLine("--- [البند 1] فحص الملف الأصلي 61 بايت (نص و Hex) ---")
        sb.appendLine(getOriginalIniDump(context))
        sb.appendLine("")

        // ── [البند 3] مقارنة ملفات NLP مع ملفات التهيئة ──────────────────
        sb.appendLine("--- [البند 3] مقارنة ملفات NLP مع ملفات التهيئة ---")
        sb.appendLine(compareNlpFiles(context))
        sb.appendLine("")

        val filesDir = context.filesDir
        val cacheDir = context.cacheDir
        val filesAcapela = File(filesDir, "Acapela")
        val altAcapela = File(filesDir, "acapela")
        val cacheV2 = File(cacheDir, "acapela_stage_v2")
        val cacheFlat = File(cacheDir, "acapela_stage_flat")
        val cacheMin = File(cacheDir, "acapela_stage_min")
        val cacheNoUnp = File(cacheDir, "acapela_stage_nounplugged")

        // ── [البند 3] معمارية النظام والمكتبة الأصلية المحمّلة ─────────────
        sb.appendLine("--- [البند 3] معمارية النظام والمكتبة المحمّلة ---")
        sb.appendLine(getNativeLibraryDetails(context))
        sb.appendLine("")

        // ── [البند 1] محتوى النصوص الكامل وملفات التجهيز المسطح ────────────
        sb.appendLine("--- [البند 1] محتوى النصوص والملفات المطلوبة ---")
        val req1FlatFiles = listOf(
            "ar_sa_hd_salma_22k_lf.ini",
            "ar_sa_hd_salma_22k_lf.bvcu.ini",
            "ar_sa_hd_salma_22k_lf.bvcu.inf",
            "bvcu.ini",
            "bvcu.inf",
            "config.yaml",
            "salma_lf.txt",
            "3073.INI",
            "logfile.txt"
        )
        for (fName in req1FlatFiles) {
            val f = File(cacheV2, fName).takeIf { it.exists() }
                ?: File(cacheFlat, fName).takeIf { it.exists() }
                ?: if (fName.equals("3073.INI", true)) {
                    File(cacheFlat, "3073.ini").takeIf { it.exists() }
                        ?: findFileRecursively(context, "3073.ini")
                        ?: findFileRecursively(context, "3073.INI")
                } else null
                ?: findFileRecursively(context, fName)
            sb.appendLine(">>> ملف: $fName (${f?.absolutePath ?: "غير موجود"})")
            sb.appendLine(dumpFileContent(f))
            sb.appendLine("")
        }

        val extraFiles = listOf(
            "version.txt" to ("Salma/version.txt"),
            "logfile.txt (salma_lf)" to ("Salma/salma_lf/logfile.txt"),
            "engines.yaml" to null,
            "voicelist" to null
        )
        for ((label, hint) in extraFiles) {
            val fName = label.substringBefore(' ')
            val f = findFileRecursively(context, fName, hint)
            sb.appendLine(">>> ملف خارجي: $label (${f?.absolutePath ?: "-"})")
            sb.appendLine(dumpFileContent(f))
            sb.appendLine("")
        }

        // ── [البند 2] أول 64 بايت Hex و MD5 للملفات الأساسية ───────────────
        sb.appendLine("--- [البند 2] أول 64 بايت Hex و MD5 للملفات ---")
        val req2Files = listOf(
            "ar_sa.dca",
            "ar_sa_unplugged.dca",
            "ar_sa_hde_arq.dca",
            "ar_sa.bnx",
            "ar_sa_utf8.cma",
            "ar_sa.trz",
            "salma_22k_lf.bvcu"
        )
        for (fName in req2Files) {
            val f = File(cacheV2, fName).takeIf { it.exists() }
                ?: File(cacheFlat, fName).takeIf { it.exists() }
                ?: if (fName.endsWith(".bvcu")) findSalmaBvcuFile(context)
                else findFileRecursively(context, fName)
            sb.appendLine(getFileHexHeaderAndMd5(f))
            sb.appendLine("")
        }

        // ── شجرة المجلدات المتداخلة للأدلة ────────────────────────────────
        sb.appendLine("--- شجرة المجلدات المتداخلة للأدلة ---")
        sb.append(buildNestedDirectoryTree(filesAcapela, "files/Acapela"))
        if (altAcapela.exists() && altAcapela != filesAcapela) {
            sb.append(buildNestedDirectoryTree(altAcapela, "files/acapela"))
        }
        val cacheList = listOf(
            cacheV2,
            cacheFlat,
            cacheMin,
            cacheNoUnp,
            File(cacheDir, "acapela_stage_parent")
        ).filter { it.exists() }
        cacheList.forEach { cDir ->
            sb.append(buildNestedDirectoryTree(cDir, "cache/${cDir.name}"))
        }
        sb.appendLine("")

        // ── [البند 4 و 5] نتائج التوليفات وسجل المكتبة الأصلي ─────────────
        sb.appendLine(
            "--- [البندان 4 و 5] نتائج التوليفات وسجل النداءات ---"
        )
        val diagReport = File(cacheDir, "acapela_diagnostic_report.txt")
        if (diagReport.exists() && diagReport.length() > 0L) {
            val reportText = runCatching {
                diagReport.readText(StandardCharsets.UTF_8)
            }.getOrNull()
            if (!reportText.isNullOrBlank()) {
                sb.appendLine(reportText)
            } else {
                sb.appendLine("  (ملف acapela_diagnostic_report.txt فارغ)")
            }
        } else {
            sb.appendLine("  (بانتظار تنفيذ التوليفات عبر جلسة المحرك)")
        }
        sb.appendLine("")

        // ── [البند 6] حالة ملف WAV التجريبي (مرحبا) ───────────────────────
        sb.appendLine("--- [البند 6] حالة ملف WAV التجريبي (مرحبا) ---")
        val trialWav = File(cacheDir, "acapela_marhaba_trial.wav")
        if (trialWav.exists()) {
            sb.appendLine(
                "ملف WAV موجود: ${trialWav.absolutePath} " +
                    "(${trialWav.length()} بايت)"
            )
        } else {
            sb.appendLine("لم يُنتج ملف WAV بعد (عينات PCM = 0)")
        }
        sb.appendLine("")

        // ── كامل سجل المكتبة الأصلية (البند 4) ─────────────────────────────
        sb.appendLine("--- [البند 4] كامل سجل المكتبة الأصلية logcat ---")
        val fullNative = readNativeLogcatLines(1500)
        fullNative.forEach { sb.appendLine("  $it") }
        sb.appendLine("")

        val fullOutput = sb.toString().trimEnd()

        // حفظ التقرير في ملف نصي واحد كما في البند 6
        runCatching {
            File(cacheDir, "acapela_full_diagnostic_10708.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_diagnostic_report.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
        }

        return fullOutput
    }

    /**
     * استرجاع آخر [maxLines] سطراً من سجل المكتبة الأصلية عبر Logcat.
     */
    fun readNativeLogcatLines(maxLines: Int = 1000): List<String> {
        return runCatching {
            val myPid = Process.myPid().toString()
            val fetchCount = maxLines.coerceAtLeast(1000).toString()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", fetchCount, "--pid", myPid)
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
                    line.contains("salma", ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                filtered.takeLast(maxLines)
            } else {
                val procTag = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d", "-t", fetchCount,
                        "-s", "acattsandroid-extended-native:V"
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
        }
    }

    private fun appendTail(
        sb: StringBuilder,
        title: String,
        file: File?
    ) {
        sb.appendLine("- $title (${file?.name ?: "-"}):")
        tailLines(file).forEach { sb.appendLine("  $it") }
    }

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
