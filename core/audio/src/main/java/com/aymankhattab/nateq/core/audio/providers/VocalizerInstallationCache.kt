package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import java.util.Locale

/**
 * لقطة فحص تثبيت أصوات Vocalizer، مربوطة بـ [EmbeddedStorage.version].
 *
 * المسار الساخن للنطق كان ينفّذ `listFiles`/`getExternalFilesDir` في كل
 * طلب (ست مرات في `NateqTtsService` وحده). اللقطة تُحسب مرة ثم تُعاد
 * ما دام رقم الإصدار ثابتاً، وتُعاد الحسابية فور رفعه بعد تنزيل أو حذف
 * أو استيراد. لا تُلمس `EmbeddedStorage.inventory` (walkTopDown) هنا.
 */
internal object VocalizerInstallationCache {

    internal data class Snapshot(
        val version: Int,
        val families: Set<String>,
        val voices: List<InstalledVocalizerVoice>
    )

    @Volatile
    private var cached: Snapshot? = null

    /**
     * تُعاد اللقطة المخزّنة إن تساوى [version] مع رقمها، وإلا تُحسب
     * من جديد تحت قفل واحد (حارس مزدوج لتفادي الحساب المتزامن).
     */
    internal fun snapshot(
        version: Int = EmbeddedStorage.version,
        compute: () -> Snapshot
    ): Snapshot {
        cached?.let { if (it.version == version) return it }
        return synchronized(this) {
            val current = cached
            if (current != null && current.version == version) {
                current
            } else {
                val fresh = compute().copy(version = version)
                cached = fresh
                fresh
            }
        }
    }

    /** بديل الكاش لـ `VocalizerModelCatalog.isVoiceInstalled(context, …)`. */
    fun isVoiceInstalled(
        context: Context,
        item: VocalizerCatalogItem
    ): Boolean = snapshot { computeSnapshot(context) }
        .families.contains(
            VocalizerVoiceManifest.headerFamily(item.headerFileName)
        )

    /** الأصوات المثبتة فعلياً — تُقرأ من اللقطة ذاتها. */
    fun installedVoices(context: Context): List<InstalledVocalizerVoice> =
        snapshot { computeSnapshot(context) }.voices

    /** للاختبار فقط: إجبار أول قراءة على إعادة الحساب. */
    internal fun resetForTest() {
        cached = null
    }

    private fun computeSnapshot(context: Context): Snapshot {
        val families = LinkedHashSet<String>()
        val voices = LinkedHashSet<InstalledVocalizerVoice>()
        for (dir in EmbeddedStorage.allSearchDirs(
            context, EmbeddedStorage.Engine.VOCALIZER
        )) {
            // الاكتمال دليل التثبيت الموثوق (رأس + بيانات صوت كافية).
            if (!VocalizerVoiceManifest.hasCompleteVoice(dir)) continue
            val headers = dir.listFiles { file ->
                file.isFile &&
                    file.name.endsWith(".hdr", ignoreCase = true) &&
                    file.length() > 0L
            } ?: continue
            for (header in headers) {
                families.add(
                    VocalizerVoiceManifest.headerFamily(header.name)
                )
                parseInstalledVoice(header.name)?.let { voices.add(it) }
            }
        }
        return Snapshot(
            version = EmbeddedStorage.version,
            families = families,
            voices = voices.toList()
        )
    }

    private val HEADER_VOICE_REGEX = Regex(
        "^ve_pipeline_(.+?)_(\\d+)_(.+)\\.hdr$",
        RegexOption.IGNORE_CASE
    )

    /** رموز اللغة داخل اسم الرأس →language tag. */
    private val LANGUAGE_TOKENS = mapOf(
        "arw" to "ar", "ara" to "ar", "ars" to "ar",
        "enu" to "en", "eng" to "en", "engb" to "en",
        "frf" to "fr", "fra" to "fr", "ged" to "de",
        "deu" to "de", "spe" to "es", "esp" to "es",
        "tut" to "tr", "tur" to "tr", "rur" to "ru",
        "rus" to "ru"
    )

    /** استخراج (الاسم، اللغة) من اسم رأس مثبت بلا كتالوج ثابت. */
    private fun parseInstalledVoice(headerName: String):
        InstalledVocalizerVoice? {
        val parts = HEADER_VOICE_REGEX.find(headerName)?.groupValues
            ?: return null
        val raw = parts[1]
        val token = raw.substringBefore('_').lowercase(Locale.ROOT)
        val namePart = raw.substringAfter('_', raw)
        if (namePart.isBlank()) return null
        val display = namePart.split('_').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase(Locale.ROOT) }
        }
        return InstalledVocalizerVoice(
            name = display,
            language = LANGUAGE_TOKENS[token] ?: token
        )
    }
}
