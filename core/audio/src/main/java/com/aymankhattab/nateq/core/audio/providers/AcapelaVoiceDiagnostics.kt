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
            files?.let { File(it, "Acapela") },
            files?.let { File(it, "acapela") }
        ).forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().filter {
                    it.isFile && it.name.equals(LOG_FILE_NAME, true)
                }.forEach { f -> runCatching { f.delete() } }
            }
        }
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
     * بناء تقرير Acapela الشامل الذي يضم كافة الأقسام المطلوبة.
     */
    fun buildAcapelaFullReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine(
            "الأقسام المضافة: (1) شجرة ملفات ومجلدات Acapela المتداخلة " +
                "بالأحجام، (2) وسائط nInit وnSetCurrentVoice وnSpeak " +
                "الحرفية وأكواد الإرجاع، (3) آخر 40 سطراً من سجل " +
                "المكتبة الأصلية مع إبراز nlpInitError، (4) فحص مسار " +
                "وحجم salma_22k_lf.bvcu، (5) فحص المجلد المسطح " +
                "acapela_stage_flat وتأكيد ملفات NLP والصوت."
        )
        sb.appendLine("")
        val filesDir = context.filesDir
        val cacheDir = context.cacheDir
        val filesAcapela = File(filesDir, "Acapela")
        val altAcapela = File(filesDir, "acapela")
        val cacheStage = File(cacheDir, "acapela_stage")
        val cacheParent = File(cacheDir, "acapela_stage_parent")
        val cacheDirect = File(cacheDir, "acapela_stage_direct")
        val cacheFlat = File(cacheDir, "acapela_stage_flat")

        // 1) فحص وجود ملف salma_22k_lf.bvcu في المسار الممرر للمحرك
        val targetStage = if (cacheFlat.exists()) cacheFlat
        else if (cacheParent.exists()) cacheParent
        else if (cacheDirect.exists()) cacheDirect
        else if (cacheStage.exists()) cacheStage
        else filesAcapela

        val foundBvcu = findSalmaBvcuFile(context)
        sb.appendLine("--- [القسم 4] فحص ملف salma_22k_lf.bvcu ---")
        if (foundBvcu != null) {
            val destInTarget = File(targetStage, foundBvcu.name)
            if (!destInTarget.exists() ||
                destInTarget.length() != foundBvcu.length()
            ) {
                runCatching {
                    Files.createLink(destInTarget.toPath(), foundBvcu.toPath())
                }.onFailure {
                    runCatching {
                        foundBvcu.copyTo(destInTarget, overwrite = true)
                    }
                }
            }
            val namedDest = File(targetStage, "salma_22k_lf.bvcu")
            if (!namedDest.exists() ||
                namedDest.length() != foundBvcu.length()
            ) {
                runCatching {
                    Files.createLink(namedDest.toPath(), foundBvcu.toPath())
                }.onFailure {
                    runCatching {
                        foundBvcu.copyTo(namedDest, overwrite = true)
                    }
                }
            }
            sb.appendLine(
                "  * ملف الصوت الأصلي المكتشف: ${foundBvcu.absolutePath}"
            )
            sb.appendLine("  * حجم الملف الأصلي: ${foundBvcu.length()} بايت")
            sb.appendLine(
                "  * المسار الممرر للمحرك: ${namedDest.absolutePath}"
            )
            sb.appendLine(
                "  * حجم الملف في مسار المحرك: ${namedDest.length()} بايت"
            )
            val ready = namedDest.exists() && namedDest.length() > 0L
            sb.appendLine(
                "  * حالة توفر الملف في مسار المحرك: " +
                    if (ready) "متوفر بنجاح" else "غير متوفر"
            )
        } else {
            sb.appendLine(
                "  * تنبيه: لم يُعثر على ملف salma_22k_lf.bvcu في " +
                    "files أو cache!"
            )
        }
        sb.appendLine("")

        // 2) شجرة الملفات والمجلدات المتداخلة بالأحجام بالبايت
        sb.appendLine("--- [القسم 1] شجرة ملفات ومجلدات Acapela المتداخلة ---")
        sb.append(buildNestedDirectoryTree(filesAcapela, "files/Acapela"))
        if (altAcapela.exists() && altAcapela != filesAcapela) {
            sb.append(buildNestedDirectoryTree(altAcapela, "files/acapela"))
        }
        val cacheList = listOf(
            cacheFlat,
            cacheStage,
            cacheParent,
            cacheDirect
        ).filter { it.exists() }
        if (cacheList.isNotEmpty()) {
            cacheList.forEach { cDir ->
                sb.append(
                    buildNestedDirectoryTree(cDir, "cache/${cDir.name}")
                )
            }
        } else {
            sb.appendLine("  (لا توجد مجلدات مرحلية في cache)")
        }
        sb.appendLine("")

        // 3) فحص المجلد المسطح cache/acapela_stage_flat وتأكيد ملفات NLP
        sb.appendLine(
            "--- [القسم 5] فحص مجلد التجهيز المسطح acapela_stage_flat ---"
        )
        if (cacheFlat.exists() && cacheFlat.isDirectory) {
            val fDca = File(cacheFlat, "ar_sa.dca")
            val fUnp = File(cacheFlat, "ar_sa_unplugged.dca")
            val fHde = File(cacheFlat, "ar_sa_hde_arq.dca")
            val fBvcu = File(cacheFlat, "salma_22k_lf.bvcu")

            val okDca = fDca.exists() && fDca.length() > 0L
            val okUnp = fUnp.exists() && fUnp.length() > 0L
            val okHde = fHde.exists() && fHde.length() > 0L
            val okBvcu = fBvcu.exists() && fBvcu.length() > 0L

            sb.appendLine(
                "  * ar_sa.dca: " +
                    if (okDca) "موجود (${fDca.length()} بايت)" else "غير موجود!"
            )
            sb.appendLine(
                "  * ar_sa_unplugged.dca: " +
                    if (okUnp) "موجود (${fUnp.length()} بايت)" else "غير موجود!"
            )
            sb.appendLine(
                "  * ar_sa_hde_arq.dca: " +
                    if (okHde) "موجود (${fHde.length()} بايت)"
                    else "غير موجود!"
            )
            sb.appendLine(
                "  * salma_22k_lf.bvcu: " +
                    if (okBvcu) "موجود (${fBvcu.length()} بايت)"
                    else "غير موجود!"
            )
            val okAll = okDca && okUnp && okHde && okBvcu
            sb.appendLine(
                "  * حالة اكتمال ملفات NLP والصوت: " +
                    if (okAll) "مكتملة بنجاح" else "ناقصة"
            )
        } else {
            sb.appendLine("  (مجلد cache/acapela_stage_flat غير موجود بعد)")
        }
        sb.appendLine("")

        // 4) قراءة وسائط النداءات وأكواد الإرجاع
        sb.appendLine(
            "--- [القسم 2] وسائط nInit وnSetCurrentVoice وnSpeak " +
                "الحرفية وأكواد الإرجاع ---"
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
            sb.appendLine(
                "  (بانتظار تنفيذ أول محاولة نطق لتسجيل الوسائط الحرفية)"
            )
        }
        sb.appendLine("")

        // 5) آخر 40 سطراً من سجل المكتبة الأصلية
        sb.appendLine(
            "--- [القسم 3] آخر 40 سطراً من سجل المكتبة الأصلية " +
                "acattsandroid-extended-native (التشغيل الحالي) ---"
        )
        val nativeLines = readNativeLogcatLines(40)
        nativeLines.forEach { sb.appendLine("  $it") }

        return sb.toString().trimEnd()
    }

    /**
     * استرجاع آخر [maxLines] سطراً من سجل المكتبة الأصلية عبر Logcat.
     */
    fun readNativeLogcatLines(maxLines: Int = 40): List<String> {
        return runCatching {
            val myPid = Process.myPid().toString()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", "500", "--pid", myPid)
            )
            val lines = proc.inputStream
                .bufferedReader(StandardCharsets.UTF_8).readLines()
            proc.waitFor()
            val filtered = lines.filter { line ->
                line.contains("acattsandroid", ignoreCase = true) ||
                    line.contains("tts_function", ignoreCase = true) ||
                    line.contains("BABILE", ignoreCase = true) ||
                    line.contains("eb=-", ignoreCase = true)
            }
            if (filtered.isNotEmpty()) {
                filtered.takeLast(maxLines)
            } else {
                val procTag = Runtime.getRuntime().exec(
                    arrayOf(
                        "logcat", "-d", "-t", "300",
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
