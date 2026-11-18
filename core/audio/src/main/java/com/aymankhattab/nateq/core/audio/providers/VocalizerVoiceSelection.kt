package com.aymankhattab.nateq.core.audio.providers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.aymankhattab.nateq.util.VoiceIdContract
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "VocalizerVoiceSelection"

/** صوتٌ مثبَّت فعلياً في محرك Vocalizer المدمج، مقروءاً من المضيف أو من
 *  واجهة فحص التثبيت الموثوقة (لا من كتالوج التنزيل الثابت). */
internal data class InstalledVocalizerVoice(
    val name: String,
    val language: String?
)

/** نتيجة قرار الاسم: أطابقنا صوتاً مثبتاً أم أعدنا المطلوب كما هو؟ */
internal data class VocalizerVoiceResolution(
    val name: String,
    val matched: Boolean,
    val reason: String
)

/** الأسماء البديلة تُوحَّد عبر [VoiceIdContract.canonicalVoiceName] (بند 1). */

/** أسماء أصوات Vocalizer المعروفة — للتصنيف فقط، لا لاختيار صوت مثبت. */
private val KNOWN_VOICE_NAMES = setOf(
    "laila", "maged", "tarik", "mariam", "ava", "samantha",
    "tom", "serena", "daniel", "oliver", "thomas", "audrey",
    "anna", "yannick", "jorge", "monica", "yelda", "milena"
)

private val warnedNames = ConcurrentHashMap.newKeySet<String>()

/** كسولاً: اختبارات JVM النقية لا تملك Looper principal. */
private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

private fun primaryLanguage(tag: String?): String =
    tag?.trim()?.lowercase(Locale.ROOT)
        ?.substringBefore('-').orEmpty()

/** تطابق لغة الطلب مع لغة الصوت المثبت (ar مع ar-SA، en مع en-US...). */
internal fun languagesMatch(left: String?, right: String?): Boolean {
    val first = primaryLanguage(left)
    val second = primaryLanguage(right)
    return first.isNotEmpty() && first == second
}

/** هل ثمة صوت مثبت يصلح للغة المطلوبة؟ */
internal fun hasInstalledVoiceForLanguage(
    languageTag: String?,
    installed: List<InstalledVocalizerVoice>
): Boolean = installed.any { languagesMatch(it.language, languageTag) }

/** توحيد الاسم والمرادفات قبل المقارنة (مرجع موحد — بند 1). */
internal fun canonicalVocalizerVoiceName(name: String): String =
    VoiceIdContract.canonicalVoiceName(name)

/** هل الاسم معروف كصوت Vocalizer (بلا كتالوج التنزيل)؟ */
fun isKnownVocalizerVoiceName(voiceName: String?): Boolean {
    val trimmed = voiceName?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase(Locale.ROOT)
    if (lower.startsWith("vocalizer")) return true
    val canonical = canonicalVocalizerVoiceName(trimmed)
        .lowercase(Locale.ROOT)
    if (canonical in KNOWN_VOICE_NAMES) return true
    // أسماء بديلة معروفة تُعدّ معروفة كذلك.
    return VoiceIdContract.sameVoiceName(trimmed, "Tarik") ||
        VoiceIdContract.sameVoiceName(trimmed, "Laila")
}

