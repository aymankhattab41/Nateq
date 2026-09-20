package com.aymankhattab.nateq.core.audio.providers

/**
 * سقف جلسات التخليق المتزامنة على منفّخ التخليق
 * (بند ب.txt 3.3 + مرحلة 7): الحجم الفعلي يُقاس ديناميكياً من عدد
 * المحركات المثبّتة ضمن حدٍّ أعلى لا يُتجاوز مهما كثرت المحركات —
 * نصٌّ مختلط عبر N محرك يتوازى حتى N، والجلسات على محركٍ واحد تبقى
 * متتالية بقفلٍ لكل مثيل.
 */
internal const val MAX_SYNTH_POOL = 4

// عتبات الذاكرة الكلية لسقف المسبح: كل مثيل TextToSpeech حي يستهلك
// عشرات الميغابايتات، فجهاز 2GB لا يحمل ما يحمله جهاز 8GB.
private const val LOW_MEM_BYTES = 3L * 1024 * 1024 * 1024
private const val MID_MEM_BYTES = 4L * 1024 * 1024 * 1024
private const val LOW_MEM_CAP = 2
private const val MID_MEM_CAP = 3

/**
 * حجم منفّذ التخليق النهائي (مرحلة 7): عدد المحركات [installedCount]
 * المثبّتة يُقاس ضِغْطاً ضمن [1, MAX_SYNTH_POOL] — فيبقى خيطٌ واحد على
 * الأقل حتى بلا محركات خارجية، ولا يتجاوز المنفّخ السقفَ مهما كثرت
 * المحركات (توفيراً لموارد الجهدُ على الأجهزة المنخفضة).
 *
 * [totalMemBytes] (اختياري) يضيّق السقف على الذاكرة الضعيفة: تحت 3GB
 * يُسقَّف باثنين، وتحت 4GB بثلاثة — فلا تُربط مثيلات متزامنة فوق
 * طاقة الجهاز. القيمة الافتراضية تُبقي السلوك القائم.
 *
 * دالة نقيّة (بلا Context/حالة) قابلة للاختبار الآلي المباشر.
 */
internal fun resolvedPoolSize(
    installedCount: Int,
    totalMemBytes: Long = Long.MAX_VALUE
): Int {
    val memCap = when {
        totalMemBytes < LOW_MEM_BYTES -> LOW_MEM_CAP
        totalMemBytes < MID_MEM_BYTES -> MID_MEM_CAP
        else -> MAX_SYNTH_POOL
    }
    return installedCount.coerceIn(1, minOf(MAX_SYNTH_POOL, memCap))
}