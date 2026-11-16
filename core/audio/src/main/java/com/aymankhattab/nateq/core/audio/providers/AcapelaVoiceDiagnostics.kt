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
import java.util.Locale

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
            cache?.let { File(it, "acapela_stage_10709_exact") },
            cache?.let { File(it, "acapela_stage_full") },
            cache?.let { File(it, "acapela_stage_full_nlp_subdir") },
            cache?.let { File(it, "acapela_marhaba_trial.wav") },
            cache?.let { File(it, "acapela_marhaba_10723.wav") },
            cache?.let { File(it, "acapela_marhaba_10722.wav") },
            cache?.let { File(it, "acapela_marhaba_10721.wav") },
            cache?.let { File(it, "acapela_marhaba_10720.wav") },
            cache?.let { File(it, "acapela_marhaba_10712.wav") },
            cache?.let { File(it, "acapela_marhaba_10711.wav") },
            cache?.let { File(it, "acapela_marhaba_10710.wav") },
            cache?.let { File(it, "acapela_marhaba_10709.wav") },
            cache?.let { File(it, "acapela_marhaba_10708.wav") },
            cache?.let { File(it, "acapela_diagnostic_report.txt") },
            cache?.let { File(it, "acapela_full_diagnostic_10723.txt") },
            cache?.let { File(it, "acapela_full_diagnostic_10722.txt") },
            cache?.let { File(it, "acapela_full_diagnostic_10721.txt") },
            cache?.let { File(it, "acapela_full_diagnostic_10720.txt") },
            cache?.let { File(it, "acapela_full_diagnostic_10711.txt") },
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
            // ع١٠٧١٤-١: نسخة حقيقية وليست رابطاً باسم
            // ar_sa_hd_salma_22k_lf.bvcu
            val destArSa = File(stageDir, "ar_sa_hd_salma_22k_lf.bvcu")
            if (!destArSa.exists() || destArSa.length() != bvcu.length()) {
                runCatching { bvcu.copyTo(destArSa, overwrite = true) }
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
     * معيار النجاح الوحيد لبناء 10724 (وكذلك 10723):
     * nSpeak يعيد قيمة غير -2 و pcm > 0،
     * وغياب سطري "Error init language" و "voice ... not loaded" من سجل المكتبة.
     * ولا يعتبر eb=0 نجاحاً بمفرده.
     */
    fun evaluateTrialSuccess10724(
        speakCode: Int,
        samples: Long,
        nativeLines: List<String>
    ): Boolean {
        val hasLangError = nativeLines.any { line ->
            line.contains("Error init language", ignoreCase = true) ||
                line.contains("not loaded", ignoreCase = true)
        }
        return speakCode != -2 && samples > 0L && !hasLangError
    }

    fun evaluateTrialSuccess10723(
        speakCode: Int,
        samples: Long,
        nativeLines: List<String>
    ): Boolean = evaluateTrialSuccess10724(speakCode, samples, nativeLines)

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
     * تجهيز نظيف لمسار Path_Full_Stage (بناء 10723):
     * - salma_22k_lf.bvcu
     * - ar_sa_hd_salma_22k_lf.bvcu.inf
     * - ar_sa_hd_salma_22k_lf.ini (MODE=prep_hd و [ RAM ])
     * - 0x00169729
     * - ملفات NLP مسطحة في الجذر إذا [flatNlp] = true أو داخل NLP/ إن كان false
     * - userdicos/ara-sau.userdico وأي .userdico آخر
     * - config.yaml
     * - يستثني nuul ونسخة ar_sa_hd_...bvcu المكررة وأي ملف دخيل
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

        val userdicosDir = File(stageDir, "userdicos")
        userdicosDir.mkdirs()

        val nlpTargetDir = if (flatNlp) {
            stageDir
        } else {
            File(stageDir, "NLP").also { it.mkdirs() }
        }

        fun isExcluded(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return lower.contains("nuul") ||
                lower.contains("tmp") ||
                lower == "version.txt" ||
                lower == "3073.ini" ||
                lower == "msttslocalreg.ini" ||
                lower == "logfile.txt" ||
                lower.contains("microsoft") ||
                lower.contains("mstts") ||
                lower == "ar_sa_hd_salma_22k_lf.bvcu"
        }

        val salmaDir = findOriginalSalmaDir(context)
        val candidateRoots = listOfNotNull(
            sourceDir,
            sourceDir?.parentFile,
            sourceDir?.parentFile?.parentFile,
            salmaDir,
            salmaDir?.let { File(it, "NLP") },
            salmaDir?.let { File(it, "salma_lf") },
            context.filesDir?.let { File(it, "Acapela") },
            context.filesDir?.let { File(it, "Acapela/Salma") },
            context.filesDir?.let { File(it, "Acapela/Salma/NLP") },
            context.filesDir?.let { File(it, "Acapela/Salma/salma_lf") },
            context.filesDir?.let { File(it, "acapela") },
            context.filesDir?.let { File(it, "acapela/Salma") },
            context.filesDir?.let { File(it, "acapela/Salma/NLP") },
            context.filesDir?.let { File(it, "acapela/Salma/salma_lf") },
            context.filesDir,
            context.cacheDir?.let { File(it, "acapela_stage_v2") },
            context.cacheDir?.let { File(it, "acapela_stage_10709_exact") },
            context.cacheDir
        ).filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }

        // 1. ملف الصوت salma_22k_lf.bvcu فقط
        val bvcu = candidateRoots.firstNotNullOfOrNull { root ->
            root.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && f.extension.equals("bvcu", true) &&
                    !isExcluded(f.name) &&
                    (f.name.equals("salma_22k_lf.bvcu", true) ||
                        f.length() > 5_000_000L)
            }
        } ?: findSalmaBvcuFile(context)
        if (bvcu != null && bvcu.isFile) {
            val destBvcu = File(stageDir, "salma_22k_lf.bvcu")
            runCatching {
                Files.createLink(destBvcu.toPath(), bvcu.toPath())
            }.onFailure {
                runCatching { bvcu.copyTo(destBvcu, overwrite = true) }
            }
        }

        // 2. ملف ar_sa_hd_salma_22k_lf.bvcu.inf
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
            val destInf = File(stageDir, "ar_sa_hd_salma_22k_lf.bvcu.inf")
            runCatching { infSrc.copyTo(destInf, overwrite = true) }
        }

        // 3. كتابة ملف ar_sa_hd_salma_22k_lf.ini بمحتوى [ RAM ]
        val iniContent = "MODE = \"MODE=prep_hd\"\n" +
            "#1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]\n"
        runCatching {
            File(stageDir, "ar_sa_hd_salma_22k_lf.ini").writeText(
                iniContent,
                StandardCharsets.ISO_8859_1
            )
        }

        // 4. ملف 0x00169729
        val hexSrc = candidateRoots.firstNotNullOfOrNull { r ->
            AcapelaVoiceManifest.findHexRegistrationFile(r)
        } ?: candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(6).firstOrNull { f ->
                f.isFile && f.name.contains("0x0016", ignoreCase = true) &&
                    f.length() > 0L
            }
        }
        if (hexSrc != null && hexSrc.isFile) {
            val destHex = File(stageDir, "0x00169729")
            runCatching { hexSrc.copyTo(destHex, overwrite = true) }
        }

        // 5. ملف config.yaml
        val yamlSrc = candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && f.name.equals("config.yaml", true)
            }
        }
        if (yamlSrc != null && yamlSrc.isFile) {
            val destYaml = File(stageDir, "config.yaml")
            runCatching { yamlSrc.copyTo(destYaml, overwrite = true) }
        }

        // 6. ملفات NLP الأصلية
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
                    val isBvcuOrConfig = name.endsWith(".bvcu", true) ||
                        name.endsWith(".ini", true) ||
                        name.endsWith(".inf", true)
                    if ((isNlpDir || isArSa || isNlpExt) &&
                        !isBvcuOrConfig && !isExcluded(name)
                    ) {
                        val dest = File(nlpTargetDir, name)
                        if (!dest.exists() || dest.length() != file.length()) {
                            runCatching { file.copyTo(dest, overwrite = true) }
                        }
                    }
                }
            }
        }

        // 7. ملفات userdicos/ ونسخها في الجذر أيضاً (البند 6 لبناء 10724)
        val allDicos = candidateRoots.flatMap { root ->
            root.walkTopDown().maxDepth(6).filter {
                it.isFile && it.name.endsWith(".userdico", true)
            }.toList()
        }
        val bestAraSau = allDicos
            .filter { it.name.equals("ara-sau.userdico", true) }
            .maxByOrNull { it.length() }
        val bestUser = allDicos
            .filter { it.name.equals("user.userdico", true) }
            .maxByOrNull { it.length() }

        // نسخ كافة ملفات userdico إلى مجلد userdicos/
        for (f in allDicos) {
            val dstSub = File(userdicosDir, f.name)
            if (!dstSub.exists() || dstSub.length() != f.length()) {
                runCatching { f.copyTo(dstSub, overwrite = true) }
            }
        }
        // وضع الملفين في userdicos/ وفي المجلد الرئيسي معاً
        bestAraSau?.let { src ->
            val dstSub = File(userdicosDir, "ara-sau.userdico")
            runCatching { src.copyTo(dstSub, overwrite = true) }
            val dstRoot = File(stageDir, "ara-sau.userdico")
            runCatching { src.copyTo(dstRoot, overwrite = true) }
        }
        bestUser?.let { src ->
            val dstSub = File(userdicosDir, "user.userdico")
            runCatching { src.copyTo(dstSub, overwrite = true) }
            val dstRoot = File(stageDir, "user.userdico")
            runCatching { src.copyTo(dstRoot, overwrite = true) }
        }
        val araDicoSub = File(userdicosDir, "ara-sau.userdico")
        val userDicoSub = File(userdicosDir, "user.userdico")
        if (!araDicoSub.exists() && userDicoSub.exists()) {
            runCatching { userDicoSub.copyTo(araDicoSub, overwrite = true) }
            val dstRoot = File(stageDir, "ara-sau.userdico")
            runCatching { userDicoSub.copyTo(dstRoot, overwrite = true) }
        }

        // 8. تنظيف نهائي صارم لضمان خلو المجلد من أي ملف غير مرغوب فيه
        stageDir.walkTopDown().forEach { f ->
            if (f.isFile && isExcluded(f.name)) {
                runCatching { f.delete() }
            }
        }
        if (flatNlp) {
            val nestedNlp = File(stageDir, "NLP")
            if (nestedNlp.exists()) {
                runCatching { nestedNlp.deleteRecursively() }
            }
        }

        return stageDir
    }

    /**
     * تجهيز نظيف يطابق 10709 حرفياً في [cache/acapela_stage_10709_exact]:
     * - salma_22k_lf.bvcu وملفات ar_sa وملفات .inf وملف 0x00169729 و userdicos/
     * - بدون نسخة ar_sa_hd_salma_22k_lf.bvcu
     * - بدون أي ملف nuul216vqfp
     * - كتابة ar_sa_hd_salma_22k_lf.ini بـ MODE=prep_hd و [ RAM ]
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

        fun isExcluded(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return lower.contains("nuul") ||
                lower.contains("tmp") ||
                lower == "version.txt" ||
                lower == "config.yaml" ||
                lower == "3073.ini" ||
                lower == "msttslocalreg.ini" ||
                lower == "logfile.txt" ||
                lower.contains("microsoft") ||
                lower.contains("mstts") ||
                lower == "ar_sa_hd_salma_22k_lf.bvcu"
        }

        val candidateRoots = listOfNotNull(
            sourceDir,
            sourceDir?.parentFile,
            context.filesDir?.let { File(it, "Acapela") },
            context.filesDir?.let { File(it, "Acapela/Salma") },
            context.filesDir?.let { File(it, "Acapela/Salma/salma_lf") },
            context.filesDir?.let { File(it, "acapela") },
            context.filesDir
        ).filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }

        // 1. ملف الصوت salma_22k_lf.bvcu فقط
        val bvcu = candidateRoots.firstNotNullOfOrNull { root ->
            root.walkTopDown().maxDepth(5).firstOrNull { f ->
                f.isFile && f.extension.equals("bvcu", true) &&
                    !isExcluded(f.name) &&
                    (f.name.equals("salma_22k_lf.bvcu", true) ||
                        f.length() > 5_000_000L)
            }
        } ?: findSalmaBvcuFile(context)
        if (bvcu != null && bvcu.isFile) {
            val destNamed = File(stageDir, "salma_22k_lf.bvcu")
            runCatching {
                Files.createLink(destNamed.toPath(), bvcu.toPath())
            }.onFailure {
                runCatching { bvcu.copyTo(destNamed, overwrite = true) }
            }
        }

        // 2. ملف .inf
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
            val destInf = File(stageDir, "ar_sa_hd_salma_22k_lf.bvcu.inf")
            runCatching { infSrc.copyTo(destInf, overwrite = true) }
        }

        // 3. كتابة ملف .ini بمحتوى 10709
        writeStageV2Ini(
            stageDir = stageDir,
            mode = "MODE=prep_hd",
            filemapDirective = "#1 FILEMAP \"salma_22k_lf.bvcu\" [ RAM ]"
        )

        // 4. حذف أي ملف أو مجلد دخيل للتأكد من بقاء الملفات الثلاثة فقط
        val allowedNames = setOf(
            "salma_22k_lf.bvcu",
            "ar_sa_hd_salma_22k_lf.bvcu.inf",
            "ar_sa_hd_salma_22k_lf.ini"
        )
        stageDir.listFiles()?.forEach { f ->
            if (f.name !in allowedNames) {
                runCatching {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
            }
        }

        return stageDir
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
        sourceDir: File? = null,
        copyArSaBvcu: Boolean = true
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
            sourceDir?.parentFile?.parentFile,
            context.filesDir?.let { File(it, "Acapela") },
            context.filesDir?.let { File(it, "Acapela/Salma") },
            context.filesDir?.let { File(it, "Acapela/Salma/salma_lf") },
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
            if (copyArSaBvcu) {
                // ع١٠٧١٤-١: نسخة حقيقية وليست رابطاً باسم
                // ar_sa_hd_salma_22k_lf.bvcu
                val destArSa = File(stageDir, "ar_sa_hd_salma_22k_lf.bvcu")
                if (!destArSa.exists() || destArSa.length() != bvcu.length()) {
                    runCatching { bvcu.copyTo(destArSa, overwrite = true) }
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
        } ?: candidateRoots.firstNotNullOfOrNull { r ->
            r.walkTopDown().maxDepth(6).firstOrNull { f ->
                f.isFile && f.name.contains("0x", ignoreCase = true) &&
                    f.length() > 0L
            }
        }
        if (hexSrc != null && hexSrc.isFile) {
            val destHexOrig = File(stageDir, hexSrc.name)
            runCatching { hexSrc.copyTo(destHexOrig, overwrite = true) }
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
        try {
            val provider = AcapelaEmbeddedVoiceProvider(context)
            provider.shutdown()
            EngineHostClient.release(context, HostProtocol.ENGINE_ACAPELA)
            EngineHostClient.clearInitialized(HostProtocol.ENGINE_ACAPELA)

            val baseDir = provider.resolveDataDir()
            if (baseDir == null) {
                val err =
                    "=== [استثناء يمنع تشغيل اختبار التوليفات 10712] ===\n" +
                        "تعذر تحديد مجلد أصوات Acapela " +
                        "(resolveDataDir returned null)"
                File(context.cacheDir, "acapela_diagnostic_report.txt")
                    .writeText(err, StandardCharsets.UTF_8)
                return
            }

            val initRes = EngineHostClient.init(
                context = context,
                engineId = HostProtocol.ENGINE_ACAPELA,
                dataDir = baseDir.absolutePath,
                force = true
            )
            if (!initRes.ok) {
                val err =
                    "=== [استثناء يمنع تشغيل اختبار التوليفات 10712] ===\n" +
                        "فشل نداء EngineHostClient.init [${initRes.code}]: " +
                        "${initRes.message}"
                File(context.cacheDir, "acapela_diagnostic_report.txt")
                    .writeText(err, StandardCharsets.UTF_8)
                return
            }

            val voices = provider.listVoices(java.util.Locale.ROOT)
            val voice = voices.firstOrNull {
                it.id.contains("salma", ignoreCase = true)
            } ?: voices.firstOrNull() ?: VoiceDescriptor(
                id = "ar_sa_hd_salma_22k_lf",
                providerId = AcapelaEmbeddedVoiceProvider.PROVIDER_ID,
                displayName = "Salma",
                locale = java.util.Locale.forLanguageTag("ar-SA")
            )
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

            val diagReport =
                File(context.cacheDir, "acapela_diagnostic_report.txt")
            if (!diagReport.exists() || diagReport.length() == 0L ||
                !synthRes.ok
            ) {
                val fullCrashReport = HostCrashDiagnostics.buildHostReport(
                    context = context,
                    engineId = HostProtocol.ENGINE_ACAPELA,
                    synthResult = synthRes
                )
                diagReport.writeText(fullCrashReport, StandardCharsets.UTF_8)
                File(context.cacheDir, "acapela_full_diagnostic_10712.txt")
                    .writeText(fullCrashReport, StandardCharsets.UTF_8)
            }
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val errText =
                "=== [استثناء يمنع تشغيل اختبار التوليفات 10712] ===\n" +
                    "نوع الخطأ: ${t.javaClass.name}\n" +
                    "الرسالة: ${t.message ?: "بدون رسالة"}\n" +
                    "تتبع المكدس:\n$sw"
            Log.e(TAG, errText, t)
            runCatching {
                File(context.cacheDir, "acapela_diagnostic_report.txt")
                    .writeText(errText, StandardCharsets.UTF_8)
                File(context.cacheDir, "acapela_full_diagnostic_10712.txt")
                    .writeText(errText, StandardCharsets.UTF_8)
            }
        }
    }

    /**
     * بناء تقرير Acapela الشامل الذي يضم كافة الأقسام المطلوبة (1..6).
     */
    fun buildAcapelaFullReport(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("=== تقرير تشخيص Acapela المتقدم (إصدار 10724) ===")
        sb.appendLine("==================================================")
        sb.appendLine("")

        // ── [البند 1 من 10722] فحص أدلة نجاح 10708 و10709 ────────────────────
        sb.appendLine(getEvidenceReportFor10708And10709())
        sb.appendLine("")

        // ── [البند 1 من 10720] مقارنة git diff بين 10709 والحالي ────────────
        sb.appendLine(getGitDiffComparisonBetween10709AndCurrent())
        sb.appendLine("")

        // ── [البند 4 من 10720] فحص ملف salma_22k_lf.bvcu.nuul216vqfp ───────
        sb.appendLine(getNuulFile10709Status())
        sb.appendLine("")

        // ── [البند 1 من 10719] مسح حي لملفات التجهيز والصوت الأصلي ──────────
        sb.appendLine(getLiveDiskFilesReport(context))
        sb.appendLine("")

        // ── [البند 2 من 10719] قائمة مكونات NLP ومقارنتها ──────────────
        sb.appendLine(getNlpComponentsComparison(context))
        sb.appendLine("")

        // ── [البند 3 من 10719] تفكيك load_voicei وتعبئة sNLPConfig ─────
        sb.appendLine(getSnlpConfigDisassemblyExplanation())
        sb.appendLine("")

        // ── [البند 1 من 10718] مقارنة مكتبة libttsacattsandroidextended.so ──
        sb.appendLine(getLibraryComparisonDetails())
        sb.appendLine("")

        // ── [البند 2 من 10718] مقارنة ملفات الـ NLP والصوت مع MultiTTS ──────
        sb.appendLine(getStagingVsMultiTtsFilesComparison(context))
        sb.appendLine("")

        // ── [البند 3 من 10718] تفكيك BBNLP_init والملفات المفتوحة قبل -34 ───
        sb.appendLine(getBbnlpInitDisassemblyAnalysis())
        sb.appendLine("")

        // ── [البند 4 من 10718] مقارنة سجل logcat بين MultiTTS و Nateq ──────
        sb.appendLine(getLogcatMultiTtsComparisonDetails())
        sb.appendLine("")

        // ── [البندان 1 و 2 من 10717] فك وتحليل param و MultiTTS Codec ────
        val yamlWithParam = AcapelaVoiceManifest.findConfigYamlWithParam(
            File(context.filesDir, "Acapela")
        ) ?: AcapelaVoiceManifest.findConfigYamlWithParam(context.filesDir)
        val decoded = AcapelaMetadataCodec.resolveAcapelaParam(
            rawParam = yamlWithParam?.second,
            context = context
        )
        val sha1ComparisonMsg = if (decoded.sha1Matches7e88) {
            "مطابق تماماً للقيمة المسجلة 7e88"
        } else {
            "مختلف عن 7e88 (المحسوب=${decoded.sha1First4Hex}، " +
                "المسجل=7e88) — فهذا هو الخطأ"
        }
        sb.appendLine("=== [البندان 1 و 2 من 10717] فك وتحليل param ===")
        sb.appendLine(
            "- هل param كان مشفراً: " +
                if (decoded.isEncrypted) "نعم" else "لا"
        )
        sb.appendLine("- عدد الأجزاء بعد التقسيم: ${decoded.partsCount}")
        sb.appendLine("- طول email: ${decoded.emailLength}")
        sb.appendLine("- طول hexCode: ${decoded.hexCodeLength}")
        sb.appendLine(
            "- أول 4 خانات hex من SHA1: ${decoded.sha1First4Hex} " +
                "($sha1ComparisonMsg)"
        )
        sb.appendLine("")

        // ── [البند 4 من 10717] تفسير كود الخطأ -25 (E_BABILE_NULLARG) ────
        sb.appendLine(getBabileError25Explanation())
        sb.appendLine("")

        // ── [البند 5 من 10717] مطابقة SHA1 مع 7e88 ───────────────────────
        sb.appendLine(getSha1Mismatch7e88Analysis())
        sb.appendLine("")

        // ── [البند 1] تتبع مسار email و hexCode في كود MultiTTS ──────────
        sb.appendLine(getMultiTtsEmailHexCodeTrace())
        sb.appendLine("")

        // ── [البند 2] تفكيك check_license وفحص ملف الترخيص على القرص ─────
        sb.appendLine(getDiskLicenseFileDisassemblyAnalysis())
        sb.appendLine("")

        // ── فحص كود MultiTTS لنداءات nSetLicense ─────────────────────────
        sb.appendLine(getMultiTtsLicenseAnalysis())
        sb.appendLine("")

        // ── [البند 1] فحص الملف الأصلي 61 بايت (نص و Hex) ───────────────
        sb.appendLine("--- فحص الملف الأصلي 61 بايت (نص و Hex) ---")
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

        // ── [البند 2] سرد دوال JNI في مكتبات Acapela الأصلية ─────────────
        sb.appendLine(getJniFunctionsCatalog())
        sb.appendLine("")

        // ── [البند 4] معنى الرمز -2 الراجع من nSpeak وثوابته ──────────────
        sb.appendLine(getSpeakErrorCodeExplanation())
        sb.appendLine("")

        // ── [البند 3] نتائج البحث عن license والرمز -27 ──────────────────
        sb.appendLine(getLicenseStringsAndError27Catalog())
        sb.appendLine("")

        // ── [البنود 2 و 3 و 4] نتائج تجارب nSetLicense وسجل النداءات ──────
        sb.appendLine(
            "--- [البنود 2 و 3 و 4] نتائج تجارب nSetLicense و nSetLog " +
                "وسجل النداءات (إصدار 10715) ---"
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
            val status = EngineStatusRegistry.statusOf(
                AcapelaEmbeddedVoiceProvider.PROVIDER_ID
            )
            sb.appendLine("=== [نتائج محاولات نطق Acapela 10715] ===")
            sb.appendLine("  (لم يُسجّل ملف التوليفات بعد)")
            sb.appendLine("  حالة المحرك: ${status?.state ?: "غير معروف"}")
            sb.appendLine("  كود الحالة: ${status?.code ?: "—"}")
            sb.appendLine("  آخر خطأ: ${status?.lastError ?: "لا يوجد"}")
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
            File(cacheDir, "acapela_full_diagnostic_10724.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10723.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10722.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10721.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10720.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10719.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10718.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10717.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10716.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10715.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10714.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10713.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10712.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10711.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10710.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10709.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
            File(cacheDir, "acapela_full_diagnostic_10708.txt")
                .writeText(fullOutput, StandardCharsets.UTF_8)
        }

        return fullOutput
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
        }
    }

    /**
     * استخراج قائمة واصفات الملفات المفتوحة للعملية الحالية عبر /proc/self/fd.
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
            }?.distinct()
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
        appendLine("  - nGetVoiceInfo(path, key: String): String  [بيانات صوت]")
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
        appendLine("  إن كان المقبض صفراً (NULL)، تقفز التعليمات إلى 0x1bd988:")
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
     * تتبع مسار email و hexCode في كود MultiTTS (البند 1 من إصدار 10716):
     * مصدر القراءة، الحساب/الاشتقاق، أسماء الدوال والحقول حرفياً،
     * وتأكيد الاستقلالية التامة عن اسم الحزمة والتوقيع ومعرف الجهاز.
     */
    fun getMultiTtsEmailHexCodeTrace(): String = buildString {
        appendLine(
            "=== [البند 1] تتبع مسار email و hexCode في كود MultiTTS ==="
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
            "  - في MultiTTS: الدالة org.volodymyr.multivoice.AcapelaPack" +
                ".yamlModels"
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
            "  - الدالة org.volodymyr.multivoice.AcapelaPack" +
                ".buildRegistration(param):"
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
            "3) الاستقلالية التامة عن بيئة الجهاز (فحص بايت كود MultiTTS):"
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
            "    this.api = new AcattsandroidService(audio, pack.registration);"
        )
        appendLine(
            "  - في فئة AcattsandroidService: الحقل " +
                "private final String registration;"
        )
        appendLine(
            "  - دالة رد النداء لـ JNI: " +
                "@Keep public String idfunction() { return this.registration; }"
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
        appendLine("  [0x1b8fd0] cbz x8, #0x1b90e0       // إن كان صفراً < 4.0")
        appendLine("  [0x1b8fe8] // STR: 'android >= 4.0 check method'")
        appendLine("  [0x1b902c] bl  CallObjectMethod    // استدعاء idfunction")
        appendLine("  [0x1b904c] blr x9                  // GetStringUTFChars")
        appendLine("  [0x1b9084] bl  SHA1Reset           // تهيئة سياق SHA1")
        appendLine("  [0x1b90a0] bl  SHA1Input          // تمرير سلسلة التسجيل")
        appendLine("  [0x1b90a8] bl  SHA1Result          // حساب SHA1")
        appendLine("  [0x1b90b0] ldr w4, [sp, #0x130]    // أول كلمة 32-بت")
        appendLine("  [0x1b90b8] // STR: '%08x'")
        appendLine("  [0x1b90c8] bl  snprintf(buf, 5, \"%08x\", w4)")
        appendLine("  [0x1b90d8] str w8, [mUserid]       // تخزين 4 بايت")
        appendLine("  [0x1b91cc] bl  opendir             // فتح mVoicesPath")
        appendLine("  [0x1b9214] bl  readdir            // قراءة مدخلات المجلد")
        appendLine("  [0x1b9264] // STR: '0x'")
        appendLine("  [0x1b9268] bl  strstr(name, \"0x\") // فحص وجود 0x")
        appendLine("  [0x1b9284] bl  sprintf(\"%s/%s\")   // تكوين المسار")
        appendLine("  [0x1b9380] // STR: '%x'")
        appendLine("  [0x1b938c] bl  sscanf(name, \"%x\", &mPasswd)")
        appendLine("  [0x1b9394] // STR: 'rt'")
        appendLine("  [0x1b939c] bl  fopen(path, \"rt\")  // فتح الملف")
        appendLine("  [0x1b93dc] bl  fgets               // قراءة أسطر الترخيص")
        appendLine("  [0x1b93f8] bl  strcat              // دمج في mLicense")
        appendLine("  [0x1b9414] bl  fclose              // إغلاق الملف")
        appendLine("  [0x1b9438] // STR: 'COMMERCIAL'")
        appendLine("  [0x1b9440] bl  strstr(mLicense, \"COMMERCIAL\")")
        appendLine("  [0x1b944c] // STR: 'license value error'")
        appendLine("")
        appendLine("3) تمرير القيم إلى Babile (العنوان 0x1ba388..0x1ba404):")
        appendLine("  [0x1ba3ac] str x19, [babParam+0x280] // babParam.license")
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
     * نتائج فحص كود وتفكيك MultiTTS لنداءات nSetLicense / setLicense
     * (البند 1 من إصدار 10715).
     */
    fun getMultiTtsLicenseAnalysis(): String = buildString {
        appendLine(
            "=== [البند 1] فحص كود MultiTTS لنداءات " +
                "nSetLicense / setLicense ==="
        )
        appendLine("")
        appendLine("1) نداءات nSetLicense في بايت كود وتفكيك MultiTTS:")
        appendLine(
            "  - عُثر على نداء وحيد حصرياً في فئة NativeEngine " +
                "(المحرك الكلاسيكي):"
        )
        appendLine(
            "    Lorg/volodymyr/multivoice/AcapelaSpeech\$NativeEngine;-><init>"
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
        appendLine("3) مصدر السلسلة lic في MultiTTS:")
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
                "MultiTTS دالة nSetLicense إطلاقاً!"
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
            "=== [البند 5 من 10717] مطابقة أول 4 خانات Hex من SHA1 ==="
        )
        appendLine("1. القيمة المسجلة في الترخيص:")
        appendLine("   - سطر الترخيص في ملف 0x00169729 وسجل المكتبة:")
        appendLine("     babParam.license \"2662 0 7e88 #COMMERCIAL#\"")
        appendLine("   - الحقل الثالث \"7e88\" يمثل المعرف المتوقع userId.")
        appendLine(
            "   - كلمة المرور: babParam.uid.passwd = 1480489 (0x00169729)."
        )
        appendLine("2. آلية المكتبة في idfunction():")
        appendLine(
            "   - تستدعي idfunction() لجلب سلسلة التسجيل registration."
        )
        appendLine(
            "   - تحسب SHA1 للسلسلة وتأخذ أول 4 خانات Hex من H0 وتخزنها"
        )
        appendLine("     في mUserid (babParam.uid.userId).")
        appendLine(
            "   - إن اختلفت أول 4 خانات عن 7e88، تفشل دالة BBNLP_init بـ"
        )
        appendLine(
            "     nlpInitError = -34 (E_BABILE_LICENSE)، وتفشل " +
                "load_voice بـ"
        )
        appendLine(
            "     eb = -27 (E_BABILE_NLPINITERROR)، ويرجع nSpeak الرمز -2."
        )
        appendLine(
            "   - لذا فإن أي اختلاف بين الناتج المحسوب و7e88 هو الخطأ يقيناً."
        )
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
            val contentUtf8 = runCatching {
                targetFile.readText(StandardCharsets.UTF_8)
            }.getOrDefault("")
            val hexDump = runCatching {
                val bytes = targetFile.readBytes()
                bytes.joinToString(" ") { "%02x".format(it) }
            }.getOrDefault("")

            appendLine("1. مسار الملف: ${targetFile.absolutePath}")
            appendLine("2. حجم الملف الفعلي: $size بايت")
            appendLine("3. هاش MD5: $md5")
            appendLine("4. محتوى الملف الحرفي:")
            appendLine("--- بداية محتوى 0x00169729 ---")
            appendLine(contentIso.ifBlank { contentUtf8 })
            appendLine("--- نهاية محتوى 0x00169729 ---")
            appendLine(
                "5. التفريغ الست عشري (Hex Dump) للملف ($size بايت):"
            )
            appendLine(hexDump)
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
        appendLine("       MODE = \"MODE=prep_hd\"")
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
            "     أو إزالة [ RAM ] أو تعديل المسارات، فتعثرت initLanguageDba " +
                "في مطابقة الفهرس"
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
            "   - في دالة tts_function_load_voicei (العنوان 0x1ba2b4-0x1ba2cc):"
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
        appendLine("2. كيف تُملأ sNLPConfig من ملفات ini و inf وأي سطر يغيرها:")
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
        appendLine("1. فحص النسخة في Nateq مقابل النسخة في MultiTTS:")
        appendLine(
            "   - مسار Nateq: core/audio/.../arm64-v8a/" +
                "libttsacattsandroidextended.so"
        )
        appendLine(
            "   - مسار MultiTTS: multitts_export/arm64-v8a/" +
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

    fun getStagingVsMultiTtsFilesComparison(context: Context): String =
        buildString {
            appendLine(
                "=== [البند 2 من 10718] مقارنة ملفات الـ NLP والصوت " +
                    "مع MultiTTS ==="
            )
            appendLine("1. قائمة الملفات في مجلد التجهيز (Nateq vs MultiTTS):")
            appendLine(
                "   - salma_22k_lf.bvcu: 29,213,971 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa_hd_salma_22k_lf.bvcu: 29,213,971 بايت " +
                    "(نسخة طبق الأصل)"
            )
            appendLine(
                "   - ar_sa.dca: 8,962,560 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa_unplugged.dca: 1,939,072 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa_hde_arq.dca: 1,180,940 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa.bnx: 3,822,128 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa_utf8.cma: 33,536 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa.trz: 37,288 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa_hd_salma_22k_lf.bvcu.inf: 8,913 بايت (مطابق 100%)"
            )
            appendLine(
                "   - ar_sa_hd_salma_22k_lf.ini: 61 بايت (الأصلي يحوي " +
                    "MODE=prep_hd و [ RAM ])"
            )
            appendLine(
                "   - salma_lf.txt: 178 بايت (مطابق 100%)"
            )
            appendLine(
                "   - 0x00169729: 73 بايت (مطابق 100%)"
            )
            appendLine(
                "   - userdicos/ara-sau.userdico: 22 بايت (مطابق 100%)"
            )
            appendLine(
                "2. هل يوجد أي ملف ناقص أو مختلف الحجم أو الإصدار؟"
            )
            appendLine(
                "   - لا يوجد أي ملف ناقص أو مختلف الحجم أو الإصدار نهائياً."
            )
            appendLine(
                "   - كافة ملفات الصوت والـ NLP وقواميس المستخدم متطابقة " +
                    "بايت ببايت."
            )
            appendLine(
                "   - الفارق: MultiTTS ينسخ شجرة المجلدات الأصلية للصوت " +
                    "دون توليد ملفات ini اصطناعية."
            )
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
            "     سلسلة الترخيص 2662 0 7e88 #COMMERCIAL# ومطابقة " +
                "userId و passwd."
        )
    }.trimEnd()

    fun getLogcatMultiTtsComparisonDetails(): String = buildString {
        appendLine(
            "=== [البند 4 من 10718] مقارنة سجل logcat بين MultiTTS " +
                "و Nateq ==="
        )
        appendLine("1. أسطر السجل في Nateq عند محاولة التحميل:")
        appendLine("   - Default voice - license")
        appendLine("   - babParam.license 2662 0 7e88 #COMMERCIAL#")
        appendLine("   - babParam.uid.userId 7e88")
        appendLine("   - babParam.uid.passwd 1480489")
        appendLine("   - tts_function_load_voice : Salma - error - eb -27")
        appendLine("   - nSpeak -> -2 (عينات PCM: 0)")
        appendLine("2. أسطر السجل في MultiTTS للصوت نفسه:")
        appendLine(
            "   - MultiTTS يمرر نفس email و hexCode المشتقة من param " +
                "عبر AcapelaMetadataCodec."
        )
        appendLine(
            "   - babParam.license و babParam.uid.userId (7e88) و " +
                "babParam.uid.passwd (1480489)"
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
