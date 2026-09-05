package com.aymankhattab.nateq.providers

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
     *  مضمونةً تُنتج صوتاً قياسياً (على عكس قارئات الشاشة). */
    private val preferredEngines = listOf(
        "com.iflytek.speechcloud",
        "com.svox.pico",
        "com.nuance.dragon.voice",
        "com.ivona.tts",
        "org.nobody.multitts"
    )

    /** محرك TTS مثبّت في النظام مع تسميته الظاهرة للمستخدم */
    data class InstalledEngine(val packageName: String, val label: String)

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
     * يختار المحرك الذي ينطق به التطبيق عند عدم تحديد المستخدم لمحرك يدوياً:
     * 1) محرك مفضَّل معروف بنطقٍ حقيقي (MultiTTS/غيرها) إن وُجد.
     * 2) وإلا جوجل (ملاذ أخير مضمون الأصوات على كل أندرويد).
     * يُتجنَّب في الاختيار التلقائي قارئات الشاشة (Jieshuo/SmartVoice/TalkBack)
     * لأنها لا تُنتج صوتاً عبر synthesize القياسي فتجعل «لا صوت يُسمع».
     * محركات الطرف الأخرى المجهولة لا يُدهَب إليها تلقائياً خشية انعدام الأصوات.
     */
    fun pickEnginePackage(context: Context): String? {
        val installed = installedEnginePackages(context)
        preferredEngines.forEach { pkg ->
            if (installed.contains(pkg)) return pkg
        }
        // الملاذ الأخير المضمون: جوجل.
        return installed.firstOrNull { it == "com.google.android.tts" }
    }

    /** حزمة محرك جوجل (الملاذ الأخير المضمون) إن كانت مثبّتة. */
    fun googleEnginePackage(context: Context): String? {
        return "com.google.android.tts"
            .takeIf { installedEnginePackages(context).contains(it) }
    }
}
