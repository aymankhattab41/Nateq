package com.aymankhattab.nateq.core.audio.enginehost

import android.os.ParcelFileDescriptor
import com.aymankhattab.nateq.core.audio.providers.VocalizerCfDecryptor
import com.nuance.android.vocalizer.internal.VocalizerFileInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * مزوّد ملفات بيانات Nuance Vocalizer (ع٣٧-2).
 *
 * يتيح فتح ملفات الصوت .dat كواصفات ParcelFileDescriptor
 * للقراءة فقط، مع تتبع الأوصاف المفتوحة وإغلاقها
 * عبر closeAssetFile(fd).
 */
class VocalizerDataProvider(
    datFiles: Map<String, File> = emptyMap(),
    private val decryptCacheDir: File? = null
) {
    private val filesMap = ConcurrentHashMap<String, File>(datFiles)
    private val openFiles = ConcurrentHashMap<Int, ParcelFileDescriptor>()

    /** ملفاتٌ فُكّ تشفيرها مؤقتاً (مفتاح الملف ← النسخة المفكوكة). */
    private val decryptedCache = ConcurrentHashMap<String, File>()

    private val fallbackFd =
        java.util.concurrent.atomic.AtomicInteger(1000)

    val datFiles: Map<String, File> get() = filesMap

    fun setDatFiles(files: Map<String, File>) {
        filesMap.clear()
        filesMap.putAll(files)
        decryptedCache.clear()
    }

    /**
     * يفتح ملف البيانات ويعيد معلوماته
     * (الواصف، startOffset=0، length=file.length()).
     *
     * الصيغة المعتمدة للمفتاح:
     * key = name.lowercase().replace('/', '_') + ".dat"
     */
    fun openDataFile(name: String?): VocalizerFileInfo? {
        if (name.isNullOrBlank()) return null

        val lowerNormalized = name.lowercase(Locale.ROOT).replace('/', '_')
        val key = if (lowerNormalized.endsWith(".dat")) {
            lowerNormalized
        } else {
            "$lowerNormalized.dat"
        }

        if (key in DataFolderScanner.EXCLUDED_DAT_FILES) {
            return null
        }

        val file = filesMap[key] ?: return null
        if (!file.exists() || !file.isFile) return null

        val target = plaintextFile(file, key)

        return try {
            val pfd = ParcelFileDescriptor.open(
                target,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val realFd = pfd.fd
            val fd = if (realFd >= 0) realFd else fallbackFd.incrementAndGet()
            val info = VocalizerFileInfo().apply {
                descriptor = fd
                startOffset = 0L
                length = target.length()
            }
            openFiles[info.descriptor] = pfd
            info
        } catch (e: Exception) {
            HostLog.w(
                HostProtocol.ENGINE_VOCALIZER,
                "VocalizerDataProvider: تعذّر فتح ملف $key: " +
                    e.message
            )
            null
        }
    }

    /**
     * يغلق واصف الملف كما يفعل الغلاف الحالي في
     * VocalizerEngine.
     */
    fun closeAssetFile(fd: Int) {
        val pfd = openFiles.remove(fd) ?: return
        try {
            pfd.close()
        } catch (e: IOException) {
            HostLog.w(
                HostProtocol.ENGINE_VOCALIZER,
                "VocalizerDataProvider: خطأ في إغلاق fd $fd: " +
                    e.message
            )
        }
    }

    /**
     * إغلاق كافة الأوصاف المفتوحة.
     */
    fun closeAll() {
        for ((_, pfd) in openFiles) {
            runCatching { pfd.close() }
        }
        openFiles.clear()
    }

    fun getOpenFileCount(): Int = openFiles.size

    /**
     * هل هذا الوصف مفتوح عبر هذا المزوّد؟
     * يستخدمه المحرك لتوجيه الإغلاق إلى المالك الصحيح.
     */
    fun hasOpenFile(fd: Int): Boolean = openFiles.containsKey(fd)

    /**
     * يعيد ملفاً خدومَ الرأس المفكوك: إن كان [source] ملف صوت `.dat`
     * غير مشترك ومشفّراً بترميز CodeFactory (رأسه CF_MAGIC) فُكّ إلى
     * نسخةٍ مؤقتة في [decryptCacheDir] وأُعيدت (بلا المساس بالأصل)؛
     * وإلا أُعيد [source] كما هو. بلا مجلد كاش لا فكّ.
     */
    private fun plaintextFile(source: File, key: String): File {
        val cacheDir = decryptCacheDir ?: return source
        if (!VocalizerCfDecryptor.isDecryptableDat(
                HostProtocol.ENGINE_VOCALIZER, key
            )
        ) {
            return source
        }
        if (!isCfEncrypted(source)) return source
        decryptedCache[key]?.let { cached ->
            if (isUsableCache(cached, source)) return cached
        }
        return try {
            cacheDir.mkdirs()
            val cached = File(cacheDir, cacheName(key))
            decryptTo(source, cached)
            decryptedCache[key] = cached
            HostLog.w(
                HostProtocol.ENGINE_VOCALIZER,
                "VocalizerDataProvider: فُكّ تشفير $key -> " +
                    "${cached.name} (${cached.length()} بايت)"
            )
            cached
        } catch (e: Exception) {
            HostLog.w(
                HostProtocol.ENGINE_VOCALIZER,
                "VocalizerDataProvider: تعذّر فكّ تشفير $key: " +
                    e.message
            )
            source
        }
    }

    /** هل رأس [file] رأس CF المشفّر؟ */
    private fun isCfEncrypted(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val head = ByteArray(VocalizerCfDecryptor.CF_MAGIC.size)
            var got = 0
            while (got < head.size) {
                val r = input.read(head, got, head.size - got)
                if (r < 0) break
                got += r
            }
            VocalizerCfDecryptor.isEncrypted(head, got)
        }
    } catch (e: IOException) {
        false
    }

    /** هل النسخة المفكوكة المؤقتة صالحة (أحدث من الأصل وغير فارغة)؟ */
    private fun isUsableCache(cached: File, source: File): Boolean =
        cached.isFile && cached.length() > 0L &&
            cached.lastModified() >= source.lastModified()

    /** يكتب [source] مفكوكاً إلى [cached] كتابةً ذرّية (tmp ثم إحلال). */
    private fun decryptTo(source: File, cached: File) {
        val tmp = File(cached.parentFile, cached.name + ".tmp")
        FileInputStream(source).use { input ->
            FileOutputStream(tmp).use { output ->
                VocalizerCfDecryptor.copyDecryptingIfNeeded(
                    input,
                    output
                ) { }
            }
        }
        if (cached.exists() && !cached.delete()) {
            throw IOException("removing stale cache ${cached.name}")
        }
        if (!tmp.renameTo(cached)) {
            throw IOException("installing cache ${cached.name}")
        }
    }

    /** اسم ملف النسخة المفكوكة في الكاش. */
    private fun cacheName(key: String): String =
        key.removeSuffix(".dat") + ".sv"

    companion object {
        private val defaultInstance = VocalizerDataProvider()

        fun init(datFiles: Map<String, File>) {
            defaultInstance.setDatFiles(datFiles)
        }

        fun openDataFile(name: String?): VocalizerFileInfo? =
            defaultInstance.openDataFile(name)

        fun hasOpenFile(fd: Int): Boolean =
            defaultInstance.hasOpenFile(fd)

        fun closeAssetFile(fd: Int) =
            defaultInstance.closeAssetFile(fd)

        fun closeAll() =
            defaultInstance.closeAll()

        /**
         * تسطيح اسم الأصل حسب قاعدة ع٣٧-3:
         * خذ الجزء بعد vocalizer/ (إن وُجد)،
         * وحوّل / و. و_ إلى _ وغيرها من غير الحروف
         * والأرقام إلى -، وأضف .xmf
         */
        @JvmStatic
        fun flattenAssetName(name: String?): String {
            if (name.isNullOrBlank()) return ".xmf"
            val after = if (name.contains("vocalizer/")) {
                name.substringAfter("vocalizer/")
            } else {
                name
            }.removePrefix("/")

            val cleanAfter = after.removeSuffix(".xmf").removeSuffix(".dat")

            val sb = StringBuilder()
            for (c in cleanAfter) {
                when {
                    c == '/' || c == '.' || c == '_' -> sb.append('_')
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> {
                        sb.append(c)
                    }
                    else -> sb.append('-')
                }
            }
            return "${sb}.xmf"
        }
    }
}
