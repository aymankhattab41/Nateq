package com.aymankhattab.nateq.providers

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.aymankhattab.nateq.R
import com.aymankhattab.nateq.settings.SettingsRepository
import com.aymankhattab.nateq.util.LocaleUtils
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * مزود احتياطي يعمل بدون إنترنت، عن طريق تفويض النطق إلى محرك TTS
 * طرفي يثبّته المستخدم بنفسه (مثل eSpeak، MultiTTS، ...) بدل المحركات
 * المدمجة في الجهاز (جوجل/سامسونج).
 *
 * ## ماذا يختار هذا المزوّد؟
 * يكشف ديناميكياً كل محركات TTS المثبتة في النظام، ثم يختار:
 *   1) المحركات الطرفية التي نصبّها المستخدم (كل ما عدا ناطق نفسه،
 *      وجوجل، وسامسونج المدمجين) — هذه هي الأَولى بالتفضيل.
 *   2) إن لم يجد أي محرك طرفي، عاد إلى أي محرك متاح (جوجل) كملاذ أخير
 *      حتى لا يبقى التطبيق صامتاً.
 *
 * ## الاكتفاء الذاتي (مهم)
 * يربط مباشرةً بمحركٍ يعيّنه عند البناء عبر منشئ
 * `TextToSpeech(context, listener, engine)` — لا يتعلق إطلاقاً باختيار
 * "المحرك الافتراضي" في شاشة إعدادات TTS النظامية التي قد تُسقطها
 * المَشغّلون كسامسونج. ومستبعدٌ دائماً كونُه نفسه، فلا يحدث تكرار ذاتي.
 */
class SystemVoiceProvider(private val context: Context) : VoiceProvider {

    companion object {
        private const val TAG = "NATEQ_TTS"

        /** أقصى مدة انتظار لكتابة المحرك ملف الصوت قبل اعتبار التخليق فاشلاً. */
        private const val MAX_SYNTH_WAIT_MS = 30_000L

        /** دورية فحص الإلغاء أثناء انتظار اكتمال الكتابة. */
        private const val CANCELLATION_POLL_MS = 100L
    }

    override val providerId = "system"
    override val displayName: String
        get() = context.getString(R.string.voice_provider_system)

    private var tts: TextToSpeech? = null

    /** حزمة المحرك المرتبط حالياً للتحقق من إعادة الاستخدام عند ثباتها */
    private var ttsEngine: String? = null

    override fun isConfigured(): Boolean = true // متاح دائمًا

    /**
     * يختار المحرك الذي ينطق به التطبيق:
     * 1) يفضّل المحرك الذي اختاره المستخدم في شاشة الإعدادات (إن وُجد ومثبَّت).
     * 2) وإلا اختار محركاً طرفياً نصبّه المستخدم (لا جوجل ولا سامسونج)،
     *    ويتم عمل fallback إلى جوجل كملاذ أخير.
     */
    private fun pickEnginePackage(): String? {
        // المحرك المختار من المستخدم (مثل MultiTTS) له الأولوية
        val selected = try {
            SettingsRepository(context).getSelectedEnginePackage()
        } catch (e: Exception) {
            null
        }
        if (selected != null &&
            EnginePicker.installedEnginePackages(context).contains(selected)
        ) {
            return selected
        }
        return EnginePicker.pickEnginePackage(context)
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        // إرجاع واصف يحمل المعرّف والـ locale الصحيحين (لغة ISO-2) حتى يتطابق
        // مع الـ Voice المُعلن في onGetVoices ولينطق المحرك باللغة الصحيحة.
        // نطبّع كود اللغة من ISO-3 (eng, ara) إلى ISO-2 (en, ar).
        val normLanguage = normalizeLanguage(locale.language)
        // معرفات الأصوات يجب أن تطابق أسماء onGetVoices/tts_engine.xml
        // ("ar-EG"/"en-US") حتى تعمل مطابقة id في الفئات والإعلانات.
        val voiceId = when (normLanguage) {
            "ar" -> "ar-EG"
            "en" -> "en-US"
            else -> "nateq-$normLanguage-local"
        }
        val normLocale = if (normLanguage != locale.language) {
            if (locale.country.isNullOrEmpty()) {
                Locale.forLanguageTag(normLanguage)
            } else {
                Locale.forLanguageTag("$normLanguage-${locale.country}")
            }
        } else {
            locale
        }
        return listOf(
            VoiceDescriptor(
                id = voiceId,
                providerId = providerId,
                displayName = if (normLanguage == "ar") context.getString(R.string.voice_name_arabic) else context.getString(R.string.voice_name_english),
                locale = normLocale
            )
        )
    }

