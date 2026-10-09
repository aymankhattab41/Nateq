package com.aymankhattab.nateq.core.audio.enginehost

import android.content.Context
import android.util.Log
import com.nuance.android.vocalizer.VocalizerAudioOutputListener
import com.nuance.android.vocalizer.VocalizerAudioSettings
import com.nuance.android.vocalizer.VocalizerEngine
import com.nuance.android.vocalizer.VocalizerVoice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ع٢٩-5: جلسة Nuance Vocalizer داخل `:engine_host`.
 *
 * منطقُ التهيئة والنطق مطابقٌ لما كان في
 * `VocalizerEmbeddedVoiceProvider` (نفس ترتيب loadVoice ثم
 * setSpeechRate/Pitch/Volume ثم speak) — نُقل دون تغيير سلوك؛
 * الفرق الوحيد أنه لم يعد يُستدعى في العملية الأمامية أبداً.
 */
internal class VocalizerHostSession(
    private val context: Context
) : HostEngineSession {

    companion object {
        private const val ENGINE_ID = HostProtocol.ENGINE_VOCALIZER

        /**
         * المسار الناجح المحفوظ لاستخدامه لاحقاً (ع٣٠-4).
         */
        @Volatile
        var cachedDataPath: String? = null
            internal set

        /**
         * توليد الصيغ المحتملة للمسار بالترتيب (ع٣٠-4):
         * 1. dir كما هو
         * 2. dir + "/"
         * 3. canonicalPath لـ dir و canonicalPath + "/"
         * 4. المجلد الأب (parent) و parent + "/" و parent.canonicalPath
         */
        internal fun getCandidatePaths(dataDir: String): List<String> =
            buildList {
                val trimmed = dataDir.trim()
                if (trimmed.isEmpty()) return@buildList

                fun addPath(path: String?) {
                    if (path != null && path.isNotBlank() && !contains(path)) {
                        add(path)
                    }
                }

                addPath(trimmed)
                addPath(if (trimmed.endsWith("/")) trimmed else "$trimmed/")

                val file = java.io.File(trimmed)
                val canonical = try {
                    file.canonicalPath
                } catch (_: Throwable) {
                    null
                }
                addPath(canonical)
                if (canonical != null) {
                    addPath(
                        if (canonical.endsWith("/")) canonical
                        else "$canonical/"
                    )
                }

                val parent = try {
                    file.parentFile?.absolutePath
                } catch (_: Throwable) {
                    null
                }
                addPath(parent)
                if (parent != null) {
                    addPath(
                        if (parent.endsWith("/")) parent
                        else "$parent/"
                    )
                }
                val parentCanonical = try {
                    file.parentFile?.canonicalPath
                } catch (_: Throwable) {
                    null
                }
                addPath(parentCanonical)
            }

        /**
         * مطابقة اسم الصوت المطلوب باسم صوت المحرك (ع٢٩-10 بند 3):
         * تُرفع بادئة `vocalizer_` عن الطرفين. **لا تنازل إلى أول
         * قائمة إن لم يُطابق** — اسم غير معروف يبقى بلا مطابقة
         * فيُعلَن خطأً صريحاً لا صوتٌ آخر يخفي الفشل.
         */
        internal fun matchesVoiceName(
            engineName: String?,
            requestedId: String
        ): Boolean {
            if (engineName.isNullOrBlank()) return false
            val engine = engineName.removePrefix("vocalizer_")
            val requested = requestedId.removePrefix("vocalizer_")
            return engine.equals(requested, ignoreCase = true)
        }
    }

    private val engineLock = Any()

    /** قفل توليد واحد: محرّك واحد لا يتولّى نطقين معاً. */
    private val speakLock = Any()

    @Volatile
    private var engine: VocalizerEngine? = null

    @Volatile
    private var stopRequested = false

    override fun init(dataDir: String): HostResult {
        synchronized(engineLock) {
            if (engine != null) {
                HostLog.d(ENGINE_ID, "المحرك مهيّأ مسبقاً")
                return HostProtocol.success()
            }
            if (dataDir.isBlank()) {
                HostLog.w(ENGINE_ID, "مسار مجلد البيانات فارغ")
                return HostResult.failure(
                    "NO_DATA_DIR",
                    "لا مجلد بيانات لمحرك Vocalizer"
                )
            }
            val eng = VocalizerEngine(context)
            val createErr = eng.createError
            HostLog.logInitDetails(
                ENGINE_ID,
                dataDir,
                mapOf(
                    "mNativeEnginePtr" to
                        "0x${eng.nativeEnginePtr.toString(16)}",
                    "isNativeCreated" to eng.isNativeCreated(),
                    "createError" to createErr?.let {
                        "${it.javaClass.simpleName}: ${it.message}"
                    },
                    "library" to "libNuanceVocalizer.so"
                )
            )
            if (!eng.isNativeCreated()) {
                val err = eng.createError
                val detail = if (err != null) {
                    val msg = err.message
                    if (!msg.isNullOrBlank()) {
                        "${err.javaClass.simpleName}: $msg"
                    } else {
                        err.javaClass.simpleName
                    }
                } else {
                    "mNativeEnginePtr == 0"
                }
                HostLog.e(
                    ENGINE_ID,
                    "فشل إنشاء كائن Vocalizer الأصلي: $detail",
                    err
                )
                return HostResult.failure(
                    "NATIVE_CREATE_FAILED",
                    "تعذّر إنشاء محرك Vocalizer الأصلي ($detail)"
                )
            }

            val candidates = getCandidatePaths(dataDir)
            var successPath: String? = null
            var lastInitCode: String? = null
            var lastInitMsg: String? = null

            for ((index, candidate) in candidates.withIndex()) {
                val stepNum = "${index + 1}/${candidates.size}"
                HostLog.d(
                    ENGINE_ID,
                    "تجربة صيغة المسار [$stepNum]: $candidate"
                )
                val (ok, initCode, initMsg) = try {
                    val initialized = eng.initialize(candidate)
                    if (initialized) {
                        Triple(true, null, null)
                    } else {
                        val initErr = eng.initError
                        if (initErr != null) {
                            val cls = initErr.javaClass.simpleName
                            val msg = eng.lastError
                                ?: "$cls: ${initErr.message}"
                            Triple(false, "INIT_EXCEPTION:$cls", msg)
                        } else {
                            val msg = eng.lastError
                                ?: "initializeNative أعاد false"
                            Triple(false, "INIT_NATIVE_FALSE", msg)
                        }
                    }
                } catch (t: Throwable) {
                    HostLog.e(
                        ENGINE_ID,
                        "استثناء أثناء initialize للصيغة " +
                            "$candidate: ${t.message}",
                        t
                    )
                    val cls = t.javaClass.simpleName
                    val msg = "$cls: ${t.message ?: t.toString()}"
                    Triple(false, "INIT_EXCEPTION:$cls", msg)
                }

                if (ok) {
                    HostLog.i(
                        ENGINE_ID,
                        "نجحت تهيئة Vocalizer بالصيغة [$stepNum]: $candidate"
                    )
                    successPath = candidate
                    cachedDataPath = candidate
                    break
                } else {
                    HostLog.w(
                        ENGINE_ID,
                        "فشلت التهيئة بالصيغة [$stepNum]: " +
                            "$candidate ($initCode: $initMsg)"
                    )
                    lastInitCode = initCode
                    lastInitMsg = initMsg
                }
            }

            if (successPath == null) {
                runCatching { eng.release() }
                HostLog.e(
                    ENGINE_ID,
                    "تهيئة Vocalizer فشلت بكل الصيغ " +
                        "[$lastInitCode] على $dataDir: $lastInitMsg"
                )
                return HostResult.failure(
                    lastInitCode ?: "INIT_FAILED",
                    "تعذّرت تهيئة Vocalizer بكل الصيغ على $dataDir " +
                        "($lastInitMsg)"
                )
            }

            engine = eng
            HostLog.i(ENGINE_ID, "Vocalizer جاهز على $successPath")
            return HostProtocol.success()
        }
    }

    /** أصوات المحرك من المكتبة (يستلزم [init] ناجحاً أولاً). */
    fun listVoices(): List<HostVoice> {
        val eng = engine ?: return emptyList()
        val voices = try {
            eng.voiceList
        } catch (t: Throwable) {
            HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
            return emptyList()
        }
        return voices?.mapNotNull { v ->
            val name = v.voiceName ?: return@mapNotNull null
            HostVoice(
                id = "vocalizer_${name.lowercase()}",
                name = name,
                langTag = v.language ?: ""
            )
        } ?: emptyList()
    }

    override suspend fun speak(
        req: SpeakRequest,
        sink: PcmSink,
        cancelled: AtomicBoolean
    ): HostResult {
        val eng = engine ?: return HostResult.failure(
            "NOT_INITIALIZED",
            "محرك Vocalizer غير مهيّأ"
        )
        synchronized(speakLock) {
            stopRequested = false

            val voices: Array<VocalizerVoice> = try {
                eng.voiceList ?: emptyArray()
            } catch (t: Throwable) {
                HostLog.e(ENGINE_ID, "voiceList فشل: ${t.message}", t)
                return HostResult.failure(
                    "VOICE_LIST_FAILED",
                    "تعذّرت قراءة أصوات المحرك"
                )
            }

            // ع٢٩-10 (3): مطابقة صارمة — لا سقوط على voices.firstOrNull()
            // الذي كان يخفي الخطأ ويقرأ بصوت آخر.
            val matched = voices.firstOrNull {
                matchesVoiceName(it.voiceName, req.voiceId)
            } ?: return HostResult.failure(
                "VOICE_NOT_FOUND",
                "صوت Vocalizer غير موجود: ${req.voiceId}"
            )
            if (!eng.loadVoice(matched)) {
                return HostResult.failure(
                    "LOAD_VOICE_FAILED",
                    "تعذّر تحميل الصوت المطلوب: ${req.voiceId}"
                )
            }

            val rateInt = (req.rate * 100).toInt().coerceIn(20, 400)
            val pitchInt = (req.pitch * 100).toInt().coerceIn(50, 200)
            val volumeInt = (req.volume * 100).toInt().coerceIn(0, 100)
            eng.setSpeechRate(rateInt)
            eng.setSpeechPitch(pitchInt)
            eng.setSpeechVolume(volumeInt)

            sink.writeHeader(matched.sampleRate, 1)

            val pipeBroken = AtomicBoolean(false)
            eng.setAudioOutputListener(
                object : VocalizerAudioOutputListener {
                    override fun onAudioData(
                        settings: VocalizerAudioSettings?,
                        audioData: ShortArray?
                    ) {
                        if (audioData == null || audioData.isEmpty()) return
                        if (cancelled.get() || stopRequested) return
                        if (pipeBroken.get()) return
                        try {
                            sink.writePcm(
                                toLePcm(audioData),
                                audioData.size * 2
                            )
                        } catch (t: Throwable) {
                            // انغلق الأنبوب (مات العميل): أوقف التوليد
                            // بدل رمي استثناء داخل رد اتصال JNI.
                            pipeBroken.set(true)
                            stopRequested = true
                        }
                    }
                },
                true
            )

            try {
                // ع٢٩-10 (3): speak متزامن — أُثبت ذلك من ثنائية
                // libNuanceVocalizer.so: ردّاها إلى Java لا يتجاوزان
                // audioSamplesReceived/speechMarksReceived (لا استدعاء
                // «اكتمال النطق» ولا حقل isSpeaking في StatusInfo)،
                // فلا يعود speakNative إلا بعد تسليم كل الدفعات
                // للمستمع؛ ولهذا يليه eng.stop() في finally عبثاً
                // آمناً لا يقطع صوتاً. لو كان speak غير متزامن لانقطع
                // كل نطق عند stop منذ أول إصدار.
                eng.speak(req.text)
            } finally {
                runCatching { eng.stop() }
            }

            return when {
                pipeBroken.get() -> HostResult.failure(
                    "PIPE_CLOSED",
                    "انغلق أنبوب الصوت"
                )
                cancelled.get() -> HostResult.failure(
                    "CANCELLED",
                    "أُلغي طلب النطق"
                )
                stopRequested -> HostResult.failure(
                    "STOPPED",
                    "أُوقف التوليد"
                )
                else -> HostProtocol.success()
            }
        }
    }

    override fun stop() {
        // إشارة الإلغاء أولاً: تُبتلع الدفعات التالية في callbacks
        // فتنتهي عودة speak سريعاً، ثم stop() أصليٌّ يوقف التوليد.
        // بلا قفل speakLock عَمداً: قفلُه هنا كان ينتظر نطقاً
        // طويلاً كاملاً فيبطل الإلغاء.
        stopRequested = true
        val eng = engine ?: return
        runCatching { eng.stop() }
    }

    override fun shutdown() {
        synchronized(engineLock) {
            val eng = engine ?: return
            engine = null
            runCatching { eng.release() }
        }
    }

    private fun toLePcm(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        return buffer.array()
    }
}
