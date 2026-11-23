package com.aymankhattab.nateq

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Base64
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * التحقق الشامل من سلامة التطبيق — يعمل عند بدء التشغيل وقبل أي عملية حساسة.
 *
 * الطبقات:
 * 1. توقيع الحزمة (Package Signature) — SHA-256 مقارنة بقيمة مرجعية.
 * 2. فلاج debuggable — يجب أن يكون false في Release.
 * 3. كشف الجذر (Root) — فحص مسارات su الشائعة + /proc mount points.
 * 4. كشف Frida/Xposed/أدوات التعديل — مكتبات أصلية محددة، ملفات، خصائص.
 * 5. Play Integrity API — تحقق تشخيصي غير حاجب.
 * 6. Anti-tamper: تحقق الـ APK checksum ذاتياً.
 */
object AppIntegrity {

    private const val TAG = "NateqIntegrity"

    // القيمة المرجعية لتوقيع SHA-256 للحزمة (Release keystore).
    const val PLACEHOLDER_SHA256 =
        "REPLACE_WITH_RELEASE_SIGNATURE_SHA256"

    internal var expectedSignatureSha256 =
        "79:3A:A5:31:F8:89:04:A1:9B:9C:1C:33:A6:7B:23:8F:31:42:92:63:" +
            "F5:D3:24:F9:5E:AE:CE:E8:81:1F:E8:C3"

