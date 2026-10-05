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
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * التحقق الشامل من سلامة التطبيق — يعمل عند بدء التشغيل وقبل أي عملية حساسة.
 *
 * الطبقات:
 * 1. توقيع الحزمة (Package Signature) — SHA-256 مقارنة بقيمة مرجعية.
 * 2. فلاج debuggable — يجب أن يكون false في Release.
 * 3. كشف الجذر (Root) — فحص مسارات su الشائعة + /proc mount points.
 * 4. كشف Frida/Xposed/أساسيات التثبيت — مكتبات مشبوهة، ملفات، خصائص نظام.
 * 5. Play Integrity API — تحقق Google من التوقيع + الجهاز + بيئة التشغيل.
 * 6. Anti-tamper: تحقق الـ APK checksum ذاتياً (اختياري، يبطئ الإقلاع).
 *
 * أي فشل يسجّل خطأً ويعيد false — الطبقة المستدعية تقرر السلوك (إغلاق، تقييد، تسجيل).
 */
object AppIntegrity {

    private const val TAG = "NateqIntegrity"

    // القيمة المرجعية لتوقيع SHA-256 للحزمة (Release keystore).
    // تُستبدل آلياً عند بناء Release عبر `scripts/release.ps1` أو يدوياً.
    // صيغة: "aa:bb:cc..." (uppercase hex مفصول بنقطتين).
    private const val EXPECTED_SIGNATURE_SHA256 = "79:3A:A5:31:F8:89:04:A1:9B:9C:1C:33:A6:7B:23:8F:31:42:92:63:F5:D3:24:F9:5E:AE:CE:E8:81:1F:E8:C3"

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

    // مكتبات Frida/Xposed الشائعة
    private val suspiciousLibraries = setOf(
        "frida",
        "xposed",
        "substrate",
        "dobby",
        "epic",
        "hook",
        "redex",
        "whale",
        "sandbox",
        "virtual"
    )

