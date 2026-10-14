package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.audio.providers.VocalizerCatalogItem
import com.aymankhattab.nateq.core.audio.providers.VocalizerModelCatalog
import java.util.Locale

/**
 * عقدةُ صوتٍ واحدة في الشجرة الهرمية، تحمل تعدّد جوداته
 * (متغيّراته). كلُّ عنصرٍ في `variants` نموذجٌ قابل للتنزيل.
 */
internal data class VoiceNode<T>(
    val key: String,
    val titleAr: String,
    val titleEn: String,
    val variants: List<T>
)

/**
 * منطقُ تنظيم كتالوجات الأصوات المدمجة إلى شجرة
 * (محرك ← لغة ← صوت ← جودة). نقيٌّ بلا اعتماد على أندرويد
 * كي يُختبَر على JVM مباشرةً.
 */
internal object EmbeddedVoiceTree {

    /** لغاتُ Vocalizer المميّزة (بلا تكرار إقليمي) مرتّبةً بالرمز. */
    fun vocalizerLanguages(): List<Locale> =
        VocalizerModelCatalog.ITEMS
            .map { it.locale }
            .distinctBy { it.language }
            .sortedBy { it.language }

    /** أصواتُ لغة Vocalizer مجمّعةً؛ كلُّ صوتٍ يضمّ جوداتِه. */
    fun vocalizerVoices(
        locale: Locale
    ): List<VoiceNode<VocalizerCatalogItem>> =
        VocalizerModelCatalog.ITEMS
            .filter { it.locale.language == locale.language }
            .groupBy { it.voiceName }
            .map { (key, items) ->
                val sample = items.first()
                VoiceNode(
                    key = key,
                    titleAr = vocalizerTitle(sample, true),
                    titleEn = vocalizerTitle(sample, false),
                    variants = items
                )
            }

    /** اسمُ صوت Vocalizer المعروض (بلا لاحقة الجودة). */
    fun vocalizerTitle(
        item: VocalizerCatalogItem,
        isAr: Boolean
    ): String {
        val full = if (isAr) item.displayNameAr else item.displayNameEn
        return full.substringBefore(" (").trim()
    }
}
