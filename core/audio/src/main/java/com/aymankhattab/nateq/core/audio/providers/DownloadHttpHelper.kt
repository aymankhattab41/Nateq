package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.SSLException

/**
 * مساعد اتصالات التنزيل وفحص التحويلات واستئناف التنزيل (ع٣٠-6 و ع٣٠-7).
 */
object DownloadHttpHelper {

    private const val TAG = "DownloadHttpHelper"

    /** مجلد التنزيلات المؤقتة الداخلي الدائم (ع٣٠-7). */
    fun getDownloadsDir(context: Context): File {
        val dir = File(context.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** نتيجة اختبار الاتصال بالخادم */
    data class ConnectionTestResult(
        val finalUrl: String,
        val responseCode: Int,
        val contentLength: Long,
        val contentType: String
    )



    /**
     * يتحقق من توفر مساحة تخزين كافية (بالميغابايت) في مسار الوجهة.
     */
    fun checkStorageSpace(destination: File, requiredMb: Int) {
        if (requiredMb <= 0) return
        try {
            val dir = if (destination.exists() && destination.isDirectory) {
                destination
            } else {
                destination.parentFile ?: destination
            }
            val stat = StatFs(dir.absolutePath)
            val available = stat.availableBytes
            val required = requiredMb.toLong() * 1024L * 1024L
            if (available < required) {
                throw DownloadError(
                    DownloadError.NO_SPACE,
                    "المساحة المتاحة غير كافية"
                )
            }
        } catch (e: DownloadError) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "تعذر فحص المساحة: ${t.message}")
        }
    }

    /** حساب بصمة SHA-256 لملف (سداسي عشري بحروف صغيرة). */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** يتحقق من تطابق بصمة الملف مع القيمة المتوقعة (غير حسّاس لحالة الأحرف). */
    fun verifySha256(file: File, expectedHex: String): Boolean {
        val actual = sha256Hex(file)
        return actual.equals(expectedHex.trim(), ignoreCase = true)
    }

    /**
     * السماح ببروتوكول https فقط (بند 6: إزالة استثناء HTTP لحزم التنزيل).
     */
    fun validateProtocol(url: URL) {
        val protocol = url.protocol.lowercase()
        val host = url.host.lowercase()
        if (protocol == "https") {
            return
        }
        throw DownloadError(
            DownloadError.REDIRECT_BLOCKED,
            "Cleartext HTTP is blocked for host: $host"
        )
    }

    /**
     * فتح اتصال HTTP مع معالجة التحويلات يدوياً وتتبع حتى 5 قفزات (ع٣٠-6)
     * ودعم رأس Range للاستئناف (ع٣٠-7).
     */
    fun openWithManualRedirects(
        urlString: String,
        maxHops: Int = 5,
        requestMethod: String = "GET",
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 30_000,
        rangeBytes: Long = 0L
    ): HttpURLConnection {
        var currentUrl = try {
            URL(urlString)
        } catch (e: Exception) {
            throw DownloadError(
                DownloadError.NETWORK,
                "Invalid URL: $urlString (${e.message})"
            )
        }

        validateProtocol(currentUrl)

        var hops = 0
        while (hops < maxHops) {
            val conn: HttpURLConnection
            try {
                conn = currentUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.instanceFollowRedirects = false
                conn.requestMethod = requestMethod
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android; Mobile)"
                )
                if (rangeBytes > 0 && requestMethod == "GET") {
                    conn.setRequestProperty("Range", "bytes=$rangeBytes-")
                }
                conn.connect()
            } catch (st: SocketTimeoutException) {
                throw DownloadError(
                    DownloadError.TIMEOUT,
                    "انتهت مهلة الاتصال (${st.message})"
                )
            } catch (ssl: SSLException) {
                throw DownloadError(
                    DownloadError.NETWORK,
                    "SSL error: ${ssl.message}"
                )
            } catch (io: IOException) {
                if (io is DownloadError) throw io
                throw DownloadError(
                    DownloadError.NETWORK,
                    "Network error: ${io.message}"
                )
            }