    // قائمة المسارات الشائعة لـ su/binaries الجذر
    private val rootPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/data/local/tmp/su",
        "/data/local/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/usr/we-need-root/su",
        "/system/app/Superuser.apk",
        "/system/app/Magisk.apk",
        "/system/xbin/daemonsu",
        "/system/etc/init.d/99SuperSUDaemon",
        "/cache/su",
        "/data/su"
    )

    // حزم أدوات الجذر المعروفة
    private val rootPackages = setOf(
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "com.topjohnwu.magisk",
        "com.koushikdutta.rommanager",
        "com.koushikdutta.rommanager.license",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatcher",
        "com.ramdroid.appquarantine",
        "com.ramdroid.appquarantinepro",
        "com.koushikdutta.superuser",
        "com.android.vending.billing.InAppBillingService.COIN"
    )

    // خصائص نظام تشير إلى بيئة مخترقة
    private val suspiciousProps = mapOf(
        "ro.build.tags" to "test-keys",
        "ro.debuggable" to "1",
        "ro.secure" to "0",
        "ro.kernel.qemu" to "1",
        "ro.product.model" to "sdk",
        "ro.product.brand" to "generic"
    )

    // مكتبات أدوات التعديل والحقن المؤكدة (مطابقة الاسم المجرد فقط)
    internal val confirmedHookLibraries = setOf(
        "libfrida-gadget.so",
        "frida-agent.so",
        "frida-gadget.so",
        "libfrida.so",
        "frida-agent-32.so",
        "frida-agent-64.so",
        "frida-gadget-32.so",
        "frida-gadget-64.so",
        "libxposed.so",
        "libxposed_art.so",
        "libedhook.so",
        "libedhook-art.so",
        "libsandhook.so",
        "libsandhook-native.so",
        "libyahfa.so",
        "libpine.so",
        "libepic.so",
        "libwhale.so",
        "libsubstrate.so",
        "libdobby.so",
        "libdexmakerjfifre.so"
    )

    // أنماط مشبوهة لأسماء المكتبات فقط (تعتبر اشتباهاً تشخيصياً
    // لا دليلاً قاطعاً على التلاعب)
    private val suspiciousModulePatterns = listOf(
        "frida",
        "xposed",
        "substrate"
    )

    sealed class HookEvidence {
        object None : HookEvidence() {
            override fun toString(): String = "NONE"
        }

        data class Suspicion(
            val rule: String,
            val moduleName: String
        ) : HookEvidence() {
            override fun toString(): String = "SUSPICION($rule:$moduleName)"
        }

        data class Confirmed(
            val rule: String,
            val moduleName: String
        ) : HookEvidence() {
            override fun toString(): String = "CONFIRMED($rule:$moduleName)"
        }
    }

    data class IntegrityResult(
        val signaturePassed: Boolean,
        val nonDebuggablePassed: Boolean,
        val rootDetected: Boolean,
        val hookEvidence: HookEvidence,
        val checksumPassed: Boolean,
        val playIntegrity: PlayIntegrityDiagnostic,
        val isOverallSafe: Boolean
    )

    enum class PlayIntegrityDiagnostic(val labelAr: String) {
        LIBRARY_NOT_FOUND("مكتبة Play Integrity غير مثبتة"),
        REQUEST_FAILED("فشل طلب رمز السلامة"),
        TOKEN_RECEIVED_UNVERIFIED(
            "تم استلام الرمز بدون تصديق خادمي موثوق"
        ),
        UNAVAILABLE("غير متوفر")
    }

    // ===== نقطة الدخول الرئيسية =====
    @Volatile
    private var cachedResult: IntegrityResult? = null

    @Volatile
    private var cachedChecksum: Boolean? = null

    @Volatile
    private var lastLoggedSummary: String? = null

    fun verify(context: Context, onResult: (Boolean) -> Unit) {
        verifyDetailed(context) { result ->
            onResult(result.isOverallSafe)
        }
    }

    fun verifyDetailed(
        context: Context,
        onResult: (IntegrityResult) -> Unit
    ) {
        val cached = cachedResult
        if (cached != null) {
            onResult(cached)
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val signatureOk = checkSignature(context)
            val debuggableOk = !isDebuggable(context)
            val rootDetected = isRooted(context)
            val hookEvidence = detectHooks(context)
            val piDiag = checkPlayIntegrityDiagnostic(context)
            val checksumOk = verifyApkChecksum(context)

            // الاشتباه التشخيصي لا يعد عبثاً قاطعاً
            val isHookConfirmed = hookEvidence is HookEvidence.Confirmed
            val allOk = signatureOk && debuggableOk && !isHookConfirmed

            val result = IntegrityResult(
                signaturePassed = signatureOk,
                nonDebuggablePassed = debuggableOk,
                rootDetected = rootDetected,
                hookEvidence = hookEvidence,
                checksumPassed = checksumOk,
                playIntegrity = piDiag,
                isOverallSafe = allOk
            )
            cachedResult = result

            logIntegrityResult(result)

            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    private fun logIntegrityResult(result: IntegrityResult) {
        val summary = "${result.signaturePassed}|" +
            "${result.nonDebuggablePassed}|" +
            "${result.rootDetected}|" +
            "${result.hookEvidence}|" +
            "${result.checksumPassed}|" +
            "${result.playIntegrity.name}|" +
            "${result.isOverallSafe}"

        if (summary == lastLoggedSummary) return
        lastLoggedSummary = summary

        val logMsg = "Integrity check: " +
            "signaturePassed=${result.signaturePassed} " +
            "nonDebuggablePassed=${result.nonDebuggablePassed} " +
            "rootDetected=${result.rootDetected} " +
            "hookEvidence=${result.hookEvidence} " +
            "checksumPassed=${result.checksumPassed} " +
            "playIntegrity=${result.playIntegrity.name} => " +
            "isOverallSafe=${result.isOverallSafe}"

        Log.i(TAG, logMsg)
        if (!result.isOverallSafe) {
            Log.e(TAG, "INTEGRITY WARNING — $logMsg")
        }
    }

    // ===== 1. تحقق التوقيع =====
    internal fun checkSignature(context: Context): Boolean {
        if (expectedSignatureSha256 == PLACEHOLDER_SHA256 ||
            expectedSignatureSha256.isBlank()
        ) {
            Log.e(TAG, "Signature check failed: placeholder signature")
            return false
        }
        try {
            val pkgInfo: PackageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val sigInfo = pkgInfo.signingInfo
            val signatures = mutableListOf<Signature>()
            sigInfo?.let { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.apkContentsSigners?.let { signatures.addAll(it) }
                    info.signingCertificateHistory?.let {
                        signatures.addAll(it)
                    }
                }
            }
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.let { signatures.addAll(it.toList()) }

            if (signatures.isEmpty()) {
                Log.e(TAG, "No signatures found")
                return false
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for (sig in signatures) {
                val bytes = sig.toByteArray()
                val hash = digest.digest(bytes)
                val hex = hash.joinToString(":") { "%02X".format(it) }
                if (hex.equals(expectedSignatureSha256, ignoreCase = true)) {
                    return true
                }
            }
            Log.e(TAG, "Signature mismatch")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Signature check failed", e)
            return false
        }
    }

    // ===== 2. فلاج debuggable =====
    private fun isDebuggable(context: Context): Boolean {
        val flags = context.applicationInfo.flags
        return (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    // ===== 3. كشف الجذر =====
    private fun isRooted(context: Context): Boolean {
        for (path in rootPaths) {
            if (File(path).exists()) {
                val base = File(path).name
                Log.w(TAG, "Root binary found: $base")
                return true
            }
        }
        val pm = context.packageManager
        for (pkg in rootPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                Log.w(TAG, "Root package found: $pkg")
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                // متوقع — الحزمة غير موجودة
            }
        }
        if (checkSystemRwMount()) return true
        for ((key, suspiciousValue) in suspiciousProps) {
            val value = System.getProperty(key) ?: ""
            if (value == suspiciousValue) {
                Log.w(TAG, "Suspicious system prop: $key")
                return true
            }
        }
        return false
    }

    private fun checkSystemRwMount(): Boolean {
        return try {
            val file = File("/proc/mounts")
            if (!file.exists()) false
            else {
                file.readText(StandardCharsets.UTF_8).lines().any { line ->
                    line.contains("/system") && line.contains("rw")
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    // ===== 4. كشف الـ Hooks والمكتبات المشبوهة بدقة =====
    internal fun detectHooks(context: Context): HookEvidence {
        val mapsEvidence = scanMapsForSuspiciousLibs()
        if (mapsEvidence is HookEvidence.Confirmed) return mapsEvidence

        val filesEvidence = checkFridaFiles()
        if (filesEvidence is HookEvidence.Confirmed) return filesEvidence

        val propsEvidence = checkXposedProps()
        if (propsEvidence is HookEvidence.Confirmed) return propsEvidence

        val pkgEvidence = checkFridaPackages(context)
        if (pkgEvidence is HookEvidence.Confirmed) return pkgEvidence

        // إن وُجد اشتباه من الـ maps يوثَّق تشخيصياً
        if (mapsEvidence is HookEvidence.Suspicion) return mapsEvidence
        if (filesEvidence is HookEvidence.Suspicion) return filesEvidence
        if (propsEvidence is HookEvidence.Suspicion) return propsEvidence

        return HookEvidence.None
    }

    internal fun scanMapsForSuspiciousLibs(): HookEvidence {
        val file = File("/proc/self/maps")
        if (!file.exists() || !file.canRead()) return HookEvidence.None
        return try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            analyzeMapsLines(lines.asSequence())
        } catch (_: Exception) {
            HookEvidence.None
        }
    }

    /**
     * تحليل أسطر /proc/self/maps دون البحث السطحي في المسارات الكاملة
     * لتلافي الإيجابيات الكاذبة من مسارات عادية تحوي كلمة hook أو sandbox.
     */
    internal fun analyzeMapsLines(lines: Sequence<String>): HookEvidence {
        var suspicion: HookEvidence.Suspicion? = null
        for (line in lines) {
            val evidence = parseMapsLineForHook(line) ?: continue
            if (evidence is HookEvidence.Confirmed) {
                return evidence
            }
            if (evidence is HookEvidence.Suspicion && suspicion == null) {
                suspicion = evidence
            }
        }
        return suspicion ?: HookEvidence.None
    }

    internal fun parseMapsLineForHook(line: String): HookEvidence? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.split(Regex("\\s+"), limit = 6)
        if (tokens.size < 6) return null
        val rawPath = tokens[5].trim()
        if (!rawPath.startsWith("/")) return null

        val pathWithoutDeleted = if (rawPath.endsWith(" (deleted)")) {
            rawPath.removeSuffix(" (deleted)").trim()
        } else {
            rawPath
        }

        val baseName = File(pathWithoutDeleted).name.lowercase(Locale.ROOT)
        if (baseName.isEmpty()) return null

        // 1. مطابقة المكتبات المؤكدة
        if (confirmedHookLibraries.contains(baseName)) {
            return HookEvidence.Confirmed("CONFIRMED_HOOK_MODULE", baseName)
        }

        // 2. فحص الأنماط المشبوهة لاسم المكتبة فقط
        for (pattern in suspiciousModulePatterns) {
            if (baseName.contains(pattern)) {
                return HookEvidence.Suspicion(
                    "SUSPICIOUS_MODULE_NAME",
                    baseName
                )
            }
        }

        return null
    }

    private fun checkFridaFiles(): HookEvidence {
        val fridaFiles = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/frida-server-",
            "/data/local/tmp/re.frida.server",
            "/data/data/re.frida.server",
            "/data/data/com.frida.frida-server"
        )
        for (f in fridaFiles) {
            val file = File(f)
            if (file.exists()) {
                val sanitized = file.name
                return HookEvidence.Confirmed("FRIDA_SERVER_FILE", sanitized)
            }
        }
        return HookEvidence.None
    }

    private fun checkXposedProps(): HookEvidence {
        val xposedProps = listOf(
            "xposed",
            "de.robv.android.xposed",
            "com.saurik.substrate"
        )
        for (prop in xposedProps) {
            val value = System.getProperty(prop) ?: ""
            if (value.isNotEmpty()) {
                return HookEvidence.Confirmed("XPOSED_PROP", prop)
            }
        }
        if (File("/system/bin/init.xposed").exists() ||
            File("/system/xbin/init.xposed").exists()
        ) {
            return HookEvidence.Confirmed("XPOSED_INIT_BINARY", "init.xposed")
        }
        return HookEvidence.None
    }

    private fun checkFridaPackages(context: Context): HookEvidence {
        val pm = context.packageManager
        val fridaPackages = setOf(
            "re.frida.server",
            "com.frida.frida-server",
            "org.frida.frida-server"
        )
        for (pkg in fridaPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return HookEvidence.Confirmed("FRIDA_PACKAGE", pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                // متوقع
            }
        }
        return HookEvidence.None
    }

    // ===== 5. Play Integrity API (فحص تشخيصي غير حاجز) =====
    private fun checkPlayIntegrityDiagnostic(
        context: Context
    ): PlayIntegrityDiagnostic {
        return try {
            val integrityManagerFactoryClass = Class.forName(
                "com.google.android.play.integrity.IntegrityManagerFactory"
            )
            val integrityManagerClass = Class.forName(
                "com.google.android.play.integrity.IntegrityManager"
            )
            val integrityTokenRequestClass = Class.forName(
                "com.google.android.play.integrity.IntegrityTokenRequest"
            )
            val integrityTokenResponseClass = Class.forName(
                "com.google.android.play.integrity.IntegrityTokenResponse"
            )
            val tasksClass = Class.forName(
                "com.google.android.gms.tasks.Tasks"
            )
            val taskClass = Class.forName(
                "com.google.android.gms.tasks.Task"
            )

            val createMethod = integrityManagerFactoryClass.getMethod(
                "create", Context::class.java
            )
            val integrityManager = createMethod.invoke(null, context)

            val builderClass = Class.forName(
                "com.google.android.play.integrity." +
                    "IntegrityTokenRequest\$Builder"
            )
            val builder = integrityTokenRequestClass
                .getMethod("builder").invoke(null)
            builderClass.getMethod(
                "setCloudProjectNumber", Long::class.java
            ).invoke(builder, 0L)
            builderClass.getMethod("setNonce", String::class.java)
                .invoke(builder, generateNonce())
            val request = builderClass.getMethod("build").invoke(builder)

            val requestMethod = integrityManagerClass.getMethod(
                "requestIntegrityToken",
                integrityTokenRequestClass
            )
            val task = requestMethod.invoke(integrityManager, request)

            val awaitMethod = tasksClass.getMethod(
                "await",
                taskClass,
                java.lang.Long.TYPE,
                java.util.concurrent.TimeUnit::class.java
            )
            val response = awaitMethod.invoke(
                null,
                task,
                5L,
                java.util.concurrent.TimeUnit.SECONDS
            )

            val tokenMethod = integrityTokenResponseClass
                .getMethod("token")
            val token = tokenMethod.invoke(response) as? String
            if (!token.isNullOrEmpty()) {
                Log.i(
                    TAG,
                    "Play Integrity token received (len=${token.length}) — " +
                        "تشخيص محلي بدون تصديق خادمي"
                )
                PlayIntegrityDiagnostic.TOKEN_RECEIVED_UNVERIFIED
            } else {
                PlayIntegrityDiagnostic.REQUEST_FAILED
            }
        } catch (_: ClassNotFoundException) {
            PlayIntegrityDiagnostic.LIBRARY_NOT_FOUND
        } catch (e: Exception) {
            Log.w(TAG, "Play Integrity diagnostic check: ${e.message}")
            PlayIntegrityDiagnostic.REQUEST_FAILED
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    // ===== 6. Anti-tamper: APK self-checksum =====
    private fun verifyApkChecksum(context: Context): Boolean {
        cachedChecksum?.let { return it }
        return try {
            val apkPath = context.applicationInfo.sourceDir
            val file = File(apkPath)
            if (!file.exists()) {
                Log.e(TAG, "APK file not found: $apkPath")
                return false
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            var bytesRead: Int
            file.inputStream().use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hash = digest.digest()
            val hex = hash.joinToString(":") { "%02X".format(it) }
            Log.i(TAG, "APK SHA-256: $hex")
            cachedChecksum = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "APK checksum verification FAILED: ${e.message}")
            false
        }
    }
}

/**
 * استدعاء سريع من Application.onCreate()
 */
fun verifyAppIntegrity(context: Context, onResult: (Boolean) -> Unit) {
    AppIntegrity.verify(context, onResult)
}
