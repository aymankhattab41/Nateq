package com.aymankhattab.nateq.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aymankhattab.nateq.util.NateqJson
import java.lang.reflect.Type

/**
 * قاموس النطق الشخصي - يسمح للمستخدم بتعريف نطق مخصص للكلمات/الاختصارات
 * أمثلة: "د." → "دكتور"، "ص" → "صفحة"، "HTTP" → "إتش تي تي بي"
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
    }

    // لا يجوز أبداً أن يرمي إنشاء التخزين المشفّر: خدمة :tts تُنشئ هذا الكائن
    // في onCreate()، وأي استثناء هنا (Keystore معطوب، Tink مقطوع، وضع محاكي…)
    // يُسقط الخدمة فيرفض نظام سامسونج المحرك برسالة "يستمر التطبيق في التوقف".
    // عند الفشل يعمل القاموس بالذاكرة فقط (بلا حفظ دائم) ولا ينهار النطق.
    private val prefs: android.content.SharedPreferences? = try {
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

    // مخبأ آلة Aho-Corasick مقيّد بهوية خريطة اللقطة: يُبنى من لقطة كاملة،
    // ويُعاد بناؤه عند تبديل المرجع (لا تُبنى آلة من قاموسٍ في منتصف القراءة).
    private data class MachineCache(
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
        entries = loadFromPrefs()
        removeLegacyDefaultsOnce()
        if (prefs != null) lastStamp = currentStamp()
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
        swapEntries(loadFromPrefs())
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
        swapEntries(loadFromPrefs())
        lastStamp = stamp
        return true
    }

    /** استبدال مرجع الخريطة ذرياً وإبطال مخبأ الآلة المقيّد به. */
    private fun swapEntries(updated: Map<String, String>) {
        entries = updated
        cache = null
    }

    private fun currentStamp(): Long =
        prefsFile?.let { if (it.exists()) it.lastModified() else 0L } ?: 0L

    /** تطبيق القاموس على نص */
    fun apply(text: String): String {
        // اكتشاف تعديلات عملية الواجهة على القرص قبل كل تطبيق
        reloadIfChanged()
        var held = cache
        if (held == null || held.snapshot !== entries) {
            held = synchronized(this) {
                val current = entries
                val cached = cache
                if (cached != null && cached.snapshot === current) cached
                else MachineCache(current, AhoCorasick(current)).also {
                    cache = it
                }
            }
        }
        return held.machine.apply(text)
    }

    /** إضافة إدخال جديد */
    fun addEntry(abbreviation: String, pronunciation: String): Boolean {
        if (abbreviation.isBlank() || pronunciation.isBlank()) return false
        val key = abbreviation.trim()
        val value = pronunciation.trim()
        if (key.length > MAX_KEY_LENGTH ||
            value.length > MAX_VALUE_LENGTH
        ) return false
        val updated = LinkedHashMap(entries)
        updated[key] = value
        swapEntries(updated)
        return save()
    }

    /** حذف إدخال */
    fun removeEntry(abbreviation: String): Boolean {
        val updated = LinkedHashMap(entries)
        updated.remove(abbreviation.trim())
        swapEntries(updated)
        return save()
    }

    /** الحصول على جميع الإدخالات */
    fun getAllEntries(): Map<String, String> = entries.toMap()

    /** استيراد قاموس من JSON — يتخطى الصفوف غير الصالحة بدل إفشال الاستيراد
     *  كاملاً، ويدعم الدمج مع الإدخالات الحالية أو الاستبدال الكامل.
     *  @param merge true: يُدمج مع الحالي (تتغلب الإدخالات الجديدة على المفاتيح
     *               المكررة مع بقاء بقية الحالي)؛ false: يحل محله بالكامل.
     *  @return true إن طُبِّق صف صالح واحد على الأقل (الذاكرة تتحدّث دائماً؛
     *          لا يُعدّ فشل التخزين المشفّر نجاحاً). */
    fun importFromJson(json: String, merge: Boolean = false): Boolean {
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
        val updated = if (merge) LinkedHashMap(entries)
            else LinkedHashMap<String, String>()
        var imported = 0
        for ((key, value) in valid) {
            if (updated.size >= MAX_IMPORT_ENTRIES) break
            updated[key] = value
            imported++
        }
        if (imported == 0) return false

        swapEntries(updated)
        val sp = prefs ?: return true
        return save()
    }

    /** تصدير القاموس إلى JSON */
    fun exportToJson(): String = NateqJson.toJson(entries)

    private fun loadFromPrefs(): Map<String, String> {
        val sp = prefs ?: return emptyMap()
        val json = sp.getString("dictionary", "{}")
        // تجزئة بلا رمي: فاسد ← null ← خريطة فارغة
        // (كما كان تنظيف catch سابقاً).
        val raw = NateqJson.fromJson<Map<*, *>>(json, typeToken)
            as? Map<*, *> ?: emptyMap<Any, Any>()
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

    private fun save(): Boolean {
        val sp = prefs ?: return false
        return try {
            val json = NateqJson.toJson(entries)
            sp.edit().putString("dictionary", json).apply()
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
        for ((key, value) in
            entries.entries.sortedByDescending { it.key.length }) {
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
            // للمفاتيح المنتهية بنقطة ليُسمح باختصارات مثل "د.أحمد".
            val endsWithDot = key.endsWith('.')
            if (start > 0 && text[start - 1].isLetterOrDigit()) continue
            if (!endsWithDot && i + 1 < n &&
                text[i + 1].isLetterOrDigit()
            ) continue
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