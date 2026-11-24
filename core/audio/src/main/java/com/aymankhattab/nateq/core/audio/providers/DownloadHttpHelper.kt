package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

/**
 * مساعد اتصالات التنزيل وفحص التحويلات واستئناف التنزيل (ع٣٠-6 و ع٣٠-7).
 */
object DownloadHttpHelper {

    private const val TAG = "DownloadHttpHelper"
    private const val ALLOWED_CLEARTEXT_HOST =
        "downloads.codefactoryglobal.com"

    @androidx.annotation.VisibleForTesting
    internal var allowedCleartextHostForTesting: String? = null

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
     * التحقق من مساحة التخزين المتاحة مقابل الحجم المطلوب (مؤقت + مستخرج).
     */
    fun checkStorageSpace(dir: File, requiredMb: Int) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val stat = StatFs(dir.absolutePath)
            val availableBytes = stat.availableBytes
            val requiredBytes = requiredMb * 2L * 1024 * 1024
            if (availableBytes < requiredBytes) {
                val availMb = availableBytes / (1024 * 1024)
                val reqMb = requiredMb * 2
                throw DownloadError(
                    DownloadError.NO_SPACE,
                    "المساحة المتاحة (${availMb}MB) أقل من المطلوب (${reqMb}MB)"
                )
            }
        } catch (e: DownloadError) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "StatFs check skipped for ${dir.path}: ${t.message}")
        }
    }

    /**
     * التحقق من أن البروتوكول آمن https
     * ويمنع تماماً التراجع (downgrade) من https إلى http عند التحويلات.
     */
    fun validateProtocol(
        url: URL,
        isRedirect: Boolean = false,
        initialWasHttps: Boolean = false
    ) {
        val protocol = url.protocol.lowercase()
        val host = url.host.lowercase()
        if (protocol == "https") {
            return
        }
        if (initialWasHttps && protocol == "http") {
            throw DownloadError(
                DownloadError.REDIRECT_BLOCKED,
                "Protocol downgrade from HTTPS to HTTP blocked for host: $host"
            )
        }
        if (protocol == "http" &&
            (host == ALLOWED_CLEARTEXT_HOST ||
                host == allowedCleartextHostForTesting)
        ) {
            return
        }
        throw DownloadError(
            DownloadError.REDIRECT_BLOCKED,
            "Cleartext HTTP is blocked for host: $host"
        )
    }

    /**
     * فتح اتصال HTTP مع معالجة التحويلات يدوياً وتتبع حتى 5 قفزات (ع٣٠-6)
     * ودعم رأس Range وIf-Range للاستئناف (ع٣٠-7).
     */
    fun openWithManualRedirects(
        urlString: String,
        maxHops: Int = 5,
        requestMethod: String = "GET",
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 30_000,
        rangeBytes: Long = 0L,
        ifRange: String? = null
    ): HttpURLConnection {
        var currentUrl = try {
            URL(urlString)
        } catch (e: Exception) {
            throw DownloadError(
                DownloadError.NETWORK,
                "Invalid URL: $urlString (${e.message})"
            )
        }

        val initialWasHttps = currentUrl.protocol.equals("https", true)
        validateProtocol(
            currentUrl,
            isRedirect = false,
            initialWasHttps = initialWasHttps
        )

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
                    if (!ifRange.isNullOrBlank()) {
                        conn.setRequestProperty("If-Range", ifRange)
                    }
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

                validateProtocol(
                    nextUrl,
                    isRedirect = true,
                    initialWasHttps = initialWasHttps
                )
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
     * filesDir/downloads/<id>.part مع التحقق من ETag وIf-Range والحجم النهائي.
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
        val maxAttempts = 3
        var lastError: Throwable? = null
        val metaFile = File(partFile.parentFile, "${partFile.name}.meta")

        for (attempt in 1..maxAttempts) {
            try {
                partFile.parentFile?.mkdirs()
                val initialBytes =
                    if (partFile.exists()) partFile.length() else 0L

                var savedEtag: String? = null
                var savedLastModified: String? = null
                var savedTotal: Long = -1L
                if (initialBytes > 0L && metaFile.isFile) {
                    val lines = runCatching {
                        metaFile.readLines()
                    }.getOrNull()
                    savedEtag = lines?.getOrNull(0)?.takeIf { it.isNotBlank() }
                    savedLastModified = lines?.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                    savedTotal = lines?.getOrNull(2)?.toLongOrNull() ?: -1L
                }
                val ifRange = savedEtag ?: savedLastModified

                var conn: HttpURLConnection
                var isResume = false
                var crTotal = -1L
                try {
                    conn = openWithManualRedirects(
                        urlString = urlString,
                        rangeBytes = initialBytes,
                        ifRange = ifRange
                    )
                    var respCode = conn.responseCode
                    if (respCode == 206) {
                        isResume = true
                        val cr = conn.getHeaderField("Content-Range")
                        val newEtag = conn.getHeaderField("ETag")
                        var resumeValid = true
                        if (cr.isNullOrBlank()) {
                            resumeValid = false
                        } else {
                            val match = Regex(
                                """bytes\s+(\d+)-(\d+)/(\d+|\*)"""
                            ).find(cr)
                            val startByte = match?.groupValues?.getOrNull(1)
                                ?.toLongOrNull() ?: -1L
                            crTotal = match?.groupValues?.getOrNull(3)
                                ?.toLongOrNull() ?: -1L
                            if (startByte != initialBytes || crTotal <= 0L) {
                                resumeValid = false
                            }
                        }
                        if (savedEtag != null && newEtag != null &&
                            savedEtag != newEtag
                        ) {
                            resumeValid = false
                        }
                        // رفض استئناف 206: إعادة الطلب من الصفر (البند 29)
                        if (!resumeValid) {
                            Log.w(
                                TAG,
                                "رفض استئناف 206 لتغير ETag أو عدم تطابق " +
                                    "Content-Range؛ إعادة الطلب من الصفر"
                            )
                            conn.disconnect()
                            partFile.delete()
                            metaFile.delete()
                            isResume = false
                            conn = openWithManualRedirects(
                                urlString = urlString,
                                rangeBytes = 0L,
                                ifRange = null
                            )
                            respCode = conn.responseCode
                        }
                    } else if (respCode == 416) {
                        conn.disconnect()
                        partFile.delete()
                        metaFile.delete()
                        isResume = false
                        conn = openWithManualRedirects(
                            urlString = urlString,
                            rangeBytes = 0L
                        )
                        respCode = conn.responseCode
                    } else if (respCode == 200 && initialBytes > 0L) {
                        Log.w(
                            TAG,
                            "الخادم رد بـ 200 بدل 206 أو تغير المورد؛ " +
                                "الاستئناف غير ممكن، البدء من الصفر"
                        )
                        isResume = false
                    }
                } catch (e: DownloadError) {
                    if (e.code == "HTTP_416") {
                        partFile.delete()
                        metaFile.delete()
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
                        metaFile.delete()
                    }

                    val contentLen = conn.contentLengthLong
                    val totalBytes = if (appendMode && crTotal > 0L) {
                        crTotal
                    } else if (appendMode && contentLen > 0L) {
                        baseDownloaded + contentLen
                    } else if (!appendMode && contentLen > 0L) {
                        contentLen
                    } else if (savedTotal > 0L) {
                        savedTotal
                    } else {
                        -1L
                    }

                    val currentEtag = conn.getHeaderField("ETag")
                        ?: savedEtag.orEmpty()
                    val currentLm = conn.getHeaderField("Last-Modified")
                        ?: savedLastModified.orEmpty()
                    runCatching {
                        metaFile.writeText(
                            "$currentEtag\n$currentLm\n$totalBytes",
                            Charsets.UTF_8
                        )
                    }

                    var currentDownloaded = baseDownloaded

                    BufferedInputStream(
                        conn.inputStream,
                        256 * 1024
                    ).use { input ->
                        FileOutputStream(partFile, appendMode).use { output ->
                            val buffer = ByteArray(256 * 1024)
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
                    if (totalBytes > 0 && partFile.length() != totalBytes) {
                        partFile.delete()
                        metaFile.delete()
                        throw DownloadError(
                            DownloadError.NETWORK,
                            "الحجم النهائي للملف المُنزَّل لا يطابق المطلوب"
                        )
                    }
                    metaFile.delete()
                    return@withContext Result.success(partFile)
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                if (!currentCoroutineContext().isActive) {
                    return@withContext Result.failure(
                        DownloadError(
                            DownloadError.NETWORK,
                            "Download cancelled"
                        )
                    )
                }
                lastError = t
                if (attempt < maxAttempts) {
                    Log.w(
                        TAG,
                        "محاولة تنزيل $attempt فشلت: ${t.message}؛ " +
                            "إعادة المحاولة تلقائياً بعد مهلة قصيرة"
                    )
                    delay(attempt * 500L)
                }
            }
        }

        val err = if (lastError is DownloadError) lastError else {
            DownloadError(
                DownloadError.NETWORK,
                lastError?.message ?: "Network error"
            )
        }
        Result.failure(err)
    }
}