            val code = try {
                conn.responseCode
            } catch (st: SocketTimeoutException) {
                conn.disconnect()
                throw DownloadError(
                    DownloadError.TIMEOUT,
                    "انتهت مهلة قراءة الاستجابة (${st.message})"
                )
            } catch (io: IOException) {
                conn.disconnect()
                throw DownloadError(
                    DownloadError.NETWORK,
                    "Network error reading response code: ${io.message}"
                )
            }

            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw DownloadError(
                        DownloadError.NETWORK,
                        "Redirect without Location header (HTTP $code)"
                    )
                }

                val nextUrl = try {
                    URL(currentUrl, location)
                } catch (e: Exception) {
                    throw DownloadError(
                        DownloadError.NETWORK,
                        "Invalid redirect URL: $location"
                    )
                }

                Log.i(
                    TAG,
                    "Redirect hop ${hops + 1}: " +
                        "$currentUrl -> $nextUrl (HTTP $code)"
                )

                validateProtocol(nextUrl)
                currentUrl = nextUrl
                hops++
                continue
            }

            if (code !in 200..299) {
                conn.disconnect()
                throw DownloadError(
                    DownloadError.http(code),
                    "HTTP $code from $currentUrl"
                )
            }

            return conn
        }

        throw DownloadError(
            DownloadError.NETWORK,
            "تجاوز الحد الأقصى للتحويلات ($maxHops قفزات)"
        )
    }

    /**
     * اختبار الاتصال بالخادم عبر طلب HEAD يعرض الرمز والحجم المعلن (ع٣٠-6).
     */
    suspend fun testConnection(
        urlString: String
    ): Result<ConnectionTestResult> = withContext(Dispatchers.IO) {
        try {
            var conn: HttpURLConnection? = null
            try {
                conn = openWithManualRedirects(
                    urlString = urlString,
                    requestMethod = "HEAD"
                )
            } catch (e: DownloadError) {
                // إذا رفض الخادم HEAD برمز 405 نجرب GET
                if (e.code == "HTTP_405") {
                    conn = openWithManualRedirects(
                        urlString = urlString,
                        requestMethod = "GET"
                    )
                } else {
                    throw e
                }
            }

            val finalUrl = conn.url.toString()
            val code = conn.responseCode
            val length = conn.contentLengthLong
            val type = conn.contentType ?: "unknown"
            conn.disconnect()

            Result.success(
                ConnectionTestResult(
                    finalUrl = finalUrl,
                    responseCode = code,
                    contentLength = length,
                    contentType = type
                )
            )
        } catch (t: Throwable) {
            val err = if (t is DownloadError) t else {
                DownloadError(
                    DownloadError.NETWORK,
                    t.message ?: "Network error"
                )
            }
            Result.failure(err)
        }
    }

    /**
     * تنزيل ملف مع دعم الاستئناف عبر رأس Range وحفظ الملف المؤقت في
     * filesDir/downloads/<id>.part (ع٣٠-7).
     */
    suspend fun downloadToFileWithResume(
        urlString: String,
        partFile: File,
        onProgress: (
            downloadedBytes: Long,
            totalBytes: Long,
            percent: Int
        ) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            partFile.parentFile?.mkdirs()
            val initialBytes = if (partFile.exists()) partFile.length() else 0L

            var conn: HttpURLConnection
            var isResume = false
            try {
                conn = openWithManualRedirects(
                    urlString = urlString,
                    rangeBytes = initialBytes
                )
                if (conn.responseCode == 206) {
                    isResume = true
                } else if (conn.responseCode == 416) {
                    // النطاق غير صالح -> بدء من الصفر
                    conn.disconnect()
                    partFile.delete()
                    conn = openWithManualRedirects(
                        urlString = urlString,
                        rangeBytes = 0L
                    )
                }
            } catch (e: DownloadError) {
                if (e.code == "HTTP_416") {
                    partFile.delete()
                    conn = openWithManualRedirects(
                        urlString = urlString,
                        rangeBytes = 0L
                    )
                } else {
                    throw e
                }
            }

            try {
                val appendMode = isResume && (conn.responseCode == 206)
                val baseDownloaded = if (appendMode) initialBytes else 0L
                if (!appendMode && partFile.exists()) {
                    partFile.delete()
                }

                val contentLen = conn.contentLengthLong
                val totalBytes = if (contentLen > 0) {
                    baseDownloaded + contentLen
                } else {
                    -1L
                }

                var currentDownloaded = baseDownloaded

                BufferedInputStream(
                    conn.inputStream,
                    32 * 1024
                ).use { input ->
                    FileOutputStream(partFile, appendMode).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (
                            input.read(buffer).also { read = it } != -1
                        ) {
                            if (!currentCoroutineContext().isActive) {
                                output.flush()
                                throw DownloadError(
                                    DownloadError.NETWORK,
                                    "Download cancelled"
                                )
                            }
                            output.write(buffer, 0, read)
                            currentDownloaded += read
                            val pct = if (totalBytes > 0) {
                                ((currentDownloaded * 100) / totalBytes)
                                    .toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            onProgress(currentDownloaded, totalBytes, pct)
                        }
                        output.flush()
                    }
                }
                Result.success(partFile)
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            val err = if (t is DownloadError) t else {
                DownloadError(
                    DownloadError.NETWORK,
                    t.message ?: "Network error"
                )
            }
            Result.failure(err)
        }
    }
}
