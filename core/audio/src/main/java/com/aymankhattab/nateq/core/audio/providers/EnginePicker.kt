package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * يختار محرك TTS طرفياً يثبّته المستخدم (مثل eSpeak، MultiTTS)
 * بدل المحركات المدمجة (جوجل/سامسونج).
 * يُستخدم من [SystemVoiceProvider] ومن متحدث الإعلانات المستقلة
 * (البطارية / المتصل) لضمان اتساق اختيار المحرك في كل مكان.
 *
 * القرارات النقية (الاختيار المفضَّل/الاحتياط وسلسلة اللغات) تُفوض إلى
 * [EngineRegistry] ليكون منطق القرار موحَّداً؛ يبقى هنا فقط ما يعتمد
 * على Context (الاستعلام عن المحركات المثبّتة في النظام ديناميكياً).
 */
object EnginePicker {

    /** محرك TTS مثبّت في النظام مع تسميته الظاهرة للمستخدم */
    data class InstalledEngine(val packageName: String, val label: String)

    /** هل الحزمة قارئ شاشة (لا تُختار تلقائياً)؟ */
    fun isScreenReader(packageName: String): Boolean {
        return EngineRegistry.isScreenReader(packageName)
    }

    /** كل محركات TTS المثبتة في النظام (تُستعلم ديناميكياً) مع تسمياتها */
    fun installedEngines(context: Context): List<InstalledEngine> {
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
                val pkg = ri.serviceInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                InstalledEngine(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
    }

    /** كل حزم محركات TTS المثبتة في النظام (تُستعلم ديناميكياً) */
    fun installedEnginePackages(context: Context): List<String> {
        return installedEngines(context).map { it.packageName }
    }

    /**
     * يختار المحرك المفضّل من قائمة الحزم المثبتة وفق ترتيب
     * الأولوية المفضَّلة، ثم أي محرك مثبّت ليس قارئ شاشة. (من
     * [EngineRegistry]). منطق نقي قابل للاختبار دون Context.
     */
    fun pickPreferredEngineFrom(installed: Collection<String>): String? {
        return EngineRegistry.pickPreferredEngineFrom(installed)
    }

    /**
     * محرك الاحتياط بعد فشل محرك أو أكثر في النطق، باستبعاد تراكمي
     * لسد سجلّ الفشل. (من [EngineRegistry]). منطق نقي قابل للاختبار.
     */
    fun pickFallbackEngineFrom(
        installed: Collection<String>,
        failedEngines: Set<String>
    ): String? {
        return EngineRegistry.pickFallbackEngineFrom(
            installed,
            failedEngines
        )
    }

    /**
     * يختار المحرك الذي ينطق به التطبيق عند عدم تحديد المستخدم لمحرك يدوياً:
     * 1) محرك مفضَّل معروف بنطقٍ حقيقي (MultiTTS/جوجل/سامسونج…) إن وُجد.
     * 2) وإلا أي محرك مثبّت ليس قارئ شاشة (ملاذ أخير).
     * يُتجنَّب في الاختيار التلقائي قارئات الشاشة (TalkBack/…)
     * لأنها لا تُنتج صوتاً عبر synthesize القياسي فتجعل «لا صوت يُسمع».
     */
    fun pickEnginePackage(context: Context): String? {
        return pickPreferredEngineFrom(installedEnginePackages(context))
    }

    /** حزمة محرك جوجل (الملاذ الأخير المضمون) إن كانت مثبّتة. */
    fun googleEnginePackage(context: Context): String? {
        return "com.google.android.tts"
            .takeIf { installedEnginePackages(context).contains(it) }
    }
}