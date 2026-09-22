package com.aymankhattab.nateq.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aymankhattab.nateq.engine.pipeline.TashkeelStripStep
import com.aymankhattab.nateq.util.NateqJson
import com.aymankhattab.nateq.util.optObject
import java.lang.reflect.Type

/**
 * قاموس النطق الشخصي - يسمح للمستخدم بتعريف نطق مخصص للكلمات/الاختصارات
 * أمثلة: "د." → "دكتور"، "ص" → "صفحة"، "HTTP" → "إتش تي تي بي"
 *
 * منذ بند الأوامر د.3.5 يدعم القاموس كلا النطاقَيْن: عام ([languageTag] =
 * null: يُخزَّن تحت المفتاح "dictionary" — السلوك القائم) وخاصٌّ بلغةٍ
 * ([languageTag] = "ar-EG" مثلًا: يُخزَّن مع بقية الطبقات تحت المفتاح
 * "dictionary_scopes"). عند النطق
 * بلغةٍ يُدمج العامُّ ثم الخاصُّ (الخاص يعلو العام) بآلةِ مطابقةٍ منفصلة
 * لكل وسم، والاستدعاءاتُ القائمة بلا وسمٍ تبقى سليمةً تماماً.
 */
class PronunciationDictionary(
    private val context: Context,
    /** الخنق بين فحصَين للقرص عند رصد تعديلات عملية الواجهة (نانوثانية).
     *  الافتراضي ثانية ونصف؛ وقابل للحقن لاختباره حتمياً. */
    private val diskCheckThrottleNanos: Long =
        DISK_CHECK_THROTTLE_NANOS
) {

    companion object {
        // حدود دفاعية ضد ملفات JSON خبيثة/ضخمة واردة من SAF أو مصادر أخرى
        private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024   // 2 MB
        private const val MAX_IMPORT_ENTRIES = 5000
        private const val MAX_KEY_LENGTH = 200
        private const val MAX_VALUE_LENGTH = 200
        // الخنق بين فحصَي القرص في reloadIfChanged: استدعاء lastModified()
        // على ملف التفضيلات المُنفَّذ لكل فقرة صوتية — الخنق يقلّص الـ I/O.
        private const val DISK_CHECK_THROTTLE_NANOS =
            1_500_000_000L // 1.5 ثانية
        // مفتاح تتبّع هجرة حذف الإدخالات الافتراضية القديمة (تُنفَّذ مرة واحدة)
        private const val KEY_DEFAULTS_MIGRATED = "defaults_migrated_to_empty"
        // مفتاح تخزين الطبقات اللغوية: كائن JSON واحد {وسم: {مفتاح: قيمة}}
        // — لا نعدّ مفاتيح التفضيلات كلها ([SharedPreferences.all] غير مدعوم
        // في EncryptedSharedPreferences على بعض البيئات) فلا مخاطرة ولا أطلال.
        private const val KEY_SCOPES = "dictionary_scopes"
    }

    // لا يجوز أبداً أن يرمي إنشاء التخزين المشفّر: خدمة :tts تُنشئ هذا الكائن
    // في onCreate()، وأي استثناء هنا (Keystore معطوب، Tink مقطوع، وضع محاكي…)
    // يُسقط الخدمة فيرفض نظام سامسونج المحرك برسالة "يستمر التطبيق في التوقف".
    // عند الفشل يعمل القاموس بالذاكرة فقط (بلا حفظ دائم) ولا ينهار النطق.
    private val prefs: android.content.SharedPreferences? = openPrefs()

    /** يفتح مثيلاً جديداً من تخزين التفضيلات المشفّر. كل مكالمة تنشئ كائناً
     *  جديداً بلا كاش داخلي سابق — تُستخدم للقراءة في [loadFromPrefs] لأن
     *  الكائن العضو [prefs] يخزّن نواتج فك التشفير في ذاكرته فلا يرى
     *  تعديل عملية الواجهة الأجنبية حتى لو تغيّر طابع الملف. */
    private fun openPrefs(): android.content.SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "nateq_pronunciation_dict",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        null
    }

    // مظلة JSON الموحّدة (البند 3): كل JSON يمر عبر NateqJson في مكان واحد.
    // «خريطة لقطة» تُستبدل مرجعاً ذرياً (Copy-on-Write) عند كل تغيير أو إعادة
    // تحميل حتى لا ترى خيوط النطق المتزامنة قاموساً جزئياً أثناء القراءة.
    @Volatile
    private var entries: Map<String, String> = emptyMap()

    // الطبقة الخاصة باللغات: وسم اللغة ← خرائطها الخاصة (Copy-on-Write مثل
    // [entries]). تُخزَّن كل الطبقات معاً تحت المفتاح [KEY_SCOPES]، وخريطةُ
    // النطق لأي لغة = العام [entries] فوقه الخاص (الخاص يعلو العام).
    @Volatile
    private var langEntries: Map<String, Map<String, String>> = emptyMap()

    // عداد تغيير: يزداد مع كل تبديلِ مرجعٍ ذري — يُقيَّد به مخبأُ آلة
    // Aho-Corasick مع وسم اللغة فلا تُبنى آلةٌ من قاموسٍ يتبدل أثناء البناء.
    @Volatile
    private var version = 0L

    // مخبأ آلة Aho-Corasick مقيّد بهوية لقطة المكتتبة: يُبنى من لقطة كاملة
    // (العام أو المدمج لكل وسم) ويُعاد بناؤه عند تبديل [version] أو الوسم.
    private data class MachineCache(
        val langTag: String?,
        val version: Long,
        val snapshot: Map<String, String>,
        val machine: AhoCorasick
    )

    @Volatile
    private var cache: MachineCache? = null
    // نوع بالمفتاح النصي القيمة النصية — من مظلة NateqJson (لا TypeToken:
    // مقاوم لقصّ R8 للتوقيعات العامة).
    private val typeToken: Type = NateqJson.mapStringOf(String::class.java)

    // ملف التفضيلات المشفّر على القرص — يُرصد طابعه لاكتشاف تعديلات عملية
    // الواجهة المنفصلة عن عملية :tts دون إعادة فتح التفضيلات في كل نطق.
    private val prefsFile: java.io.File? =
        if (prefs != null) context.filesDir?.parentFile
            ?.let {
                java.io.File(it, "shared_prefs/nateq_pronunciation_dict.xml")
            }
        else null
    // آخر طابع قرأه هذا المثيل من القرص؛ null = يجب إعادة القراءة.
    @Volatile
    private var lastStamp: Long? = null
    // آخر فحص للقرص (ساعة رتية) — يُختنق به stat يُنفَّذ لكل فقرة.
    @Volatile
    private var lastDiskCheckNanos = 0L

    init {
        val (global, langs) = loadFromPrefs()
        entries = global
        langEntries = langs
        removeLegacyDefaultsOnce()
        if (prefs != null) lastStamp = currentStamp()
    }

    /** تطبيع وسم اللغة: يُجرد من الفراغات؛ فارغٌ/بلا وسم = النطاق العام. */
    private fun normalizeLangTag(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    /** خريطة الدمج للوسم: العام أولاً ثم الخاص يعلوه (بلا تعديل [entries]). */
    private fun mergedFor(lang: String?): Map<String, String> {
        if (lang == null) return entries
        val overlay = langEntries[lang] ?: return entries
        if (overlay.isEmpty()) return entries
        return LinkedHashMap(entries).apply { putAll(overlay) }
    }

    /** الإدخالات الافتراضية القديمة التي كانت تُزرَع تلقائياً في نسخ سابقة؛
     *  تُحذف عند الترقية ليبقى القاموس افتراضياً فارغاً ويبنيه المستخدم وحده
     *  (إضافة/تعديل/استيراد/تصدير) دون كلمات مفروضة من التطبيق. */
    private fun legacyDefaultEntries(): Map<String, String> = mapOf(
            // اختصارات طبية — مع النقطة فقط لمنع الاستبدال
            // غير المقصود في النصوص العادية
            "د." to "دكتور",
            "أ.د" to "أستاذ دكتور",
            "بروفسور" to "بروفيسور",

            // اختصارات عامة — مع رمز التمييز لتجنب استبدال الكلمات الكاملة
            "صـ" to "صفحة",
            "ج." to "جزء",

            // وحدات قياس
            "كم" to "كيلومتر",
            "سم" to "سنتيمتر",
            "مم" to "مليمتر",
            "كغ" to "كيلوغرام",
            "غم" to "غرام",
            "مل" to "مليلتر",

            // تقنيات
            "HTTP" to "إتش تي تي بي",
            "HTTPS" to "إتش تي تي بي إس",
            "URL" to "يو آر إل",
            "HTML" to "إتش تي إم إل",
            "CSS" to "سي إس إس",
            "JS" to "جافا سكريبت",
            "API" to "إيه بي آي",
            "JSON" to "جيسون",
            "XML" to "إكس إم إل",
            "SQL" to "سيكويل",
            "CPU" to "سي بي يو",
            "GPU" to "جي بي يو",
            "RAM" to "رام",
            "SSD" to "إس إس دي",
            "USB" to "يو إس بي",
            "WiFi" to "واي فاي",
            "Bluetooth" to "بلوتوث",
            "AI" to "إيه آي",
            "ML" to "إم إل",

            // منظمات
            "WHO" to "منظمة الصحة العالمية",
            "UN" to "الأمم المتحدة",
            "EU" to "الاتحاد الأوروبي",
            "NASA" to "ناسا",
            "FIFA" to "فيفا",
            "UEFA" to "يويفا",

            // دول (اختصارات)
            "السعودية" to "المملكة العربية السعودية",
            "الإمارات" to "الإمارات العربية المتحدة",
            "أمريكا" to "الولايات المتحدة الأمريكية",
            "بريطانيا" to "المملكة المتحدة",
            "الصين" to "جمهورية الصين الشعبية",
            "روسيا" to "الاتحاد الروسي",

            // رموز رياضية
            "دورى" to "دوري",

            // عملات — يُستخدم الرمز ر.س وما إليه من الوحدات؛ ولا تُحوَّل كلمة
            // "ريال" العامة (قد تكون قطرياً/عمانياً/مغربياً) فيجري إصلاح
            // "50 ريال قطري" من قبل الاستبدال الافتراضي الخاطئ.
            "درهم" to "درهم إماراتي",
            "دينار" to "دينار كويتي",
            "جنيه" to "جنيه مصري",

            // أيام الأسبوع
            "أح" to "الأحد",
            "اث" to "الاثنين",
            "ثث" to "الثلاثاء",
            "أرب" to "الأربعاء",
            "خم" to "الخميس",
            "سبت" to "السبت",

            // شهور
            "ينا" to "يناير",
            "فبر" to "فبراير",
            "مار" to "مارس",
            "أبر" to "أبريل",
            "ماي" to "مايو",
            "يون" to "يونيو",
            "يول" to "يوليو",
            "أغس" to "أغسطس",
            "أكت" to "أكتوبر",
            "نوف" to "نوفمبر",
            "ديس" to "ديسمبر"
        )

    /** هجرة لمرة واحدة: حذف الإدخالات الافتراضية القديمة المخزّنة عند المستخدم.
     *  تُحذف المزاوجات المطابقة للافتراضي فقط
     *  (لا تُمسّ تعديلات المستخدم على نفس المفتاح). */
    private fun removeLegacyDefaultsOnce() {
        val sp = prefs ?: return
        if (sp.getBoolean(KEY_DEFAULTS_MIGRATED, false)) return
        var changed = false
        val updated = LinkedHashMap(entries)
        for ((key, value) in legacyDefaultEntries()) {
            if (updated[key] == value) {
                updated.remove(key)
                changed = true
            }
        }
        // تنظيف إدخالات قديمة ضارة خُزّنت في نسخ سابقة على أجهزة المستخدمين
        // (استُبدل لفظ كتابةً وفاق بحيث شوّهت «50 ريال قطري» و«500 جم»)
        for ((key, badValue) in mapOf(
            "ريال" to "ريال سعودي", "جم" to "الجمعة"
        )) {
            if (updated[key] == badValue) {
                updated.remove(key)
                changed = true
            }
        }
        if (changed) {
            swapEntries(updated)
            save()
        }
        sp.edit().putBoolean(KEY_DEFAULTS_MIGRATED, true).apply()
    }

    /** هل التخزين المشفّر متاح فعلاً (Keystore سليم) أم يُعمل بالذاكرة فقط؟ */
    fun isPersistent(): Boolean = prefs != null

    /** إعادة تحميل الإدخالات من القرص المشفّر — يلتقط التعديلات التي كتبتها
     *  عملية الواجهة المنفصلة عن عملية :tts (الإنشاء يُحمّل مرة واحدة فقط).
     *  إلى خريطة كاملة جديدة ثم تبديل المرجع ذرياً (بلا clear في المنتصف). */
    fun reload() {
        if (prefs == null) return
        val (global, langs) = loadFromPrefs()
        synchronized(this) {
            entries = global
            langEntries = langs
            cache = null
            version++
        }
        lastStamp = currentStamp()
    }

    /** إعادة تحميل فورية فقط إذا تغيّر طابع الملف على القرص منذ آخر قراءة —
     *  فحص طابع أرخص بكثير من إعادة فتح التفضيلات المشفّرة في كل نطق، ويُدعى
     *  تلقائياً من [apply] ليلتقط تعديلات عملية
     *  الواجهة دون إعادة تشغيل الخدمة. يُختنق فحص القرص بثانية ونصفٍ
     *  على الأقل حتى لا يُنفَّذ stat على كل فقرة صوتية. */
    fun reloadIfChanged(): Boolean {
        if (prefs == null) return false
        val now = System.nanoTime()
        if (lastDiskCheckNanos != 0L &&
            now - lastDiskCheckNanos < diskCheckThrottleNanos
        ) return false
        lastDiskCheckNanos = now
        val stamp = currentStamp()
        if (lastStamp == stamp) return false
        val (global, langs) = loadFromPrefs()
        synchronized(this) {
            entries = global
            langEntries = langs
            cache = null
            version++
        }
        lastStamp = stamp
        return true
    }

    /** استبدال مرجع الخريطة ذرياً وإبطال مخبأ الآلة المقيّد به. */
    private fun swapEntries(updated: Map<String, String>) {
        entries = updated
        cache = null
        version++
    }

    /** استبدال مرجع طبقة اللغة ذرياً — تُحذف الطبقة إذا أُفرغت كلياً. */
    private fun swapLangEntries(lang: String, updated: Map<String, String>) {
        val copy = LinkedHashMap(langEntries)
        if (updated.isEmpty()) copy.remove(lang) else copy[lang] = updated
        langEntries = copy
        cache = null
        version++
    }

    private fun currentStamp(): Long =
        prefsFile?.let { if (it.exists()) it.lastModified() else 0L } ?: 0L

    /** تطبيق القاموس على نص — بالدمج العام+الخاص إن حُدد [languageTag]. */
    fun apply(text: String, languageTag: String? = null): String {
        // اكتشاف تعديلات عملية الواجهة على القرص قبل كل تطبيق
        reloadIfChanged()
        val lang = normalizeLangTag(languageTag)
        var held = cache
        if (held == null || held.langTag != lang || held.version != version) {
            held = synchronized(this) {
                val currentVersion = version
                val cached = cache
                if (cached != null && cached.langTag == lang &&
                    cached.version == currentVersion
                ) {
                    cached
                } else {
                    val merged = mergedFor(lang)
                    MachineCache(
                        lang, currentVersion, merged, AhoCorasick(merged)
                    ).also { cache = it }
                }
            }
        }
        return held.machine.apply(text)
    }

    /** إضافة إدخال جديد — في النطاق العام أو طبقةِ [languageTag] الخاصة. */
    fun addEntry(
        abbreviation: String,
        pronunciation: String,
        languageTag: String? = null
    ): Boolean {
        if (abbreviation.isBlank() || pronunciation.isBlank()) return false
        val key = abbreviation.trim()
        val value = pronunciation.trim()
        if (key.length > MAX_KEY_LENGTH ||
            value.length > MAX_VALUE_LENGTH
        ) return false
        val lang = normalizeLangTag(languageTag)
        // بند 3.13: تثبيت القراءة-التعديل-الكتابة داخل قفل حتى لا تضيع
        // إدخالات من استدعاءات متزامنة من خيطين (كانا يقرآن نفس اللقطة
        // فيستبدل كلٌّ منهما عملَ الآخر).
        synchronized(this) {
            if (lang == null) {
                val updated = LinkedHashMap(entries)
                updated[key] = value
                swapEntries(updated)
            } else {
                val overlay = LinkedHashMap(langEntries[lang] ?: emptyMap())
                overlay[key] = value
                swapLangEntries(lang, overlay)
            }
        }
        return save()
    }

    /** حذف إدخال — من النطاق العام أو طبقةِ [languageTag] الخاصة. */
    fun removeEntry(
        abbreviation: String,
        languageTag: String? = null
    ): Boolean {
        val lang = normalizeLangTag(languageTag)
        var changed = false
        synchronized(this) {
            if (lang == null) {
                val updated = LinkedHashMap(entries)
                changed = updated.remove(abbreviation.trim()) != null
                if (changed) swapEntries(updated)
            } else {
                val overlay = LinkedHashMap(
                    langEntries[lang] ?: emptyMap()
                )
                changed = overlay.remove(abbreviation.trim()) != null
                if (changed) swapLangEntries(lang, overlay)
            }
        }
        return if (changed) save() else false
    }

    /** تفريغ القاموس بالكامل (الذاكرة والقرص معاً) — يُستدعى عند «استعادة
     *  الافتراضيات»: إن نُظّف الملف وحده بقي المخزون في الذاكرة، فيظل
     *  يُقرأ من الخريطة وُيعاد كتابته للملف بمجرد إضافة أي كلمة جديدة. */
    fun clear() {
        synchronized(this) {
            swapEntries(emptyMap())
            langEntries = emptyMap()
            cache = null
            version++
        }
        save()
    }

    /** الحصول على إدخالات نطاقٍ — العام أو طبقةِ [languageTag] وحدها. */
    fun getAllEntries(languageTag: String? = null): Map<String, String> {
        val lang = normalizeLangTag(languageTag)
        return if (lang == null) entries.toMap()
        else (langEntries[lang] ?: emptyMap()).toMap()
    }

    /** استيراد قاموس من JSON — يتخطى الصفوف غير الصالحة بدل إفشال الاستيراد
     *  كاملاً، ويدعم الدمج مع الإدخالات الحالية أو الاستبدال الكامل، وكلٌّ
     *  في نطاقه ([languageTag] null عام وإلا خاصة بلغة).
     *  @param merge true: يُدمج مع الحالي (تتغلب الإدخالات الجديدة على المفاتيح
     *               المكررة مع بقاء بقية الحالي)؛ false: يحل محله بالكامل.
     *  @return true إن طُبِّق صف صالح واحد على الأقل (الذاكرة تتحدّث دائماً؛
     *          لا يُعدّ فشل التخزين المشفّر نجاحاً). */
    fun importFromJson(
        json: String,
        merge: Boolean = false,
        languageTag: String? = null
    ): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        // تجزئة بلا رمي عبر المظلة: فاسد/غير مطابق ← null ← نرفض الاستيراد.
        val map = NateqJson.fromJson<Map<*, *>>(json, typeToken)
            as? Map<*, *> ?: return false

        // فلترة الصفوف الصالحة فقط: مفتاح/قيمة نصيان غير فارغين ضمن الحدود
        val valid = LinkedHashMap<String, String>()
        for ((rawKey, rawValue) in map) {
            if (rawKey !is String || rawValue !is String) continue
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) continue
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) continue
            valid[key] = value
        }
        if (valid.isEmpty()) return false

        // السقف التراكمي: الحالي أولاً ثم الجديد بترتيبه حتى MAX_IMPORT_ENTRIES
        // (القفل يثبّت قراءة الحالي مع البناء والاستبدال — بند 3.13).
        val lang = normalizeLangTag(languageTag)
        val imported = synchronized(this) {
            val base = if (lang == null) entries
            else (langEntries[lang] ?: emptyMap())
            val updated = if (merge) LinkedHashMap(base)
                else LinkedHashMap<String, String>()
            var count = 0
            for ((key, value) in valid) {
                if (updated.size >= MAX_IMPORT_ENTRIES) break
                updated[key] = value
                count++
            }
            if (count > 0) {
                if (lang == null) swapEntries(updated)
                else swapLangEntries(lang, updated)
            }
            count
        }
        if (imported == 0) return false

        val sp = prefs ?: return true
        return save()
    }

    /** تصدير نطاقٍ إلى JSON — العام أو طبقةِ [languageTag] وحدها. */
    fun exportToJson(languageTag: String? = null): String {
        val lang = normalizeLangTag(languageTag)
        val source = if (lang == null) entries
        else (langEntries[lang] ?: emptyMap())
        return NateqJson.toJson(source)
    }

    private fun loadFromPrefs():
        Pair<Map<String, String>, Map<String, Map<String, String>>> {
        // تُقرأ القيم من «مثيل طازج» (انظر [openPrefs]) لا من الكائن العضو
        // المخبئ — تصطاد تعديلات عملية الواجهة عبر الطابع. على فشل الفتح أو
        // الفك نقف عند آخر ما رصدناه بدل مسح القاموس الحي (تفضيلُ مستخدمٍ
        // حقيقي لا يجوز أن يُمحى بسبب خللٍ عابر). القراءة بمفتاحين معلومين
        // فقط — بلا عدّ [SharedPreferences.all] غير المدعوم في التخزين
        // المشفّر على بعض البيئات فيُسقط الحفظ كاملاً.
        val sp = openPrefs() ?: return entries to langEntries
        val globalJson = try {
            sp.getString("dictionary", "{}")
        } catch (t: Throwable) {
            return entries to langEntries
        }
        val scopesJson = try {
            sp.getString(KEY_SCOPES, "{}")
        } catch (t: Throwable) {
            return parseEntries(globalJson) to langEntries
        }
        return parseEntries(globalJson) to parseScopes(scopesJson)
    }

    /** تجزئة دفاعية بلا رمي: مفتاح/قيمة نصيان غير فارغين ضمن الحدود فقط. */
    private fun parseEntries(json: String?): Map<String, String> {
        val raw = if (json == null) emptyMap<Any, Any>()
        else (NateqJson.fromJson<Map<*, *>>(json, typeToken)
            as? Map<*, *> ?: emptyMap<Any, Any>())
        val fresh = LinkedHashMap<String, String>()
        for ((k, v) in raw) {
            if (k !is String || v !is String) continue
            val key = k.trim()
            val value = v.trim()
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) continue
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) continue
            fresh[key] = value
        }
        return fresh
    }

    /** تجزئة طبقات اللغات دفاعياً بلا رمي: {وسم: {مفتاح: قيمة}} — يُتخطى
     *  كل وسمٍ غير كائنٍ وكل صفٍّ غير نصيٍّ بدل إسقاط الطبقات كلها. */
    private fun parseScopes(json: String?): Map<String, Map<String, String>> {
        val root = NateqJson.parseObject(json) ?: return emptyMap()
        val out = LinkedHashMap<String, Map<String, String>>()
        for ((rawTag, element) in root.entrySet()) {
            val tag = rawTag.trim()
            if (tag.isEmpty()) continue
            val layer = element.optObject() ?: continue
            val parsed = LinkedHashMap<String, String>()
            for ((rawKey, rawValue) in layer.entrySet()) {
                if (!rawValue.isJsonPrimitive) continue
                val key = rawKey.trim()
                val value = rawValue.asString.trim()
                if (key.isEmpty() || key.length > MAX_KEY_LENGTH) continue
                if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) {
                    continue
                }
                parsed[key] = value
            }
            if (parsed.isNotEmpty()) out[tag] = parsed
        }
        return out
    }

    private fun save(): Boolean {
        val sp = prefs ?: return false
        return try {
            sp.edit()
                .putString("dictionary", NateqJson.toJson(entries))
                .putString(KEY_SCOPES, NateqJson.toJson(langEntries))
                .apply()
            lastStamp = currentStamp()
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * آلة Aho-Corasick — تبحث عن كل مفاتيح القاموس في النص في تمريرة واحدة
 * بزمن O(n+m) بدلاً من دورة لكل مفتاح، مع الحفاظ على قواعد حدود الكلمة
 * (لا تُستبدل إلا الكلمات الكاملة وليس داخل كلمات أطول).
 */
private class AhoCorasick(entries: Map<String, String>) {

    private class Node {
        val children = HashMap<Char, Node>()
        var key: String? = null      // أطول مفتاح ينتهي عند هذه العقدة
        var value: String? = null    // نطقه
        var fail: Node? = null       // رابط الفشل
    }

    private data class Match(val start: Int, val end: Int, val value: String)

    private val root = Node()

    init {
        // إدخال المفاتيح الأطول أولاً حتى تُسجَّل المطابقة الأطول
        // في العقد المشتركة
        for ((rawKey, value) in
            entries.entries.sortedByDescending { it.key.length }) {
            val key = TashkeelStripStep.apply(rawKey)
            var node = root
            for (ch in key) {
                node = node.children.getOrPut(ch) { Node() }
            }
            node.key = key
            node.value = value
        }

        // بناء روابط الفشل عبر BFS
        val queue = ArrayDeque<Node>()
        for (child in root.children.values) {
            child.fail = root
            queue.add(child)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for ((ch, child) in current.children) {
                var fail = current.fail
                while (fail != null && !fail.children.containsKey(ch)) {
                    fail = fail.fail
                }
                child.fail = if (fail != null) fail.children[ch] else root
                if (child.value == null) {
                    child.key = child.fail?.key
                    child.value = child.fail?.value
                }
                queue.add(child)
            }
        }
    }

    /** علامات التشكيل العربية والتركيبية — تُعدّ داخل الكلمة ولا تكسر حدودها
     *  (كانت isLetterOrDigit=false فتنقلب فواصلَ كلماتٍ خاطئة فيُستبدل مفتاح
     *  في منتصف كلمة مشكولة — بند 3.5). */
    private fun isDiacritic(ch: Char): Boolean =
        ch in '\u064B'..'\u065F' ||
            ch in '\u0670'..'\u0674' ||
            ch == '\u06D6' || ch == '\u06D7' ||
            ch == '\u06DF' || ch == '\u06E0' ||
            ch == '\u06E8' || ch == '\u06EA' || ch == '\u06EB'

    /** هل المحرف جزءٌ من كلمة (حرف/رقم/علامة تشكيل)؟ */
    private fun isWordChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || isDiacritic(ch)

    /** رمزٌ غير حرفي (لا حرف/رقم/مسافة/تشكيل) مثل # $ % — لا يُحسب كلمةً
     *  فلا يمنع إلحاق المفتاح الرمزي بها (بند 3.5: «#عاجل» و«$50»). */
    private fun isSymbol(ch: Char): Boolean =
        !ch.isLetterOrDigit() && !ch.isWhitespace() && !isDiacritic(ch)

    /** يطبّق استبدالات القاموس على النص في تمريرة واحدة */
    fun apply(text: String): String {
        if (text.isEmpty()) return text
        val n = text.length

        // تمريرة الفحص: اجمع كل التطابقات الصالحة (ضمن حدود الكلمة)
        val matches = ArrayList<Match>()
        var node = root
        for (i in 0 until n) {
            val ch = text[i]
            while (node !== root && !node.children.containsKey(ch)) {
                node = node.fail ?: root
            }
            node = node.children[ch] ?: root

            val key = node.key ?: continue
            val value = node.value ?: continue
            val start = i - key.length + 1
            if (start < 0) continue
            // حدود الكلمة: لا حرف ولا رقم (بأي لغة) قبلها
            // ولا بعدها — الرقم جزءٌ
            // من الكلمة فيمنع إفساد "50م" قبل مرحلة الوحدات،
            // ويُعفى شرط "ما بعد"
            // للمفاتيح المنتهية بنقطة ليُسمح باختصارات مثل "د.أحمد"،
            // ورمزٌ طرفي في المفتاح (مثل # $ ٪) يفتح حدّه حتى تلتصق
            // الرموز المركّبة بكلماتٍ وأرقام ("#عاجل" و"$50" — بند 3.5).
            val endsWithDot = key.endsWith('.')
            val startsWithSymbol = key.isNotEmpty() &&
                isSymbol(key.first())
            val endsWithSymbol = key.isNotEmpty() && isSymbol(key.last())
            val leftOk = startsWithSymbol || start == 0 ||
                !isWordChar(text[start - 1])
            val rightOk = endsWithDot || endsWithSymbol ||
                i + 1 >= n || !isWordChar(text[i + 1])

            if (!leftOk || !rightOk) {
                // الأطول فشل بحدود الكلمة — ننزل عبر «روابط الفشل» بحثاً عن
                // مفتاحٍ أقصر ينتهي عند نفس الموضع ويمرّ بحدوده. المثال:
                // «x dye» (يخفق اليسار في «ayx dye» لأن قبلها حرف) بينما
                // لاحقته «dye» تبدأ بعد مسافة فتمرّ — كانت تُفقد والكلمة
                // تُترك بلا نطق رغم وجود مفتاحٍ صالح. حدُّ اليمين لا يتبدل
                // (نفسُ الموضع) لكن نفحصه اتساقاً.
                var fallback: Node? = node.fail
                while (fallback != null) {
                    val k = fallback.key
                    val v = fallback.value
                    if (k == null || v == null) {
                        fallback = fallback.fail
                        continue
                    }
                    val fs = i - k.length + 1
                    if (fs >= 0) {
                        val fDot = k.endsWith('.')
                        val fLeftSym = k.isNotEmpty() &&
                            isSymbol(k.first())
                        val fRightSym = k.isNotEmpty() &&
                            isSymbol(k.last())
                        val fLeftOk = fLeftSym || fs == 0 ||
                            !isWordChar(text[fs - 1])
                        val fRightOk = fDot || fRightSym ||
                            i + 1 >= n || !isWordChar(text[i + 1])
                        if (fLeftOk && fRightOk) {
                            matches.add(Match(fs, i + 1, v))
                            break
                        }
                    }
                    fallback = fallback.fail
                }
                continue
            }
            matches.add(Match(start, i + 1, value))
        }

        if (matches.isEmpty()) return text

        // تمريرة الاستبدال: غير متداخل، والأطول أولاً لكل موضع بداية
        matches.sortWith(
            compareBy<Match> { it.start }.thenByDescending { it.end }
        )
        val result = StringBuilder(n)
        var cursor = 0
        for (m in matches) {
            if (m.start < cursor) continue
            result.append(text, cursor, m.start)
            result.append(m.value)
            cursor = m.end
        }
        if (cursor < n) result.append(text, cursor, n)
        return result.toString()
    }
}