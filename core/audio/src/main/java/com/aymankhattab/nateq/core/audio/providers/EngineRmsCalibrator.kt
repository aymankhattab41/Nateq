package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import com.aymankhattab.nateq.core.data.VoicePrefsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * أداة معايرة جهارة الصوت (RMS) لجميع المحركات المثبتة والمدمجة.
 *
 * تقوم بقياس أو تعيين قيم كسب التطبيع المستقرة (RMS Gain) لكل محرك،
 * وحفظها في [VoicePrefsProvider]، لتوحيد مستوى الصوت ومنع قفزات الجهارة.
 */
object EngineRmsCalibrator {

    /** قيم كسب RMS قياسية ومختبرة للمحركات الشائعة بنطاق -16 dBFS. */
    val BASELINE_GAINS: Map<String, Float> = mapOf(
        "com.google.android.tts" to 1.35f,
        "com.acapelagroup.android.tts" to 0.95f,
        "com.samsung.SMT" to 1.10f,
        "com.googlecode.eyesfree.espeak" to 0.85f,
        "com.techassistantforblind.vajratts" to 1.15f,
        "com.videvelopers.app.tech_freedom" to 1.05f,
        "com.vnspeak.autotts" to 1.00f,
        "dev.msvoice.tts" to 1.10f,
        "com.eduardopetrini.gryphon" to 1.00f,
        "com.nirenr.talkman" to 1.00f,
        "br.com.alphamob.aie" to 1.00f,
        "com.pantheraspeech.tts" to 1.00f,
        "in.visionHacks.eloquencePlus" to 1.00f,
        "org.volodymyr.multivoice" to 1.00f,
        "com.sarperarikan.accessimindreader" to 1.00f,
        "com.jumpingfridge.pravka" to 1.00f,
        "com.intu.multilingualtts" to 1.00f,
        VocalizerEmbeddedVoiceProvider.PROVIDER_ID to 1.05f
    )

    const val DEFAULT_FALLBACK_GAIN = 1.00f

    /**
     * تشغيل معايرة جهارة الصوت لجميع المحركات المثبتة في النظام.
     * @param forceRecompute إذا كانت true، تعيد معايرة المحركات
     * المعايرة مسبقاً.
     * @return عدد المحركات التي تمت معايرتها وتثبيتها.
     */
    suspend fun calibrateAll(
        context: Context,
        settings: VoicePrefsProvider,
        forceRecompute: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val installed = EnginePicker.installedEngines(context)
        val allEnginePackages = installed.map { it.packageName }.toMutableSet()
        allEnginePackages.add(VocalizerEmbeddedVoiceProvider.PROVIDER_ID)

        for (pkg in allEnginePackages) {
            val current = settings.getEngineRmsCalibration(pkg)
            if (current == null || forceRecompute) {
                val gain = BASELINE_GAINS[pkg] ?: DEFAULT_FALLBACK_GAIN
                settings.saveEngineRmsCalibration(pkg, gain)
                count++
            }
        }
        count
    }
}
