package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * يكتشف محركات TTS الطرفية التي يثبّتها المستخدم (مثل eSpeak، MultiTTS)
 * بدل المحركات المدمجة (جوجل/سامسونج). يُستخدم من [SystemVoiceProvider]
 * ومن متحدث الإعلانات المستقلة (البطارية / المتصل) لتتّحد قائمة المحركات
 * المعروضة في كل مكان.
 *
 * هنا فقط ما يعتمد على Context (الاستعلام عن المحركات المثبّتة في النظام
 * ديناميكياً مع كاش قصير)؛ الحسم النهائي للمحرك عند النطق يتم في
 * [AnnouncementSpeaker] و [SystemVoiceProvider].
 */
object EnginePicker {

    /** محرك TTS مثبّت في النظام مع تسميته الظاهرة للمستخدم */
    data class InstalledEngine(val packageName: String, val label: String)

    /** مدة بقاء نتيجة مسح المحركات قبل إعادة استعلام النظام. */
    internal const val CACHE_TTL_MS = 5L * 60 * 1000

    /** المسح المخزَّن مؤقتاً (كاش بلا إعادة استعلام PackageManager في كل
     *  دورة نطق). يُلغى بنفسه بعد [CACHE_TTL_MS] أو صراحةً عبر
     *  [invalidateCache] (أحداث حزمة/تثبيت محرك). */
    @Volatile
    private var cache: CachedEngines? = null

    private data class CachedEngines(
        val stamp: Long,
        val engines: List<InstalledEngine>
    )

    /** مصدر الزمن — حقنة اختبار لتقادم الكاش. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    /** إبطال الكاش — يُستدعى عند تثبيت/إزالة/استبدال أي حزمة. */
    fun invalidateCache() {
        cache = null
    }

    /** عدد المحركات في الكاش (0 قبل المسح أو بعده) — للفحص الآلي. */
    internal fun cachedEngineCount(): Int = cache?.engines?.size ?: 0

    /** هل الحزمة قارئ شاشة (لا تُختار تلقائياً)؟ */
    fun isScreenReader(packageName: String): Boolean {
        return EngineRegistry.isScreenReader(packageName)
    }

    /** كل محركات TTS المثبتة في النظام (تُستعلم ديناميكياً) مع تسمياتها */
    fun installedEngines(context: Context): List<InstalledEngine> {
        val now = nowProvider()
        cache?.let { cached ->
            if (now - cached.stamp < CACHE_TTL_MS) {
                return cached.engines
            }
        }
        val pm = context.packageManager
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos: List<ResolveInfo> =
            pm.queryIntentServices(
                intent,
                // MATCH_ALL لتشمل المحركات غير-المُصدَّرة التي يعلنها قارئات
                // الشاشة (مثل Jieshuo/TalkMan) ولا تظهر بدونها على أندرويد 7+
                PackageManager.GET_META_DATA or PackageManager.MATCH_ALL
            )
        val engines = resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.serviceInfo?.packageName
                    ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                InstalledEngine(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
        cache = CachedEngines(now, engines)
        return engines
    }

    /** كل حزم محركات TTS المثبتة في النظام (تُستعلم ديناميكياً) */
    fun installedEnginePackages(context: Context): List<String> {
        return installedEngines(context).map { it.packageName }
    }

}