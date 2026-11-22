package com.aymankhattab.nateq.core.audio.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * اختبارات استئناف التنزيل وتغير الموارد ومنع انحدار البروتوكول:
 * 1. منع downgrade من HTTPS إلى HTTP وحظر النطاقات غير المشفرة.
 * 2. استئناف التنزيل بنجاح عند مطابقة ETag واستجابة 206.
 * 3. إعادة التنزيل من الصفر عند تغير المورد (رد 200 أو اختلاف ETag).
 * 4. التحقق من تنظيف ملف .meta بعد اكتمال التنزيل.
 */
class DownloadHttpResumeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var server: HttpServer? = null
    private var serverPort: Int = 0

    @Before
    fun setUp() {
        DownloadHttpHelper.allowedCleartextHostForTesting = "127.0.0.1"
    }

    @After
    fun tearDown() {
        DownloadHttpHelper.allowedCleartextHostForTesting = null
        server?.stop(0)
    }

    @Test
    fun validateProtocol_httpsAllowed_httpBlocked() {
        // HTTPS مسموح دائماً
        DownloadHttpHelper.validateProtocol(
            URL("https://secure.example.com/file.zip")
        )

        // HTTP محظور للمضيفين العاديين
        try {
            DownloadHttpHelper.allowedCleartextHostForTesting = null
            DownloadHttpHelper.validateProtocol(
                URL("http://insecure.example.com/file.zip")
            )
            fail("يجب حظر بروتوكول HTTP غير المشفر")
        } catch (e: DownloadError) {
            assertEquals(DownloadError.REDIRECT_BLOCKED, e.code)
        }
    }

    @Test
    fun validateProtocol_downgradeFromHttpsToHttp_throwsError() {
        try {
            DownloadHttpHelper.validateProtocol(
                url = URL("http://any-host.com/file.zip"),
                isRedirect = true,
                initialWasHttps = true
            )
            fail("يجب حظر التحويل من HTTPS إلى HTTP")
        } catch (e: DownloadError) {
            assertEquals(DownloadError.REDIRECT_BLOCKED, e.code)
            assertTrue(e.message?.contains("downgrade") == true)
        }
    }

    @Test
    fun downloadToFileWithResume_successfulResumeOn206() = runBlocking {
        val fullData = ByteArray(100) { it.toByte() }
        val etag = "\"v1-hash\""

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server!!.createContext("/voice.zip", object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                val range = exchange.requestHeaders.getFirst("Range")
                val ifRange = exchange.requestHeaders.getFirst("If-Range")

                if (range == "bytes=40-" && ifRange == etag) {
                    val slice = fullData.copyOfRange(40, 100)
                    exchange.responseHeaders.set("ETag", etag)
                    exchange.responseHeaders.set(
                        "Content-Range",
                        "bytes 40-99/100"
                    )
                    exchange.sendResponseHeaders(206, slice.size.toLong())
                    exchange.responseBody.use { it.write(slice) }
                } else {
                    exchange.responseHeaders.set("ETag", etag)
                    exchange.sendResponseHeaders(200, fullData.size.toLong())
                    exchange.responseBody.use { it.write(fullData) }
                }
            }
        })
        server!!.start()
        serverPort = server!!.address.port

        val partFile = File(tmp.root, "voice.zip.part")
        val metaFile = File(tmp.root, "voice.zip.part.meta")

        // كتابة 40 بايت أولى محاكاة لتنزيل جزئي سابق
        partFile.writeBytes(fullData.copyOfRange(0, 40))
        metaFile.writeText("$etag\n\n100", Charsets.UTF_8)

        val result = DownloadHttpHelper.downloadToFileWithResume(
            urlString = "http://127.0.0.1:$serverPort/voice.zip",
            partFile = partFile,
            onProgress = { _, _, _ -> }
        )

        assertTrue(result.isSuccess)
        assertTrue(partFile.exists())
        assertEquals(100L, partFile.length())
        assertArrayEquals(fullData, partFile.readBytes())
        assertFalse(metaFile.exists())
    }

    @Test
    fun downloadToFileWithResume_restartsFromZeroOnHttp200() = runBlocking {
        val newData = ByteArray(100) { (it + 50).toByte() }
        val newEtag = "\"v2-new-hash\""

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server!!.createContext("/voice.zip", object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                // الخادم يرفض الاستئناف أو المورد تغير فيرد بـ 200
                // وبيانات جديدة
                exchange.responseHeaders.set("ETag", newEtag)
                exchange.sendResponseHeaders(200, newData.size.toLong())
                exchange.responseBody.use { it.write(newData) }
            }
        })
        server!!.start()
        serverPort = server!!.address.port

        val partFile = File(tmp.root, "voice.zip.part")
        val metaFile = File(tmp.root, "voice.zip.part.meta")

        // تنزيل جزئي قديم بمحتوى مخالف
        partFile.writeBytes(ByteArray(40) { 0x01 })
        metaFile.writeText("\"old-etag\"\n\n100", Charsets.UTF_8)

        val result = DownloadHttpHelper.downloadToFileWithResume(
            urlString = "http://127.0.0.1:$serverPort/voice.zip",
            partFile = partFile,
            onProgress = { _, _, _ -> }
        )

        assertTrue(result.isSuccess)
        assertTrue(partFile.exists())
        assertEquals(100L, partFile.length())
        // يجب أن يحتوي الملف على البيانات الجديدة بالكامل دون دمج للقديمة
        assertArrayEquals(newData, partFile.readBytes())
        assertFalse(metaFile.exists())
    }
}
