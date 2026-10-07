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
        // حدود دفاعية ضد ملفات JSON ضخمة/خبيثة واردة من SAF. الملف
        // يُقرأ كاملاً في الذاكرة قبل التجزئة، فالسقف ضروري: فوقه
        // يَOOM التطبيق عند قراءة الملف قبل التحقق. 8 ميغابايت أوسعُ
        // من احتياج القاموس الحقيقي بأضعاف (آلاف المدخلات = أقلّ من
        // ميغابايت) مع بقائه آمناً على الأجهزة الضعيفة.
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        // سقفٌ تقنيّ لا تقييد عملي: يحمي من قاموسٍ يُغرق بناءَ آلة
        // Aho-Corasick في الذاكرة. **عامٌّ** ليبقى المرجعُ واحداً
        // في القاموس وفي اختباره، فلا يتناقِثُ الحدّان (بند 3.7:
        // حدٌّ في موضعين = قيمةٌ منفلتة في أحدهما).
        const val MAX_IMPORT_ENTRIES = 200_000
        // طول المفتاح والقيمة: بلا سقف عملي — تُقصّ فقط حمايةً من
        // مدخلٍ شارد يبني عقداً عملاقة بلا فائدة. 8 آلاف حرف أوسعُ
        // من أي كلمة أو اختصار حقيقي.
        const val MAX_KEY_LENGTH = 8_000
        const val MAX_VALUE_LENGTH = 8_000
        // الخنق بين فحصَي القرص في reloadIfChanged: استدعاء lastModified()
        // على ملف التفضيلات المُنفَّذ لكل فقرة صوتية — الخنق يقلّص الـ I/O.
        private const val DISK_CHECK_THROTTLE_NANOS =
            1_500_000_000L // 1.5 ثانية
        // مفتاح تتبّع هجرة حذف الإدخالات الافتراضية القديمة (تُنفَّذ مرة
        // واحدة)
        private const val KEY_DEFAULTS_MIGRATED = "defaults_migrated_to_empty"
        // مفتاح تخزين الطبقات اللغوية: كائن JSON واحد {وسم: {مفتاح: قيمة}}
        // — لا نعدّ مفاتيح التفضيلات كلها ([SharedPreferences.all] غير مدعوم
        // في EncryptedSharedPreferences على بعض البيئات) فلا مخاطرة ولا أطلال.
        private const val KEY_SCOPES = "dictionary_scopes"
        // اسمُ التفضيلات نفسه للتشفيرِ وللتخزين العادي — فالملفُ واحدٌ
        // فيقرؤه أيّهما: path واحد ولا ازدواج.
        const val PREFS_NAME = "nateq_pronunciation_dict"
        private const val LOG_TAG = "NateqDict"

        /**
         * **المثّل الواحد على مستوى العملية — جذرُ عطل «القاموس لا يعمل».**
         *
         * كان لكلِّ مستهلكٍ نسختُه الخاصة: الواجهةُ تحقن Hilt نسخةً، و
         * [AnnouncementSpeaker] (نطقُ الإعلانات والمعاينات) يبني نسخةً أخرى
         * بـ`TextProcessor(appContext, settings)` بلا قاموسٍ محقون. ولا يجمع
         * بينهما إلا **قراءةُ الملف من القرص** عبر `reloadIfChanged` — وهي
         * تنهار بصمتٍ إن: تعذّر فتحُ التخزين المشفّر (Keystore معطوبٌ بعد
         * استرجاع نسخة احتياطية، أو Tink مقطوع، أو بعضُ الأجهزة) فيصير
         * القاموسُ **بالذاكرة فقط** لكل نسخة، فلا يرى النطقُ ما كتبته
         * الواجهةُ أبداً؛ أو لم يتغيّر `lastModified()` على بعض الأنظمة؛ أو
         * وقع الطلبُ داخل نافذةِ الخنق (1.5s). **والعَرَضُ واحدٌ في الحالات
         * الثلاث: قائمةُ الإعدادات تُظهر المدخلات والنطقُ لا يطبّقها.**
         *
         * فالمثيلُ الواحد يُلغي المزامنةَ بين العملية الواحدة تماماً: لا
         * استطلاعَ للقرص ولا نافذةَ انتظار. ويبقى الاستطلاعُ لعمليّة `:tts`
         * وحدها لأنها genuinely منفصلة.
         *
         * ولا غموض: الاختباراتُ تستدعي [PronunciationDictionary] مباشرةً
         * فتحصل كلٌّ على نسختها المعزولة (شرطُ عزلة الاختبار).
         */
        @Volatile
        private var sharedInstance: PronunciationDictionary? = null

        /** مثيلُ العملية الواحدة — انظر [sharedInstance] للسبب. */
        fun shared(context: Context): PronunciationDictionary =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: PronunciationDictionary(
                    context.applicationContext
                ).also { sharedInstance = it }
            }

        /**
         * يُصفّر المثّلَ المشترك — **للاختبارات وحدها** (عزلُ كل حالة JVM).
         * في الإنتاج لا يفرّغه أبداً: إفراغُه ينسخ القاموسَ من حيٍّ إلى ميت
         * ويُفقد النطقَ ما أضيفَ في-flight، فلا داعيَ لمساره في الكود الحقيقي.
         */
        fun resetSharedForTests() {
            synchronized(this) { sharedInstance = null }
        }
    }

    /** نمطُ التخزين الساري — يُعرَض في شاشة التشخيص ليعرف المستخدمُ
     *  أيَّ حالةٍ يعمل بها القاموس بدل أن يفشل صامتاً. */
    enum class StorageMode { ENCRYPTED, PLAIN, MEMORY_ONLY }

    // **لا يجوزُ أبداً أن يرمي إنشاءُ التخزينِ خدمةَ :tts** فهي تنشئ هذا
    // الكائن في onCreate()، وأي استثناء هنا (Keystore معطوب، Tink مقطوع،
    // وضع محاكي…) يُسقطها فيرفض نظامُ سامسونج المحركَ برسالة «يستمر التطبيق
    // في التوقف». فالاختيارُ تراتبٌ لا خيارات: مشفّرٌ أولاً، ثم عاديٌّ إن
    // تعذّر، ثم الذاكرةُ وحدها.
    //
    // **ولماذا عاديٌّ لا ذاكرة؟** القاموسُ تفضيلاتُ نطقٍ لا سرّ: قيمةُ
    // «ج = جنيه» ليست بياناتٍ يستحقّها التشفير. فالتشفيرُ هنا لا يضيفُ
    // أماناً يُذكر، وإنّما يجعل الميزةَ **تفشل صامتاً** إن تعذّر فتحُ
    // Keystore — وهو ما كان يُبطلُ القاموسَ كلَّه على جهاز المستخدم. أمّا
    // الذاكرةُ وحدها فهي الخيارُ الأخير: عملٌ دون حفظٍ دائم، وهو
    // أهونُ من ضياعٍ صامت.
    private val storageRef: StorageMode
    private val prefs: android.content.SharedPreferences?

    init {
        val encrypted = runCatching { openEncryptedPrefs() }.getOrNull()
        val plain = if (encrypted == null) {
            runCatching {
                context.getSharedPreferences(
                    PREFS_NAME, android.content.Context.MODE_PRIVATE
                )
            }.getOrNull()
        } else {
            null
        }
        when {
            encrypted != null -> {
                storageRef = StorageMode.ENCRYPTED
                prefs = encrypted
            }
            plain != null -> {
                storageRef = StorageMode.PLAIN
                prefs = plain
                android.util.Log.w(
                    LOG_TAG,
                    "تعذّر فتح التخزين المشفّر لقاموس النطق؛ التحويل إلى" +
                        " تخزين عادي كي لا يفشل القاموس صامتاً"
                )
            }
            else -> {
                storageRef = StorageMode.MEMORY_ONLY
                prefs = null
                android.util.Log.w(
                    LOG_TAG,
                    "تعذّر فتح أيّ تخزين لقاموس النطق؛ سيعمل بالذاكرة" +
                        " وحدها وستضيع المدخلاتُ بإعادة التشغيل"
                )
            }
}
    }

    /** نمطُ التخزين الساري — بعد [init] لا قبله، وإلا قُرئ غيرَ مهيّأ. */
    val storageMode: StorageMode get() = storageRef

    /** يفتح مثيلاً جديداً من تخزين التفضيلات المشفّر. كل مكالمة تنشئ كائناً
     *  جديداً بلا كاش داخلي سابق — تُستخدم للقراءة في [loadFromPrefs] لأن
     *  الكائن العضو [prefs] يخزّن نواتج فك التشفير في ذاكرته فلا يرى
     * تعديل عملية الواجهة الأجنبية حتى لو تغيّر طابع الملف. */
    private fun openEncryptedPrefs(): android.content.SharedPreferences? {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * نسخةٌ **طازجة** من التخزين الساري: الكائن العضو [prefs] يخزّن
     * نواتج فك التشفير في
     * ذاكرته فلا يرى تعديلَ كائنٍ آخر، فتن القراءةُ تُجرى بنسخةٍ جديدة
     * كلّما ظهر تغيّرٌ على القرص. تأخذ النمطَ الساري (مشفّر/عادي) فلا
     * تقرأ من تخزينٍ غير الذي كُتب فيه.
     */
    private fun openFreshPrefs(): android.content.SharedPreferences? =
        when (storageRef) {
            StorageMode.ENCRYPTED -> runCatching { openEncryptedPrefs() }
                .getOrNull()
            StorageMode.PLAIN -> runCatching {
                context.getSharedPreferences(
                    PREFS_NAME, android.content.Context.MODE_PRIVATE
                )
            }.getOrNull()
            StorageMode.MEMORY_ONLY -> null
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
        val (global, langs) = loadFromPrefs(prefs)
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

    /**
     * الإدخالات الافتراضية القديمة — فارغة ليبقى القاموس تحت تحكم المستخدم
     * كاملاً بلا تحويلات افتراضية مفروضة.
     */
    private fun legacyDefaultEntries(): Map<String, String> = emptyMap()

    /** هجرة لمرة واحدة: حذف الإدخالات الافتراضية القديمة
     *  المخزّنة عند المستخدم.
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

    /** مسح كل إدخالات القاموس (عامة ولغات) — يُستخدم لإعادة الضبط الكامل. */
    fun clearAll(): Boolean {
        if (prefs == null) return false
        synchronized(this) {
            entries = emptyMap()
            langEntries = emptyMap()
            cache = null
            version++
        }
        val ok = try {
            prefs.edit().clear().commit()
        } catch (e: Exception) {
            false
        }
        lastDiskCheckNanos = 0L
        return ok
    }

    /** تصفير مؤقت الخنق لإجبار [reloadIfChanged] على فحص القرص فوراً. */
    fun invalidate() {
        lastDiskCheckNanos = 0L
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
        val strippedKey = TashkeelStripStep.apply(key).trim()
        if (strippedKey.isEmpty()) return false
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
        lastDiskCheckNanos = 0L
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
        lastDiskCheckNanos = 0L
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
        lastDiskCheckNanos = 0L
        save()
    }

    /** الحصول على إدخالات نطاقٍ — العام أو طبقةِ [languageTag] وحدها. */
    fun getAllEntries(languageTag: String? = null): Map<String, String> {
        val lang = normalizeLangTag(languageTag)
        return if (lang == null) entries.toMap()
        else (langEntries[lang] ?: emptyMap()).toMap()
    }

/**
     * استيراد قاموس من JSON — **بلا تسامح مع أي خطأ** (قرار المدير):
     * المدخلُ غير الصالح (قيمةٌ ليست نصّاً، أو مفتاحٌ/قيمةٌ فارغة، أو
     * أطولُ من الحدّ) **يُفشل الملفَ كاملاً** ولا يُستورد منه شيء.
     *
     * **لماذا الفشلُ لا التخطّي؟** كان الصفُّ الفاسد يُتخطّى صامتاً
     * فيخرج المستخدمُ بقاموسٍ ناقصٍ لا يعرف ما الذي ضاع منه، فيظنّ
     * أن كل شيءٍ دخل — وصمتٌ يُقنعه بما لم يكن. الآن إمّا الملفُ كلُّه
     * أو لا شيء.
     *
     * @param merge true: يُدمج مع الحالي (تتغلب الجديدات على المفاتيح
     *               المكررة مع بقية الحالي)؛ false: يحل محله بالكامل.
     * @return true إن طُبِّق الملفُ كاملاً وحُفظ؛ false عند أي خطأ.
     */
    fun importFromJson(
        json: String,
        merge: Boolean = false,
        languageTag: String? = null
    ): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        // **تحليلٌ إلى شجرة JSON لا إلى Map<String,String>**: Gson يُحوّل
        // الرقمَ 5 إلى نصٍّ «5» فيمرّ مدخلٌ غيرُ نصّيٍّ بصفته نصّاً — وكان
        // الاستيرادُ يقبل `{"أ": 5}` و`{"أ": true}`. الشجرةُ تُبقي نوعَ
        // القيمة فيُتحقَّق منه صراحةً.
        val obj = NateqJson.parseObject(json) ?: return false
        if (obj.size() == 0) return false

        // تحقّقٌ صارم: أي مدخلٍ فاسد يُفشل الملفَ كلَّه بلا استثناء،
        // فلا استيراد جزئي ولا مدخلات مفقودة بلا إشعار.
        val valid = LinkedHashMap<String, String>()
        for ((rawKey, element) in obj.entrySet()) {
            // يجب أن يكون نصّاً خاماً: لا رقمٌ ولا منطقيٌ ولا null.
            if (!element.isJsonPrimitive ||
                !element.asJsonPrimitive.isString
            ) {
                return false
            }
            val key = rawKey.trim()
            val value = element.asString.trim()
            if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return false
            if (value.isEmpty() || value.length > MAX_VALUE_LENGTH) {
                return false
            }
            valid[key] = value
        }
        if (valid.isEmpty()) return false

        // السقف التراكمي: **تجاوزُه يُفشل الاستيراد** لا يقتطعُ صامتاً.
        val lang = normalizeLangTag(languageTag)
        val imported = synchronized(this) {
            val base = if (lang == null) entries
            else (langEntries[lang] ?: emptyMap())
            val updated = if (merge) LinkedHashMap(base)
            else LinkedHashMap<String, String>()
            if (updated.size + valid.size > MAX_IMPORT_ENTRIES) {
                return@synchronized -1
            }
            var count = 0
            for ((key, value) in valid) {
                updated[key] = value
                count++
            }
            if (count > 0) {
                if (lang == null) swapEntries(updated)
                else swapLangEntries(lang, updated)
            }
            count
        }
        if (imported <= 0) return false

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

    private fun loadFromPrefs(
        sourceSp: android.content.SharedPreferences? = null
    ): Pair<Map<String, String>, Map<String, Map<String, String>>> {
        // تُقرأ القيم من «مثيل طازج» (انظر [openFreshPrefs]) لا من الكائن
        // العضو
        // المخبئ — تصطاد تعديلات عملية الواجهة عبر الطابع. على فشل الفتح أو
        // الفك نقف عند آخر ما رصدناه بدل مسح القاموس الحي (تفضيلُ مستخدمٍ
        // حقيقي لا يجوز أن يُمحى بسبب خللٍ عابر). القراءة بمفتاحين معلومين
        // فقط — بلا عدّ [SharedPreferences.all] غير المدعوم في التخزين
        // المشفّر على بعض البيئات فيُسقط الحفظ كاملاً.
        val sp = sourceSp ?: openFreshPrefs() ?: return entries to langEntries
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
            // **commit() لا apply()** — والسببُ هو نفسُ العطب لا غيره: مع
            // `apply()` يبقى الكتابةُ في طريقها إلى القرص بعد عودة
            // `addEntry`، فيقرأ القارئُ (عمليةُ `:tts` المستقلة، أو نسخةٌ
            // أخرى تستجوب الطابع) طابعَ ما **قبل** أن يكتمل الملف فيقارن
            // غيرَ المتغيّر فلا يستجدّ شيئاً… ثم يُصفّر [lastStamp] على
            // الطابع القديم فيضيع التحديثُ نافذةً كاملة. وcommit() يُنهي
            // الكتابةَ قبل العودة فيصحّ الطابعُ ويكون ما على القرش هو ما
            // في الذاكرة. والكتابةُ صغيرة (JSON واحد) وتنتج عن فعلِ
            // المستخدم، فحجبُ الخيطِ لحظاتٍ ثمنُ صحّة البيانات لا ترفٌّ.
            val ok = sp.edit()
                .putString("dictionary", NateqJson.toJson(entries))
                .putString(KEY_SCOPES, NateqJson.toJson(langEntries))
                .commit()
            lastStamp = currentStamp()
            lastDiskCheckNanos = 0L
            ok
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
            val key = TashkeelStripStep.apply(rawKey).trim()
            if (key.isEmpty()) continue
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

/**
 * القاموس مفتوحٌ بلا حدود: يُطبَّق مفتاحُه **أينما ورد** في النص،
 * يميناً ويساراً، بلا شرط حدود الكلمة.
 *
 * **قرار المدير:** كان الحرفُ حدّاً (لا يُبدَّل داخل كلمةٍ أخرى)، فكان
 * القاموسُ صامتاً عن كل ما يُكتب ملتصقاً — «٥٠ج»، «٣٠٠جم»، «كم2» —
 * مع أن المستخدم أدخله بيده، وهو ما اشتكى منه. فلم يبقَ حدّ: لا
 * أيسرَ ولا أيمن، والرقمُ والعربيةُ والإنجليزيةُ سواء.
 *
 * **الأثرُ المقصود:** مفتاحٌ قصير يُطبَّق داخل كلماتٍ أطول فيشوّهها
 * («م»←«متر» يجعل «مرحبا»←«مترحبا») — عَمدُ المستخدم بمفتاحه القصير،
 * والقرارُ له. وما يخفّفه: **الأطولُ يُطبَّق أولاً** (ترتيبُ المطابقات
 * تنازلياً عند بدايةٍ واحدة)، فمفتاحُ «كجم» يحجبُ «كغ» داخله لا العكس.
 */
fun apply(text: String): String {
    if (text.isEmpty()) return text
    val n = text.length

    // تمريرة الفحص: كل تطابقٍ وارد، بلا شرط حدود
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