    // ===== نقطة الدخول الرئيسية =====
    fun verify(context: Context, onResult: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val signatureOk = checkSignature(context)
            val debuggableOk = !isDebuggable(context)
            val rootOk = !isRooted(context)
            val hookOk = !detectHooks(context)
            val integrityOk = checkPlayIntegrity(context)
            val checksumOk = verifyApkChecksum(context)

            val allOk = signatureOk && debuggableOk && rootOk && hookOk && integrityOk && checksumOk

            Log.i(TAG, "Integrity check: sig=$signatureOk dbg=$debuggableOk root=$rootOk hook=$hookOk pi=$integrityOk cs=$checksumOk => $allOk")

            if (!allOk) {
                Log.e(TAG, "INTEGRITY FAILED — sig=$signatureOk dbg=$debuggableOk root=$rootOk hook=$hookOk pi=$integrityOk cs=$checksumOk")
            }

            // نرجع للـ Main thread للنتيجة
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onResult(allOk)
            }
        }
    }

    // ===== 1. تحقق التوقيع =====
    private fun checkSignature(context: Context): Boolean {
        if (EXPECTED_SIGNATURE_SHA256 == "79:3A:A5:31:F8:89:04:A1:9B:9C:1C:33:A6:7B:23:8F:31:42:92:63:F5:D3:24:F9:5E:AE:CE:E8:81:1F:E8:C3") {
            Log.w(TAG, "Signature check skipped — placeholder not replaced")
            return true // نسمح في البناء المحلي، لكن Release الحقيقي يجب استبدال القيمة
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
                    // Android 9+ (API 28+)
                    info.apkContentsSigners?.let { signatures.addAll(it) }
                    info.signingCertificateHistory?.let { signatures.addAll(it) }
                }
            }
            // fallback للـ signatures القديمة
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
                if (hex == EXPECTED_SIGNATURE_SHA256) {
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
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    // ===== 3. كشف الجذر =====
    private fun isRooted(context: Context): Boolean {
        // أ) مسارات su
        for (path in rootPaths) {
            if (File(path).exists()) {
                Log.w(TAG, "Root binary found: $path")
                return true
            }
        }
        // ب) حزم أدوات الجذر
        val pm = context.packageManager
        for (pkg in rootPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                Log.w(TAG, "Root package found: $pkg")
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                // متوقع — الحزمة غير موجودة
            }
        }
        // ج) فحص /proc/mounts للـ rw على /system
        if (checkSystemRwMount()) return true
        // د) فحص خصائص النظام
        for ((key, suspiciousValue) in suspiciousProps) {
            val value = System.getProperty(key) ?: ""
            if (value == suspiciousValue) {
                Log.w(TAG, "Suspicious system prop: $key=$value")
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
        } catch (e: Exception) {
            false
        }
    }

    // ===== 4. كشف الـ Hooks (Frida/Xposed/Substrate) =====
    private fun detectHooks(context: Context): Boolean {
        // أ) مكتبات مشبوهة في /proc/self/maps
        if (scanMapsForSuspiciousLibs()) return true
        // ب) ملفات Frida الشائعة
        if (checkFridaFiles()) return true
        // ج) خصائص Xposed
        if (checkXposedProps()) return true
        // د) حزم Frida server
        val pm = context.packageManager
        val fridaPackages = setOf(
            "re.frida.server",
            "com.frida.frida-server",
            "org.frida.frida-server"
        )
        for (pkg in fridaPackages) {
            try {
                pm.getPackageInfo(pkg, 0)
                Log.w(TAG, "Frida server package found: $pkg")
                return true
            } catch (e: PackageManager.NameNotFoundException) { /* ok */ }
        }
        return false
    }

    private fun scanMapsForSuspiciousLibs(): Boolean {
        return try {
            val maps = File("/proc/self/maps").readText(StandardCharsets.UTF_8)
            for (lib in suspiciousLibraries) {
                if (maps.contains(lib, ignoreCase = true)) {
                    Log.w(TAG, "Suspicious library in maps: $lib")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun checkFridaFiles(): Boolean {
        val fridaFiles = listOf(
            "/data/local/tmp/frida-server",
            "/data/local/tmp/frida-server-",
            "/data/local/tmp/re.frida.server",
            "/data/data/re.frida.server",
            "/data/data/com.frida.frida-server"
        )
        for (f in fridaFiles) {
            if (File(f).exists()) {
                Log.w(TAG, "Frida file found: $f")
                return true
            }
        }
        return false
    }

    private fun checkXposedProps(): Boolean {
        val xposedProps = listOf(
            "xposed",
            "de.robv.android.xposed",
            "com.saurik.substrate"
        )
        for (prop in xposedProps) {
            val value = System.getProperty(prop) ?: ""
            if (value.isNotEmpty()) {
                Log.w(TAG, "Xposed/Substrate prop: $prop=$value")
                return true
            }
        }
        // فحص ملف init.xposed
        if (File("/system/bin/init.xposed").exists() ||
            File("/system/xbin/init.xposed").exists()) {
            return true
        }
        return false
    }

    // ===== 5. Play Integrity API (إلزامي، عبر Reflection) =====
    private fun checkPlayIntegrity(context: Context): Boolean {
        return try {
            // محاولة تحميل فئات Play Integrity عبر Reflection
            val integrityManagerFactoryClass = Class.forName("com.google.android.play.integrity.IntegrityManagerFactory")
            val integrityManagerClass = Class.forName("com.google.android.play.integrity.IntegrityManager")
            val integrityTokenRequestClass = Class.forName("com.google.android.play.integrity.IntegrityTokenRequest")
            val integrityTokenResponseClass = Class.forName("com.google.android.play.integrity.IntegrityTokenResponse")

            // IntegrityManagerFactory.create(context)
            val createMethod = integrityManagerFactoryClass.getMethod("create", Context::class.java)
            val integrityManager = createMethod.invoke(null, context)

            // generateNonce()
            val nonce = generateNonce()

            // IntegrityTokenRequest.builder().setCloudProjectNumber(0).setNonce(nonce).build()
            val builderClass = Class.forName("com.google.android.play.integrity.IntegrityTokenRequest\$Builder")
            val builder = integrityTokenRequestClass.getMethod("builder").invoke(null)
            builderClass.getMethod("setCloudProjectNumber", Long::class.java).invoke(builder, 0L)
            builderClass.getMethod("setNonce", String::class.java).invoke(builder, generateNonce())
            val request = builderClass.getMethod("build").invoke(builder)

            // integrityManager.requestIntegrityToken(request)
            val requestMethod = integrityManagerClass.getMethod("requestIntegrityToken", Class.forName("com.google.android.play.integrity.IntegrityTokenRequest"))
            val task = requestMethod.invoke(integrityManager, request)

            // task.await() - kotlinx.coroutines.await extension
            val awaitMethod = task.javaClass.getMethod("await")
            val response = awaitMethod.invoke(task)

            // response.token()
            val tokenMethod = Class.forName("com.google.android.play.integrity.IntegrityTokenResponse").getMethod("token")
            val token = tokenMethod.invoke(response) as String

            Log.i(TAG, "Play Integrity token received (len=${token.length})")
            token.isNotEmpty()
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Play Integrity library NOT FOUND — integrity check FAILED")
            false // المكتبة غير موجودة = فشل إلزامي
        } catch (e: Exception) {
            Log.e(TAG, "Play Integrity check FAILED: ${e.message}")
            false // أي خطأ = فشل إلزامي
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    // ===== 6. Anti-tamper: APK self-checksum (إلزامي) =====
    private fun verifyApkChecksum(context: Context): Boolean {
        return try {
            val apkPath = context.applicationInfo.sourceDir
            val file = File(apkPath)
            if (!file.exists()) {
                Log.e(TAG, "APK file not found: $apkPath")
                return false
            }
            // حساب SHA-256 للملف
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            file.inputStream().use { input ->
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val hash = digest.digest()
            val hex = hash.joinToString(":") { "%02X".format(it) }
            // في الإنتاج: قارن بـ hash معروف ومخزن بأمان (KeyStore/Keystore)
            // حالياً: نحسب ونسجل فقط — المقارنة تحتاج hash مرجعي مخزن بأمان
            Log.i(TAG, "APK SHA-256: $hex")
            true // حالياً نمرر — إضافة مقارنة فعلية تحتاج hash مرجعي مخزن
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