    /** تطبيع كود اللغة من ISO-3 إلى ISO-2 (مثل eng→en، ara→ar) */
    private fun normalizeLanguage(code: String): String = LocaleUtils.normalizeLanguageCode(code)

    override suspend fun synthesize(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onAudioChunk: (ByteArray) -> Unit,
        enginePackage: String?,
        voiceLocale: Locale?,
        desiredVoiceName: String?
    ) {
        // **تفويض النطق لمحركٍ مثبّت** (منهج MultiTTS): نصّل دائماً عبر محرك TTS
        // خارجي نربط به مباشرةً (جوجل/سامسونج/طرفي). تُفضَّل المحركات الطرفية
        // إن وُجدت وإلا جوجل، وتُستبعد دائماً حزمة التطبيق نفسه
        // (`EnginePicker.installedEnginePackages`) فلا يحدث تكرار ذاتي.
        // إلغاء قابل للتعاون: على عكس suspendCoroutine، يُبلَّغ suspendCancellableCoroutine
        // بالخارج عند إلغاء المهمة (onStop من المحرك)، فنضبط علماً ونتوقف فوراً بدل
        // انتظار القفل حتى 30 ثانية. الاستئناف بعد الإلغاء يُسقط تلقائياً وهذا متوقع.
        suspendCancellableCoroutine<Unit> { cont ->
            val cancelled = AtomicBoolean(false)
            cont.invokeOnCancellation {
                cancelled.set(true)
                runCatching { tts?.stop() }
            }
            val ttsEngine = resolveEngine(enginePackage)

            // إذا تُحدَّد لغة عبر التحويل التلقائي، نستخدم صوتاً بلغتها النهائية.
            val effectiveVoice = if (voiceLocale != null && voiceLocale.language.isNotEmpty()) {
                voice.copy(locale = voiceLocale)
            } else {
                voice
            }

            synthesizeWithEngine(
                ttsEngine,
                text,
                effectiveVoice,
                speechRate,
                pitch,
                volume,
                onAudioChunk,
                cont,
                cancelled,
                desiredVoiceName
            )
        }
    }

    /** يحدّد محرك TTS الذي سيُستخدَم، مع التحقق من أنه مثبَّت فعلاً. */
    private fun resolveEngine(enginePackage: String?): String? {
        val engine = enginePackage ?: pickEnginePackage()
        return if (engine != null && EnginePicker.installedEnginePackages(context).contains(engine)) {
            engine
        } else {
            pickEnginePackage()
        }
    }

    /**
     * يُنفّذ النطق عبر المحرك المعطى، وعند فشل المحرك الطرفي (مثل SmartVoice الذي
     * يفشل synthesizeToFile) يتراجع تلقائياً إلى محرك جوجل المدمج كملاذ أخير حتى
     * لا يبقى التطبيق صامتاً على أي جهاز.
     */
    private fun synthesizeWithEngine(
        engine: String?,
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onAudioChunk: (ByteArray) -> Unit,
        cont: kotlin.coroutines.Continuation<Unit>,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?
    ) {
        val done = AtomicBoolean(false)
        val attemptWith = { currentEngine: String? ->
            if (currentEngine == null) {
                Log.e(TAG, "[Provider] no engine available to bind")
                if (!done.getAndSet(true)) cont.resume(Unit)
            } else if (!cancelled.get()) {
                if (tts == null || ttsEngine != currentEngine) {
                    // نغلق أي محرك سابق قبل ربط محرك جديد (خاصة بعد فشل محرك).
                    if (tts != null) {
                        runCatching { tts?.shutdown() }
                        tts = null
                    }
                    Log.w(TAG, "[Provider] init engine=$currentEngine")
                    tts = TextToSpeech(context, { status ->
                        if (done.getAndSet(true)) return@TextToSpeech
                        if (status == TextToSpeech.SUCCESS && !cancelled.get()) {
                            val ok = synthesizeInternal(text, voice, speechRate, pitch, volume, onAudioChunk, cancelled, desiredVoiceName)
                            if (ok) {
                                cont.resume(Unit)
                            } else {
                                // فشل النطق — جرّب محرك جوجل إن أمكن.
                                retryWithGoogle(engine, voice, text, speechRate, pitch, volume, onAudioChunk, cont, cancelled, desiredVoiceName)
                            }
                        } else {
                            Log.e(TAG, "[Provider] engine init failed: $currentEngine status=$status")
                            retryWithGoogle(engine, voice, text, speechRate, pitch, volume, onAudioChunk, cont, cancelled, desiredVoiceName)
                        }
                    }, currentEngine)
                    ttsEngine = currentEngine
                } else if (!done.getAndSet(true)) {
                    // مثيل نفس المحرك جاهز — ننطق مباشرة بإعادة استخدامه.
                    val ok = synthesizeInternal(text, voice, speechRate, pitch, volume, onAudioChunk, cancelled, desiredVoiceName)
                    if (ok) {
                        cont.resume(Unit)
                    } else {
                        retryWithGoogle(engine, voice, text, speechRate, pitch, volume, onAudioChunk, cont, cancelled, desiredVoiceName)
                    }
                }
            }
        }

        if (engine != null && EnginePicker.installedEnginePackages(context).contains(engine)) {
            attemptWith(engine)
        } else {
            attemptWith(EnginePicker.pickEnginePackage(context))
        }
    }