private fun decideVoice(
    requestedName: String,
    requestedLanguage: String?,
    installed: List<InstalledVocalizerVoice>
): VocalizerVoiceResolution {
    val request = canonicalVocalizerVoiceName(requestedName)
    if (installed.isEmpty()) {
        return VocalizerVoiceResolution(
            requestedName.trim(), false, "لا أصوات Vocalizer مثبتة"
        )
    }
    if (request.isEmpty()) {
        // لا اسم مطلوب (إعداد عام): لا يُطبَّق تطابق جزئي على سلسلة
        // فارغة، بل يُختار صوت مثبت للغة الطلب فقط.
        val byLanguage = installed.firstOrNull {
            languagesMatch(it.language, requestedLanguage)
        }
        return if (byLanguage != null) {
            VocalizerVoiceResolution(
                byLanguage.name, true, "صوت مثبت للغة الطلب"
            )
        } else {
            VocalizerVoiceResolution(
                "", false, "لا صوت مثبت للغة الطلب"
            )
        }
    }
    val exact = installed.firstOrNull {
        canonicalVocalizerVoiceName(it.name)
            .equals(request, ignoreCase = true)
    }
    if (exact != null) {
        return VocalizerVoiceResolution(
            exact.name, true, "تطابق تام مع صوت مثبت"
        )
    }
    val partial = installed.firstOrNull { voice ->
        val known = canonicalVocalizerVoiceName(voice.name)
        known.contains(request, ignoreCase = true) ||
            request.contains(known, ignoreCase = true)
    }
    if (partial != null) {
        return VocalizerVoiceResolution(
            partial.name, true, "تطابق جزئي أو اسم مستعار"
        )
    }
    val sameLanguage = installed.firstOrNull {
        languagesMatch(it.language, requestedLanguage)
    }
    if (sameLanguage != null) {
        return VocalizerVoiceResolution(
            sameLanguage.name, true, "تحويل إلى صوت مثبت للغة الطلب"
        )
    }
    // لا رجوع صامت إلى أول صوت عربي ولا إلى اسم ثابت: يُعاد المطلوب
    // كما هو ليظهر خطأ المضيف صراحةً، ولا تُمسّ القيمة المحفوظة.
    return VocalizerVoiceResolution(
        requestedName.trim(), false, "لا تطابق مع الأصوات المثبتة"
    )
}

/** المقرِّر الوحيد لاسم صوت Vocalizer في كل مسارات النطق. */
internal fun chooseVocalizerVoiceName(
    requestedName: String,
    requestedLanguage: String?,
    installed: List<InstalledVocalizerVoice>
): String = decideVoice(requestedName, requestedLanguage, installed).name

/**
 * نفس القرار مع إعلان صريح حين يغيب الاسم المطلوب عن المثبَّت.
 *
 * القرار الموثّق: نُبقي التحويل إلى صوتٍ مثبت (لئلا يُطلب اسمٌ فاشل)
 * لكن نُعلنه سجلّاً وتنبيهاً مرة واحدة لكل اسم في الجلسة. وإن لم يوجد
 * أي تطابق نُعيد الاسم المطلوب كما هو ليظهر خطأ المضيف صراحةً، دون
 * كتابة أي شيء في SettingsRepository.
 */
internal fun resolveInstalledVocalizerVoice(
    context: Context,
    requestedName: String,
    requestedLanguage: String?,
    installed: List<InstalledVocalizerVoice>
): VocalizerVoiceResolution {
    val result = decideVoice(requestedName, requestedLanguage, installed)
    val requestedText = requestedName.trim()
    // أي اختلاف بين المحفوظ والمختار يُعلَن صراحةً: استبدالاً كان أو
    // تمريراً كما هو. الصمت هنا بالضبط هو ما منع المستخدم من معرفة
    // أن صوته المحفوظ غير مثبت على الجهاز.
    val substituted = requestedText.isNotEmpty() &&
        !result.name.equals(requestedText, ignoreCase = true)
    if (!result.matched || substituted) {
        val installedNames = installed.joinToString { it.name }
        val valPassed = if (result.matched) {
            "استُبدل بصوت مثبت [${result.name}]"
        } else {
            "يُمرَّر كما هو ليظهر خطأ المضيف صراحةً"
        }
        Log.w(
            TAG,
            "الصوت المطلوب [$requestedName] غير موجود بين المثبت " +
                "[$installedNames]: ${result.reason}. $valPassed، " +
                "ولا تُغيَّر القيمة المحفوظة."
        )
        notifyMissingVoiceOnce(context, requestedName)
    }
    return result
}

private fun notifyMissingVoiceOnce(context: Context, name: String) {
    val key = name.trim().lowercase(Locale.ROOT)
    if (key.isEmpty() || !warnedNames.add(key)) return
    mainHandler.post {
        Toast.makeText(
            context.applicationContext,
            "صوت Vocalizer المطلوب ($name) غير مثبت على الجهاز",
            Toast.LENGTH_LONG
        ).show()
    }
}
