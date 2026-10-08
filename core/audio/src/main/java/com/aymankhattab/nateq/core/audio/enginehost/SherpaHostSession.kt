package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.engine.SynthesisBudget
import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import com.aymankhattab.nateq.core.audio.providers.SherpaModelCatalog
import com.aymankhattab.nateq.core.audio.providers.SherpaModelChecks
import com.aymankhattab.nateq.core.audio.providers.SherpaModelException
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.VersionInfo
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ع٢٩-5 + ع٢٩-6: جلسة Sherpa-ONNX داخل `:engine_host`.
 *
 * كلُّ المكتبة الأصلية (`OfflineTts` وأوامر التوليد) هنا وحدها؛
 * والتحقق المسبق من ع٢٩-6 يسبق أي بناء للمحرّك برمزاً معنوناً
 * فلا تصل المكتبة إلى نموذج ناقص أبداً. وميزانية التوليد من
 * [SynthesisBudget.pieceTimeoutMs] (الحصّة الداخلية) — المهلة
 * الخارجية في العميل أكبر منها دائماً.
 */
internal class SherpaHostSession(
    private val context: Context
) : HostEngineSession {

    companion object {
        private const val TAG = "SherpaHost"

        /** مسار نسخة espeak المضمّنة في الأصول. */
        private const val ESPEAK_ASSET_PATH = "espeak-ng-data"

        /** الحد الأدنى لخيوط التوليد. */
        private const val MIN_NUM_THREADS = 1

        /** الحد الأقصى لخيوط التوليد. */
        private const val MAX_NUM_THREADS = 2

        /**
         * مهلة رحمة بعد إشارة الإيقاف قبل الإهمال — ليست ميزانية
         * نطق: تُحسب بعد طلب التوقف فحسب.
         */
        internal const val STOP_GRACE_MS = 5_000L
    }

    private val engineLock = Any()

    private val ttsInstances = ConcurrentHashMap<String, OfflineTts>()

    /** مجلد بحث إضافي ممرَّر في init (مجلد مستورد/اختبار). */
    @Volatile
    private var extraSearchDir: String = ""

    /** نجحت التهيئة (يُختصر التحقق التالي — لا معادلة نطق جديدة). */
    @Volatile
    private var inited = false

    @Volatile
    private var espeakDir: File? = null

    @Volatile
    private var stopRequested = false

    override fun init(dataDir: String): HostResult {
        if (dataDir.isNotBlank()) extraSearchDir = dataDir
        synchronized(engineLock) {
            if (inited) return HostProtocol.success()
            val espeak = ensureEspeakDir()
            val missing = SherpaModelChecks.missingEspeakEntries(espeak)
            if (missing.isNotEmpty()) {
                Log.e(
                    TAG,
                    "espeak-ng-data ناقص: missing=$missing " +
                        "local=${espeak.listFiles()?.size ?: -1}"
                )
                return HostResult.failure(
                    "ESPEAK_DATA_INCOMPLETE",
                    "بيانات espeak-ng ناقصة: ${missing.joinToString()}"
                )
            }
            espeakDir = espeak
            inited = true
            Log.i(TAG, "espeak جاهز على ${espeak.absolutePath}")
            return HostProtocol.success()
        }
    }

    /** أصوات النماذج (مسح قرصي فقط — بلا مكتبة أصلية). */
    fun listVoices(): List<HostVoice> = discover().map {
        HostVoice(
            id = it.id,
            name = it.displayName,
            langTag = it.locale.toLanguageTag()
        )
    }

    /** كل النماذج المكتشفة من مجلدات البحث. */
    fun discover(): List<SherpaModelCatalog.ModelInfo> {
        // مسار صريح ممرَّر في init يحكم البحث وحده (يطابق سلوك
        // المزوّد: مجلد مخصّص يُقصّ عليه ولا يُمزج بالمخزون).
        val dirs = if (extraSearchDir.isNotBlank()) {
            listOf(File(extraSearchDir))
        } else {
            EmbeddedStorage.allSearchDirs(
                context,
                EmbeddedStorage.Engine.SHERPA
            )
        }
        return SherpaModelCatalog.discover(dirs)
    }

    override suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        stopRequested = false

        val models = discover()
        if (models.isEmpty()) {
            return HostResult.failure("NO_MODEL", "لا نماذج Sherpa")
        }
        val clean = req.voiceId.removePrefix("sherpa_")
        val matched = models.firstOrNull {
            it.id.equals(req.voiceId, ignoreCase = true)
        } ?: models.firstOrNull {
            it.displayName.equals(clean, ignoreCase = true)
        } ?: return HostResult.failure(
            "NO_SUCH_VOICE",
            "لا نموذج بالمعرّف ${req.voiceId}"
        )

        val tts = try {
            getOrCreateTts(matched)
        } catch (e: SherpaModelException) {
            // ع٢٩-6: رفض تحقق مسبق برمز معنون لا خطأ عام.
            Log.e(TAG, "Sherpa init refused [${e.code}]: ${e.message}")
            return HostResult.failure(e.code, e.message)
        } catch (t: Throwable) {
            Log.e(TAG, "فشل بناء Sherpa TTS: ${t.message}", t)
            return HostResult.failure(
                "INIT_FAILED",
                t.message ?: "فشل بناء المحرّك"
            )
        }

        sink.writeHeader(tts.sampleRate(), 1)

        val config = GenerationConfig(
            sid = 0,
            speed = req.rate.coerceIn(0.2f, 3.0f)
        )
        val volumeFactor = req.volume.coerceIn(0.0f, 2.0f)
        val budgetMs = SynthesisBudget.pieceTimeoutMs(req.text.length)

        return try {
            coroutineScope {
                val abort = AtomicBoolean(false)
                val generation = async(Dispatchers.IO) {
                    tts.generateWithConfigAndCallback(
                        text = req.text,
                        config = config
                    ) { floatSamples ->
                        if (abort.get() || stopRequested) {
                            return@generateWithConfigAndCallback 0
                        }
                        if (floatSamples.isNotEmpty()) {
                            val bytes = scaleToLePcm(
                                floatSamples,
                                volumeFactor
                            )
                            try {
                                sink.writePcm(bytes, bytes.size)
                            } catch (t: Throwable) {
                                // انغلق الأنبوب (مات العميل).
                                abort.set(true)
                                return@generateWithConfigAndCallback 0
                            }
                        }
                        1
                    }
                }
                val finished = withTimeoutOrNull(budgetMs) {
                    generation.join()
                    true
                } ?: false
                if (!finished) {
                    abort.set(true)
                    Log.e(
                        TAG,
                        "Sherpa تجاوز ميزانيته ${budgetMs}ms: " +
                            matched.displayName
                    )
                    withTimeoutOrNull(STOP_GRACE_MS) {
                        generation.join()
                    }
                    generation.cancel()
                    return@coroutineScope HostResult.failure(
                        "GENERATION_TIMEOUT",
                        "تجاوز توليد Sherpa ميزانيته ${budgetMs}ms"
                    )
                }
                HostProtocol.success()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "خطأ في توليد Sherpa: ${t.message}", t)
            HostResult.failure(
                "ENGINE_ERROR",
                t.message ?: "خطأ غير متوقّع"
            )
        }
    }

    /**
     * ع٢٩-6: بناء (أو إعادة استخدام) محرّك نموذج واحد — التحقق
     * المسبق يسبق `OfflineTts`، وعلامة الانهيار تُكتب قبل البناء
     * الأصلي وتُمحى بعد رجوعه.
     */
    private suspend fun getOrCreateTts(
        model: SherpaModelCatalog.ModelInfo
    ): OfflineTts {
        ttsInstances[model.id]?.let { return it }

        val modelEspeak = model.dataDir?.takeIf {
            SherpaModelChecks.missingEspeakEntries(it).isEmpty()
        }
        val espeakData = modelEspeak ?: espeakDir ?: ensureEspeakDir()

        SherpaModelChecks.validate(
            modelFile = model.modelFile,
            tokensFile = model.tokensFile,
            espeakDir = espeakData
        )

        val threads = Runtime.getRuntime().availableProcessors()
            .coerceIn(MIN_NUM_THREADS, MAX_NUM_THREADS)

        val config = getOfflineTtsConfig(
            modelDir = model.modelDir.absolutePath,
            modelName = model.modelFile.name,
            acousticModelName = "",
            vocoder = "",
            voices = "",
            lexicon = "",
            dataDir = espeakData.absolutePath,
            dictDir = "",
            ruleFsts = "",
            ruleFars = ""
        )
        val vits = config.model.vits.copy(
            model = model.modelFile.absolutePath,
            tokens = model.tokensFile.absolutePath,
            dataDir = espeakData.absolutePath
        )
        val finalConfig = config.copy(
            model = config.model.copy(
                vits = vits,
                numThreads = threads,
                debug = false
            )
        )

        HostProtocol.writeMarker(
            context.filesDir,
            HostProtocol.ENGINE_SHERPA,
            "OfflineTts ${model.modelFile.absolutePath}"
        )
        val tts = try {
            OfflineTts(assetManager = null, config = finalConfig)
        } catch (t: Throwable) {
            // ع٢٩-9: فشل التهيئة يُبطل الكاش ليظهر السبب في القائمة.
            EnginePicker.invalidateAfterStorageChange()
            throw t
        } finally {
            HostProtocol.clearMarker(
                context.filesDir,
                HostProtocol.ENGINE_SHERPA
            )
        }
        ttsInstances[model.id] = tts
        Log.i(
            TAG,
            "Sherpa-ONNX ${sherpaVersionTag()} initialized " +
                "model=${model.modelFile.absolutePath} " +
                "size=${model.modelFile.length()}B " +
                "tokens=${model.tokensFile.length()}B " +
                "espeak=${espeakData.absolutePath} " +
                "threads=$threads"
        )
        // ع٢٩-9: نجاح التهيئة يُبطل كاش المحركات والاكتشاف.
        EnginePicker.invalidateAfterStorageChange()
        return tts
    }

    /** إصدار sherpa-onnx من مكتبة JNI إن توفّر، وإلا `unknown`. */
    private fun sherpaVersionTag(): String = runCatching {
        VersionInfo.version
    }.getOrDefault("unknown")

    override fun stop() {
        stopRequested = true
    }

    override fun shutdown() {
        synchronized(engineLock) {
            for ((id, instance) in ttsInstances) {
                try {
                    instance.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "خطأ في تحرير $id: ${t.message}")
                }
            }
            ttsInstances.clear()
        }
    }

    private fun scaleToLePcm(
        samples: FloatArray,
        volumeFactor: Float
    ): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val scaled = (samples[i] * volumeFactor * 32767f)
                .coerceIn(-32768f, 32767f)
                .toInt()
            bytes[2 * i] = (scaled and 0xFF).toByte()
            bytes[2 * i + 1] = ((scaled shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // ----------------------------------------------------------------
    // نسخ espeak من الأصول (منطق ع٢٩-6 كاملاً، في العملية الأمامية)
    // ----------------------------------------------------------------

    /**
     * نسخة espeak المضمّنة، تُعاد بناؤها إذا لم تكتمل (النسخة
     * المقطوعة التي يوجد فيها phondata فقط لا تصلح).
     */
    private fun ensureEspeakDir(): File {
        val target = File(context.filesDir, ESPEAK_ASSET_PATH)
        val assetCount = runCatching {
            context.assets.list(ESPEAK_ASSET_PATH)?.size
        }.getOrNull() ?: 0
        if (!espeakDirUsable(target, assetCount)) {
            target.deleteRecursively()
            target.mkdirs()
            copyAssetFolder(context, ESPEAK_ASSET_PATH, target)
            if (!espeakDirUsable(target, assetCount)) {
                val missing = SherpaModelChecks
                    .missingEspeakEntries(target)
                Log.e(
                    TAG,
                    "espeak-ng-data incomplete after copy: " +
                        "missing=$missing " +
                        "local=${target.listFiles()?.size ?: -1} " +
                        "assets=$assetCount"
                )
            }
        }
        return target
    }

    /**
     * المجلد صالح إذا اكتملت المدخلات الأساسية ولم يبقَ عددها أقل
     * من عدد ملفات الأصول المصدر (دليل قطعٍ على اكتمال النسخ لا
     * مجرد وجود phondata).
     */
    private fun espeakDirUsable(target: File, assetCount: Int): Boolean {
        if (SherpaModelChecks.missingEspeakEntries(target).isNotEmpty()) {
            return false
        }
        if (assetCount > 0) {
            val local = target.listFiles()?.size ?: 0
            if (local < assetCount) return false
        }
        return true
    }

    private fun copyAssetFolder(
        ctx: Context,
        assetPath: String,
        destDir: File
    ) {
        try {
            val list = ctx.assets.list(assetPath)
            if (list.isNullOrEmpty()) {
                destDir.parentFile?.mkdirs()
                ctx.assets.open(assetPath).use { input ->
                    FileOutputStream(destDir).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                destDir.mkdirs()
                for (file in list) {
                    val subAsset = if (assetPath.isEmpty()) {
                        file
                    } else {
                        "$assetPath/$file"
                    }
                    val subDest = File(destDir, file)
                    var isFile = false
                    try {
                        ctx.assets.open(subAsset).use { input ->
                            isFile = true
                            subDest.parentFile?.mkdirs()
                            FileOutputStream(subDest).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (_: Throwable) {
                        isFile = false
                    }
                    if (!isFile) {
                        copyAssetFolder(ctx, subAsset, subDest)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed copying asset $assetPath: ${e.message}")
        }
    }
}
