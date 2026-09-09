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
 */
object EnginePicker {

    /** محركات النطق الحقيقية التي نمنحها الأولوية عند اختيار تلقائي، لأنها
     *  مضمونةً تُنتج صوتاً قياسياً (على عكس قارئات الشاشة). الترتيب يفضّل
     *  MultiTTS (صوت قياسي مرن) ثم محرك النظام الرسمي
     *  (جوجل فسامسونج فـ AOSP). */
    private val preferredEngines = listOf(
        "org.nobody.multitts",
        "com.google.android.tts",
        "com.samsung.SMT",
        "com.svox.pico"
    )

    /** قارئات الشاشة التي تُستثنى من الاختيار التلقائي: لا تُنتج صوتاً عبر
     *  TextToSpeech.synthesize القياسي فتجعل المستخدم بلا صوت. تبقى ظاهرة
     *  في واجهة المحركات للاختيار اليدوي الصريح (بعض المستخدمين يفضّلها).
     *  Talkman/Jieshuo (com.nirenr.talkman) قارئ ومحرك معاً: يسجّل نفسه
     *  TTS عبر eSpeak ويردّ بـ getVoices لغاتٍ نظرية (af/am/…) بلا بيانات
     *  مثبتة فعلياً على الجهاز — يستثنى من المساهمة باللغات المكتشفة ويبقى
     *  قابلاً للاختيار اليدوي كأي قارئ آخر. */
    private val screenReaderPackages = setOf(
        "com.google.android.marvin.talkback",   // TalkBack جوجل
        "com.samsung.accessibility",            // TalkBack سامسونج
        // Jieshuo/Talkman (قارئ + محرك eSpeak)
        "com.nirenr.talkman"
    )

    /** محرك TTS مثبّت في النظام مع تسميته الظاهرة للمستخدم */
    data class InstalledEngine(val packageName: String, val label: String)

    /** هل الحزمة قارئ شاشة (لا تُختار تلقائياً)؟ */
    fun isScreenReader(packageName: String): Boolean {
        return packageName in screenReaderPackages
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
     * يختار المحرك المفضّل من قائمة الحزم المثبتة وفق ترتيب [preferredEngines]،
     * ثم أي محرك مثبّت ليس قارئ شاشة كمسار احتياطي آمن (بدل العودة null).
     * منطق نقي قابل للاختبار دون Context.
     */
    fun pickPreferredEngineFrom(installed: Collection<String>): String? {
        preferredEngines.forEach { pkg ->
            if (installed.contains(pkg)) return pkg
        }
        // المسار الاحتياطي: أي محرك حقيقي (غير قارئ شاشة) بدل null.
        return installed.firstOrNull { !isScreenReader(it) }
    }

    /**
     * يختار محرك الاحتياط بعد فشل محرك أو أكثر في النطق: يستبعد **كل** المحركات
     * الفاشلة سابقاً (سجل [failedEngines]) من القائمة ثم يعتمد على
     * [pickPreferredEngineFrom] على المتبقّي — فيُفضَّل جوجل (وإن لم يوجد، أي
     * محرك حقيقي آخر بالترتيب: MultiTTS/سامسونج/…). يدعم الأسواق التي لا تصلها
     * خدمة جوجل (الصين مثلاً). منطق نقي قابل للاختبار دون Context.
     *
     * بالاستبعاد التراكمي يُمنع «تأرجح التراجع» (ping-pong): لو فشل المحركان
     * A ثم B معاً فلن يُعاد A (المفضّل الأول) لأن الاثنين مستبعدان من القائمة
     * — فكان الاستبعادُ السابق للمحرك الأخير الفاشل فقط يُعيد الأعلى أولويةً
     * وتترنّح المحاولة بين المحركين حتى استنفاد الذاكرة.
     */
    fun pickFallbackEngineFrom(
        installed: Collection<String>,
        failedEngines: Set<String>
    ): String? {
        return pickPreferredEngineFrom(
            installed.filter { it !in failedEngines }
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
