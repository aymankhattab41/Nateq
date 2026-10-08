package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.util.Log
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * مزود صوت محلي مدمج لمحرك Sherpa-ONNX / Piper / Coqui —
 * **لا تُحمَّل المكتبة الأصلية في هذه العملية أبداً** (ع٢٩-5):
 * العرض يقرأ القرص هنا (بلا JNI)، والنطق يمرّ عبر
 * [EngineHostClient] إلى `:engine_host` حيث يعيش `OfflineTts`
 * وحده؛ فإن انهار قُتلت تلك العملية فقط والتطبيق حيّ.
 */
class SherpaOnnxVoiceProvider(
    private val context: Context,
    private val customModelDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "SherpaOnnxEmbedded"
        const val PROVIDER_ID = "sherpa_onnx_embedded"
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Sherpa-ONNX / Piper (مدمج)"

    @Volatile
    private var isConfiguredCached: Boolean? = null

    /** رقم إصدار التخزين الذي بُني عنده كاش [isConfigured]. */
    @Volatile
    private var configuredAtVersion: Int = -1

    /** كل المجلدات التي تُمسح فيها النماذج (المصدر الموحّد ع٢٩-3). */
    private fun getSearchDirectories(): List<File> {
        if (customModelDir != null) {
            return listOf(customModelDir)
        }
        return EmbeddedStorage.allSearchDirs(
            context,
            EmbeddedStorage.Engine.SHERPA
        )
    }

    /**
     * مسح القرص واستخراج كل النماذج الصالحة — قرصٌ فقط بلا مكتبة
     * أصلية، بنفس منهج المضيف فلا تختلف النتيجة بين الطرفين.
     */
    fun discoverModels(): List<SherpaModelCatalog.ModelInfo> {
        return SherpaModelCatalog.discover(getSearchDirectories())
    }

    override fun isConfigured(): Boolean {
        // ع٢٩-9: الكاش مرتبط برقم إصدار التخزين، فتغيّر الملفات
        // (تنزيل/حذف/هجرة) يعيد الفحص فوراً بدل التجميد الدائم.
        val version = EmbeddedStorage.version
        isConfiguredCached?.let {
            if (configuredAtVersion == version) return it
        }
        val configured = discoverModels().isNotEmpty()
        configuredAtVersion = version
        isConfiguredCached = configured
        return configured
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> {
        return withContext(Dispatchers.IO) {
            val models = discoverModels()
            val result = mutableListOf<VoiceDescriptor>()

            for (m in models) {
                val matchesLang = locale.language.isEmpty() ||
                    m.locale.language.isEmpty() ||
                    m.locale.language.startsWith(
                        locale.language,
                        ignoreCase = true
                    )
                if (matchesLang) {
                    result.add(
                        VoiceDescriptor(
                            id = m.id,
                            providerId = PROVIDER_ID,
                            displayName = m.displayName,
                            locale = m.locale
                        )
                    )
                }
            }
            result
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: VoiceDescriptor,
        speechRate: Float,
        pitch: Float,
        volume: Float,
        onFormatInfo: (sampleRateInHz: Int, channelCount: Int) -> Unit,
        onAudioChunk: (ByteArray, Int) -> Unit,
        enginePackage: String?,
        voiceLocale: Locale?,
        desiredVoiceName: String?
    ) {
        withContext(Dispatchers.IO) {
            val models = discoverModels()
            if (models.isEmpty()) {
                Log.w(TAG, "No Sherpa-ONNX models found")
                return@withContext
            }

            val targetVoiceName = desiredVoiceName ?: voice.displayName
            val cleanTarget = targetVoiceName.removePrefix("sherpa_")

            val matchedModel = models.firstOrNull {
                it.id.equals(voice.id, ignoreCase = true) ||
                    it.displayName.equals(
                        targetVoiceName,
                        ignoreCase = true
                    ) ||
                    it.displayName.equals(cleanTarget, ignoreCase = true) ||
                    it.modelFile.nameWithoutExtension.equals(
                        cleanTarget,
                        ignoreCase = true
                    ) ||
                    it.modelDir.name.equals(cleanTarget, ignoreCase = true)
            } ?: models.firstOrNull {
                voiceLocale != null &&
                    it.locale.language.equals(
                        voiceLocale.language,
                        ignoreCase = true
                    )
            } ?: models.first()

            // تهيئة المضيف (تحقق espeak داخله) — بنداء سريع
            // متكرر يعيد جاهزيته إن أُعيد تشغيل العملية الأمامية.
            val init = EngineHostClient.init(
                context,
                HostProtocol.ENGINE_SHERPA,
                customModelDir?.absolutePath.orEmpty()
            )
            if (!init.ok) {
                Log.e(
                    TAG,
                    "تهيئة المضيف فشلت [${init.code}]: ${init.message}"
                )
                return@withContext
            }

            val result = EngineHostClient.synthesize(
                context = context,
                engineId = HostProtocol.ENGINE_SHERPA,
                voiceId = matchedModel.id,
                text = text,
                rate = speechRate,
                pitch = pitch,
                volume = volume,
                onFormatInfo = onFormatInfo,
                onAudioChunk = onAudioChunk
            )
            if (!result.ok) {
                Log.e(
                    TAG,
                    "نطق المضيف فشل [${result.code}]: ${result.message}"
                )
            }
        }
    }

    override fun shutdown() {
        isConfiguredCached = null
        configuredAtVersion = -1
        EngineHostClient.release(
            context,
            HostProtocol.ENGINE_SHERPA
        )
    }
}
