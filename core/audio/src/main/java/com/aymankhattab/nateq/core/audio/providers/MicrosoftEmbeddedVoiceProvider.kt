package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import com.aymankhattab.nateq.core.audio.announcement.LoudnessStage
import com.aymankhattab.nateq.core.audio.engine.UnifiedSpeechTracker
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient
import com.aymankhattab.nateq.core.audio.enginehost.HostProtocol
import com.aymankhattab.nateq.core.audio.enginehost.HostWarmStatusRegistry
import com.aymankhattab.nateq.core.audio.enginehost.MicrosoftHostSession
import com.aymankhattab.nateq.core.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * مزود صوت محرك مايكروسوفت المدمج (Microsoft Neural Embedded Voice Provider).
 * يعمل عبر [EngineHostClient] معزولاً في عملية :engine_host.
 */
class MicrosoftEmbeddedVoiceProvider(
    private val context: Context,
    private val customVoiceDir: File? = null
) : VoiceProvider {

    companion object {
        private const val TAG = "MsEmbeddedProvider"
        const val PROVIDER_ID = "microsoft_embedded"
        private const val MIN_API = Build.VERSION_CODES.O

        /** مايكروسوفت تشترط API 26 (أندرويد 8.0) فأعلى للمحرك المدمج —
         *  دالة نقية قابلة للاختبار بلا أندرويد (الثابت مُضمَّن وقت
         *  التجميع). */
        internal fun supportsApi(sdkInt: Int): Boolean = sdkInt >= MIN_API

        /** سبب الرفض الواضح تحت API 26 — يُسجَّل في السجلّ عند الرفض. */
        internal fun apiRejectionReason(sdkInt: Int): String =
            "محرك مايكروسوفت المدمج يتطلب API 26 فأعلى " +
                "(المستوى الحالي $sdkInt) — يُرفض"

        fun resolveDataDir(context: Context): File? =
            MicrosoftEmbeddedVoiceProvider(context).resolveDataDir()
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Microsoft (مدمج)"

    fun resolveDataDir(): File? {
        val base = customVoiceDir ?: MicrosoftDownloader.getMicrosoftBaseDir(
            context
        )
        return if (MicrosoftVoiceManifest.hasCompleteVoice(base)) base else null
    }

    override fun isConfigured(): Boolean {
        // شرط مايكروسوفت: API 26 فأعلى. تحته نرفض بوضوح (سجلّ + سبب)
        // بدل إظهار محرك مهيّأ شكلياً لكنه لن يعمل فعلياً.
        val sdk = Build.VERSION.SDK_INT
        if (!supportsApi(sdk)) {
            Log.e(TAG, apiRejectionReason(sdk))
            return false
        }
        if (customVoiceDir != null) {
            return EmbeddedStorage.inventory(
                context,
                EmbeddedStorage.Engine.MICROSOFT,
                listOfNotNull(customVoiceDir)
            ).isCompleteVoicePresent
        }
        val snapshot = MicrosoftVoiceRepository.getSnapshot()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!snapshot.isLoaded) {
                MicrosoftVoiceRepository.triggerBackgroundRefresh(context)
            }
            return snapshot.isConfigured
        }
        return if (snapshot.isLoaded) {
            snapshot.isConfigured
        } else {
            EmbeddedStorage.inventory(
                context,
                EmbeddedStorage.Engine.MICROSOFT
            ).isCompleteVoicePresent
        }
    }

    override suspend fun listVoices(locale: Locale): List<VoiceDescriptor> =
        withContext(Dispatchers.IO) {
            val base = resolveDataDir() ?: return@withContext emptyList()
            val completeDirs = MicrosoftVoiceManifest.completeVoiceDirs(base)
            if (completeDirs.isEmpty()) return@withContext emptyList()

            val catalogItems = MicrosoftModelCatalog.getItems(context)
            val result = mutableListOf<VoiceDescriptor>()

            for (dir in completeDirs) {
                val matched = catalogItems.firstOrNull { item ->
                    item.topDirName.equals(dir.name, ignoreCase = true) ||
                        dir.name.contains(item.topDirName, ignoreCase = true) ||
                        item.id.equals(dir.name, ignoreCase = true)
                }

                if (matched != null) {
                    if (locale == Locale.ROOT ||
                        locale.language.isBlank() ||
                        matched.locale.language.equals(
                            locale.language,
                            ignoreCase = true
                        )
                    ) {
                        result.add(
                            VoiceDescriptor(
                                id = matched.voiceName,
                                providerId = PROVIDER_ID,
                                displayName = matched.displayNameAr,
                                locale = matched.locale
                            )
                        )
                    }
                } else {
                    result.add(
                        VoiceDescriptor(
                            id = dir.name,
                            providerId = PROVIDER_ID,
                            displayName = dir.name,
                            locale = Locale.getDefault()
                        )
                    )
                }
            }

            result.distinctBy { it.id }
        }

    private suspend fun ensureInitialized(): Boolean {
        val base = resolveDataDir() ?: return false
        val res = EngineHostClient.init(
            context = context,
            engineId = HostProtocol.ENGINE_MICROSOFT,
            dataDir = base.absolutePath
        )
        return res.ok
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
        desiredVoiceName: String?,
        category: String?
    ) {
        withContext(Dispatchers.IO) {
            if (!ensureInitialized()) {
                Log.e(TAG, "Cannot synthesize: Microsoft host not initialized")
                return@withContext
            }

            val targetQuery = (desiredVoiceName ?: voice.displayName)
                .removePrefix("microsoft_")
                .trim()

            val catalogItem = MicrosoftModelCatalog.findItemByNameOrModel(
                context,
                targetQuery
            )
            val targetVoiceName = catalogItem?.modelName
                ?: catalogItem?.voiceName
                ?: targetQuery

            val isEvent = LoudnessStage.isEventCategory(category)
            val chunkConsumer: (ByteArray, Int) -> Unit = if (isEvent) {
                val gainDb = runCatching {
                    SettingsRepository.create(context).getEventPcmGainDb()
                }.getOrDefault(LoudnessStage.DEFAULT_GAIN_DB)
                if (gainDb > 0f) {
                    { chunk, validLength ->
                        if (validLength > 0) {
                            LoudnessStage.processPcm16(
                                chunk, 0, validLength, gainDb
                            )
                        }
                        onAudioChunk(chunk, validLength)
                    }
                } else {
                    onAudioChunk
                }
            } else {
                onAudioChunk
            }

            val isWarm = HostWarmStatusRegistry.isVoiceWarm(
                context = context,
                engineId = HostProtocol.ENGINE_MICROSOFT,
                voiceId = targetVoiceName,
                expectedRevision =
                    MicrosoftVoiceRepository.getStorageRevision(context)
            ) || MicrosoftHostSession.isWarmReady(targetVoiceName)

            val callCategory = when {
                category == "warmup" ->
                    UnifiedSpeechTracker.Category.WARMUP
                category == "preview" || category == "diagnostic" ->
                    UnifiedSpeechTracker.Category.DIAGNOSTIC
                isWarm ->
                    UnifiedSpeechTracker.Category.WARM
                else -> UnifiedSpeechTracker.Category.COLD
            }
            val tracker = UnifiedSpeechTracker.startRequest(
                engine = UnifiedSpeechTracker.Engine.MICROSOFT,
                category = callCategory,
                voiceName = targetVoiceName,
                textLength = text.length
            )
            try {
                tracker.markSpeakCallStart()
                var firstPcmRecorded = false
                val trackedChunkConsumer: (ByteArray, Int) -> Unit =
                    { chunk, len ->
                        if (!firstPcmRecorded && len > 0) {
                            firstPcmRecorded = true
                            tracker.markFirstPcm()
                        }
                        chunkConsumer(chunk, len)
                    }

                val result = EngineHostClient.synthesize(
                    context = context,
                    engineId = HostProtocol.ENGINE_MICROSOFT,
                    voiceId = targetVoiceName,
                    text = text,
                    rate = speechRate,
                    pitch = pitch,
                    volume = volume,
                    onFormatInfo = onFormatInfo,
                    onAudioChunk = trackedChunkConsumer
                )

                tracker.markSpeakCallEnd()
                if (result.ok) {
                    tracker.complete(UnifiedSpeechTracker.Status.SUCCESS)
                } else {
                    tracker.complete(
                        UnifiedSpeechTracker.Status.FAILED,
                        result.message
                    )
                }

                if (!result.ok) {
                    Log.e(
                        TAG,
                        "نطق مايكروسوفت المدمج فشل " +
                            "[${result.code}]: ${result.message}"
                    )
                    EngineStatusRegistry.noteFailure(
                        PROVIDER_ID,
                        result.code,
                        result.message
                    )
                }
            } finally {
                if (!tracker.isCompletedRequest) {
                    tracker.complete(
                        UnifiedSpeechTracker.Status.CANCELLED,
                        "انتهى أو ألغي طلب Microsoft"
                    )
                }
            }
        }
    }

    override fun shutdown() {
        runCatching {
            EngineHostClient.release(
                context,
                HostProtocol.ENGINE_MICROSOFT
            )
        }
    }
}
