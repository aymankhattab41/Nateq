package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.aymankhattab.nateq.core.audio.engine.VoiceCatalog
import com.aymankhattab.nateq.core.audio.enginehost.EngineHostClient

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

    /** مدة بقاء نتيجة مسح محركات النظام قبل إعادة استعلام النظام. */
    internal const val CACHE_TTL_MS = 5L * 60 * 1000

    /**
     * مهلة كاش المحركات المدمجة (ع٢٩-9): ملفاتها تتغيّر بلا تثبيت
     * حزمة (تنزيل/حذف/هجرة) فلا يصحّ بقاء خمس دقائق كاشفاً.
     */
    internal const val EMBEDDED_CACHE_TTL_MS = 15_000L

    /** المسح المخزَّن مؤقتاً (كاش بلا إعادة استعلام PackageManager في كل
     *  دورة نطق). يُلغى بنفسه بعد [CACHE_TTL_MS] أو صراحةً عبر
     *  [invalidateCache] (أحداث حزمة/تثبيت محرك/تغيّر التخزين). */
    @Volatile
    private var systemCache: CachedEngines? = null

    @Volatile
    private var embeddedCache: CachedEngines? = null

    private data class CachedEngines(
        val stamp: Long,
        val engines: List<InstalledEngine>
    )

    /** مصدر الزمن — حقنة اختبار لتقادم الكاش. */
    internal var nowProvider: () -> Long = { System.currentTimeMillis() }

    /** إبطال الكاش — يُستدعى عند تثبيت/إزالة/استبدال أي حزمة. */
    fun invalidateCache() {
        systemCache = null
        embeddedCache = null
    }

    /**
     * إبطال كاش المحركات مع ذاكرة اكتشاف الأصوات (ع٢٩-9): يُستدعى
     * عند كل تغيير في محتوى التخزين (تنزيل/حذف/استيراد/نقل/تهيئة).
     */
    fun invalidateAfterStorageChange() {
        invalidateCache()
        VoiceCatalog.noteStorageChange()
        // يُبطل أيضاً ذاكرة تهيئة المضيف كي يُعاد نداؤه فيعيد بناء
        // المحرّك إن تغيّرت ترويسات الأصوات (تنزيل/حذف صوت مدمج).
        EngineHostClient.onStorageChanged()
    }

    /** عدد المحركات في الكاش (0 قبل المسح أو بعده) — للفحص الآلي. */
    internal fun cachedEngineCount(): Int =
        (systemCache?.engines?.size ?: 0) +
            (embeddedCache?.engines?.size ?: 0)

    /** هل الحزمة قارئ شاشة (لا تُختار تلقائياً)؟ */
    fun isScreenReader(packageName: String): Boolean {
        return EngineRegistry.isScreenReader(packageName)
    }

    /** كل محركات TTS المثبتة في النظام (تُستعلم ديناميكياً) مع تسمياتها */
    fun installedEngines(context: Context): List<InstalledEngine> {
        EngineStatusRegistry.setFilesDir(context.filesDir)
        val now = nowProvider()

        val system = systemCache
            ?.takeIf { now - it.stamp < CACHE_TTL_MS }
            ?.engines
            ?: scanSystemEngines(context).also {
                systemCache = CachedEngines(now, it)
            }

        val embedded = embeddedCache
            ?.takeIf { now - it.stamp < EMBEDDED_CACHE_TTL_MS }
            ?.engines
            ?: scanEmbeddedEngines(context).also {
                embeddedCache = CachedEngines(now, it)
            }

        return (system + embedded).distinctBy { it.packageName }
    }

    /** مسح محركات النظام عبر PackageManager (مهلة 5 دقائق). */
    private fun scanSystemEngines(
        context: Context
    ): List<InstalledEngine> {
        val pm = context.packageManager
        val intent = Intent("android.intent.action.TTS_SERVICE")
        val resolveInfos: List<ResolveInfo> =
            pm.queryIntentServices(
                intent,
                // MATCH_ALL لتشمل المحركات غير-المُصدَّرة التي يعلنها قارئات
                // الشاشة (مثل Jieshuo/TalkMan) ولا تظهر بدونها على أندرويد 7+
                PackageManager.GET_META_DATA or PackageManager.MATCH_ALL
            )
        return resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.serviceInfo?.packageName
                    ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                InstalledEngine(pkg, ri.loadLabel(pm).toString())
            }
    }

    /**
     * مسح المحركات المدمجة (مهلة 15 ثانية ع٢٩-9): اعتمادها على ملفات
     * يتغيّر محتواها بلا أي تثبيت حزمة.
     */
    private fun scanEmbeddedEngines(
        context: Context
    ): List<InstalledEngine> {
        val engines = mutableListOf<InstalledEngine>()

        try {
            val vocalizer = EmbeddedProviders.vocalizer(context)
            scanOneEmbedded(
                engines,
                vocalizer,
                VocalizerEmbeddedVoiceProvider.PROVIDER_ID
            )
        } catch (t: Throwable) {
            // ع٢٩-8: السبب يُسجَّل ولا يُبتلع بصمت.
            EngineStatusRegistry.noteFailure(
                VocalizerEmbeddedVoiceProvider.PROVIDER_ID,
                "SCAN_FAILED",
                t.message ?: t.javaClass.simpleName
            )
        }

        return engines.distinctBy { it.packageName }
    }

    /**
     * يفحص مزوداً مدمجاً واحداً ويحدّث سجلّ حالته (ع٢٩-8): الملفات
     * موجودة ⇐ `VOICES_PRESENT` ويُعرض؛ لا ملفات ⇐ `NOT_INSTALLED`
     * ولكن المحرك يُعرض دائماً في القائمة مع شارة توضح حالته.
     */
    private fun scanOneEmbedded(
        engines: MutableList<InstalledEngine>,
        provider: VoiceProvider,
        providerId: String
    ) {
        if (provider.isConfigured()) {
            EngineStatusRegistry.noteFilesPresent(providerId)
            engines.add(InstalledEngine(providerId, provider.displayName))
        } else {
            EngineStatusRegistry.noteFilesAbsent(providerId)
        }
    }

    /** كل حزم محركات TTS المثبتة في النظام (تُستعلم ديناميكياً) */
    fun installedEnginePackages(context: Context): List<String> {
        return installedEngines(context).map { it.packageName }
    }

    /** تسخين الكاش في الخلفية — يُستدعى عند بدء التطبيق لتجنب الاستعلام
     *  المتزامن عند أول فتح للإعدادات. يبدأ معه هجرة مجلدات
     *  التخزين القديمة إلى الداخلي (ع٢٩-3). */
    fun warmupCache(context: Context) {
        EmbeddedStorage.startBackgroundMigration(context)
        try {
            installedEngines(context)
        } catch (e: Exception) {
            // تجاهل — الكاش سيُبنى عند الطلب الفعلي
        }
    }
}