    /** عند فشل المحرك الأصلي، يتراجع إلى محرك جوجل المدمج (إن وُجد). */
    private fun retryWithGoogle(
        originalEngine: String?,
        voice: VoiceDescriptor,
        text: String,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onAudioChunk: (ByteArray) -> Unit,
        cont: kotlin.coroutines.Continuation<Unit>,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?
    ) {
        if (cancelled.get()) return
        val google = EnginePicker.googleEnginePackage(context)
        // لا نتراجع إلى جوجل إذا كان هو بالفعل المحرك الأصلي المستخدَم.
        if (google != null && google != originalEngine && EnginePicker.installedEnginePackages(context).contains(google)) {
            Log.w(TAG, "[Provider] falling back to Google engine: $google")
            synthesizeWithEngine(google, text, voice, speechRate, pitch, volume, onAudioChunk, cont, cancelled, desiredVoiceName)
        } else {
            cont.resume(Unit)
        }
    }

    /**
     * يُنفّذ النطق عبر المحرك المربوط ويُعيد true عند النجاح (صَرْف بيانات صوتية)،
     * أو false عند الفشل (حتى يتراجع المتصل إلى محرك بديل).
     */
    private fun synthesizeInternal(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onAudioChunk: (ByteArray) -> Unit,
        cancelled: AtomicBoolean,
        desiredVoiceName: String?
    ): Boolean {
        val engine = tts
        if (engine == null) return false
        // نهج موحد الرقمية (يتسق عبر كل المحركات والأجهزة):
        // - النبرة والسرعة ومستوى الصوت تُعالج كلها رقمياً لاحقاً في
        //   [applyAudioEffects] لضمان الأثر حتى مع المحركات التي تتجاهل
        //   setPitch/setSpeechRate (مثل جوجل). لذلك يُضبط المحرك على قيم
        //   محايدة (1.0) لئلا يتضاعف التأثير (المحرك + نحن).
        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)
        engine.setLanguage(voice.locale)
        // إن اختار المستخدم صوتاً محدداً من حوار التحويل (اسم صوت في محرك
        // خارجي مثل MultiTTS) نطبّقه هنا عبر `voice`، مع التراجع الصامت إلى
        // اللغة إذا لم يجده المحرك (تجنّباً لكسر النطق لمجرد اسم غير مطابق).
        if (!desiredVoiceName.isNullOrBlank()) {
            runCatching {
                val matching = engine.voices?.firstOrNull { it.name == desiredVoiceName }
                if (matching != null) engine.voice = matching
            }
        }

        val utteranceId = "nateq_${System.currentTimeMillis()}"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val tempFile = java.io.File(context.cacheDir, "nateq_tts_${System.currentTimeMillis()}.wav")

        // synthesizeToFile يُرجع SUCCESS فوراً قبل اكتمال الكتابة، لذلك ننتظر
        // اكتمال الكتابة عبر UtteranceProgressListener قبل قراءة الملف — وإلا
        // نقرأ ملفاً فارغاً/غير مكتمل ولا يُسمع أي صوت نهائياً.
        val done = CountDownLatch(1)
        var failed = false
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                @Deprecated("Java Deprecated")
                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }

                @Deprecated("Java Deprecated")
                override fun onError(utteranceId: String?) {
                    failed = true
                    done.countDown()
                }
            })
        } catch (e: RuntimeException) {
            Log.w(TAG, "[Provider] setOnUtteranceProgressListener threw", e)
        }

        val status = engine.synthesizeToFile(text, params, tempFile, utteranceId)

        var success = false
        if (status == TextToSpeech.SUCCESS) {
            // ننتظر فعلاً حتى يكتب المحرك الملف كاملاً (أو يُلغى الإعلان/النطق)،
            // بفحص الإلغاء كل 100ms بدل القفل الأعمى 30 ثانية — فإذا أوقف
            // المستخدم النطق (onStop) نتحرر فوراً ولا نعلق 30 ثانية.
            val deadline = SystemClock.elapsedRealtime() + MAX_SYNTH_WAIT_MS
            var finished = false
            try {
                while (true) {
                    if (done.await(CANCELLATION_POLL_MS, TimeUnit.MILLISECONDS)) {
                        finished = true
                        break
                    }
                    if (cancelled.get() ||
                        Thread.currentThread().isInterrupted ||
                        SystemClock.elapsedRealtime() >= deadline
                    ) {
                        break
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            if (finished && !failed && tempFile.exists() && tempFile.length() > 44) {
                try {
                    // قراءة بيانات الصوت مباشرة من ملف التخليق (تخطّي رأس WAV
                    // وقائمة الخانات) دون قراءة الملف كاملاً ثم نسخه — كان ذلك
                    // يرفع ذروة الذاكرة 2-3× حجم الملف للنصوص الطويلة.
                    val pcmData = extractPcm(tempFile)
                    if (pcmData.isEmpty()) {
                        Log.e(TAG, "[Provider] extractPcm returned empty")
                    } else {
                        // المعالجة الرقمية الموحّدة للنبرة والسرعة ومستوى الصوت.
                        val scaledData = applyAudioEffects(pcmData, speechRate, pitch, volume)
                        onAudioChunk(scaledData)
                        success = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[Provider] read audio failed", e)
                } finally {
                    tempFile.delete()
                }
            } else {
                Log.e(
                    TAG,
                    "[Provider] synthesis not completed: failed=$failed finished=$finished size=${if (tempFile.exists()) tempFile.length() else -1}"
                )
                tempFile.delete()
            }
        } else {
            Log.e(TAG, "[Provider] synthesizeToFile status=$status")
        }
        return success
    }

    /**
     * يستخرج بيانات PCM الخام من ملف WAV بتخطّي الرأس وقائمة الخانات بصيغة
     * آمنة، حتى مع رؤوس أطول من 44 بايتاً (ببعض المحركات مثل MultiTTS).
     * يقرأ من القرص مباشرة (RandomAccessFile) فيقرأ خانة data وحدها دون
     * نسخ الملف كاملاً إلى الذاكرة.
     */
    private fun extractPcm(file: java.io.File): ByteArray {
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                if (fileLen < 12) return ByteArray(0)

                val sig = ByteArray(12)
                raf.readFully(sig)
                if (sig[0] != 'R'.code.toByte() || sig[1] != 'I'.code.toByte() ||
                    sig[2] != 'F'.code.toByte() || sig[3] != 'F'.code.toByte()
                ) {
                    // ليس ملف WAV صالح — نقرأه كاملاً تحسباً.
                    raf.seek(0)
                    val all = ByteArray(fileLen.toInt())
                    raf.readFully(all)
                    return all
                }

                var offset = 12L // بعد "RIFF"+الحجم+"WAVE"
                while (offset + 8 <= fileLen) {
                    raf.seek(offset)
                    val header = ByteArray(8)
                    raf.readFully(header)
                    val chunkId = String(header, 0, 4, Charsets.US_ASCII)
                    val chunkSize = readLeInt(header, 4)
                    if (chunkId == "data") {
                        return readDataSection(raf, offset + 8, chunkSize.toLong(), fileLen)
                    }
                    offset += 8 + chunkSize
                }
                // لم نعثر على خانة data — نعود لافتراض 44 بايت احتياطاً.
                return if (fileLen > 44) readDataSection(raf, 44L, fileLen - 44, fileLen) else ByteArray(0)
            }
        } catch (e: Exception) {
            // أي خطأ قراءة — نُرجع فارغاً فيتخلى المتصل عن الملف.
            return ByteArray(0)
        }
    }

    /** قراءة خانة بيانات صوتية بطول معلوم بدءاً من الموضع المحدد. */
    private fun readDataSection(
        raf: java.io.RandomAccessFile,
        start: Long,
        len: Long,
        fileLen: Long
    ): ByteArray {
        val dataLen = minOf(len, fileLen - start).coerceAtLeast(0L).toInt()
        if (dataLen <= 0) return ByteArray(0)
        raf.seek(start)
        val out = ByteArray(dataLen)
        raf.readFully(out)
        return out
    }

    /** قراءة عدد صحيح صغير التدرج (little-endian) بطول 4 بايت */
    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** تطبيق مستوى الصوت على بيانات PCM */
    private fun applyVolume(pcmData: ByteArray, volume: Float): ByteArray {
        val result = ByteArray(pcmData.size)
        for (i in 0 until pcmData.size step 2) {
            // Read 16-bit sample (little endian)
            val sample = (pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)
            // Apply volume
            val scaled = (sample * volume).toInt().coerceIn(-32768, 32767)
            // Write back as little endian
            result[i] = (scaled and 0xFF).toByte()
            result[i + 1] = (scaled ushr 8).toByte()
        }
        return result
    }

    /**
     * إعادة أخذ عينات خطية (linear interpolation) لبيانات PCM أحادية 16-bit.
     * أفضل جودة من الاستيفاء بالجار الأقرب (nearest-neighbor) الذي كان يُسبب
     * تشوّهاً وتقطيعاً عند تغيير السرعة/النبرة.
     * @param factor <1 يسرّع (نحذف عينات)، >1 يبطّئ (نكرر عينات).
     * @return مصفوفة جديدة بالطول الجديد.
     */
    private fun resample(data: ByteArray, factor: Float): ByteArray {
        if (factor == 1.0f || data.size < 4) return data
        val totalSamples = data.size / 2
        val newSamples = kotlin.math.max((totalSamples / factor).toInt(), 1)
        val out = ByteArray(newSamples * 2)
        val samples = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            samples[i] = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xFF)).toShort()
        }
        for (j in 0 until newSamples) {
            val srcPos = j * factor
            val i0 = srcPos.toInt().coerceIn(0, totalSamples - 1)
            val i1 = (i0 + 1).coerceIn(0, totalSamples - 1)
            val frac = (srcPos - i0).toFloat()
            val interpolated = (samples[i0].toFloat() * (1f - frac) + samples[i1].toFloat() * frac)
                .toInt()
                .coerceIn(-32768, 32767)
            out[j * 2] = (interpolated and 0xFF).toByte()
            out[j * 2 + 1] = (interpolated ushr 8).toByte()
        }
        return out
    }

    /**
     * تغيير نبرة الكلام مع الحفاظ على مدّته الزمنية (pitch shift بسيط):
     * نعيد أخذ العينات بنسبة 1/pitch (ترتفع/تنخفض النغمة)، ثم نعكسها طولياً
     * لاستعادة المدة الزمنية الأصلية دون تغيير سرعة النطق.
     */
    private fun applyPitch(pcmData: ByteArray, pitch: Float): ByteArray {
        if (pitch == 1.0f || pcmData.size < 4) return pcmData
        val p = pitch.coerceIn(0.5f, 2.0f)
        val first = resample(pcmData, 1.0f / p) // غيّر النغمة (غيّر المدة مؤقتاً)
        // أعد أخذ العينات للطول الأصلي لاستعادة المدة: نسبة = الطول/الأصلي/الأول.
        val ratio = pcmData.size.toFloat() / first.size
        return if (ratio == 1.0f) first else resample(first, ratio)
    }

    /**
     * السلسلة الكاملة لتأثيرات التحكم الصوتي. عند القيم الافتراضية
     * (rate=1, pitch=1, volume=1) تُعاد [pcmData] كما هي دون تغيير.
     *
     * النبرة تُعالَج رقمياً (عبر [applyPitch]) هنا لتُضمَن على كل محرك بغضّ
     * النظر عن احترام المحرك لـ setPitch (جوجل يتجاهلها أحياناً). لذلك يُضبط
     * المحرك على نبرة محايدة (setPitch=1.0) في [synthesizeInternal] لئلا
     * يتضاعف التأثير (المحرك + نحن).
     */
    private fun applyAudioEffects(pcmData: ByteArray, speechRate: Float, pitch: Float, volume: Float): ByteArray {
        var result = pcmData
        if (pitch != 1.0f) result = applyPitch(result, pitch)
        if (speechRate != 1.0f) result = resample(result, 1.0f / speechRate)
        if (volume != 1.0f) result = applyVolume(result, volume)
        return result
    }
}
