package com.aymankhattab.nateq.engine

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * قاموس النطق الشخصي - يسمح للمستخدم بتعريف نطق مخصص للكلمات/الاختصارات
 * أمثلة: "د." → "دكتور"، "ص" → "صفحة"، "HTTP" → "إتش تي تي بي"
 */
class PronunciationDictionary(private val context: Context) {

    companion object {
        // حدود دفاعية ضد ملفات JSON خبيثة/ضخمة واردة من SAF أو مصادر أخرى
        private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024   // 2 MB
        private const val MAX_IMPORT_ENTRIES = 5000
        private const val MAX_KEY_LENGTH = 200
        private const val MAX_VALUE_LENGTH = 200
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

    private val gson = Gson()
    private val entries = ConcurrentHashMap<String, String>()
    // آلة Aho-Corasick يُعاد بناؤها عند تغيّر القاموس (للبحث في تمريرة واحدة)
    @Volatile
    private var ahoCorasick: AhoCorasick? = null
    // نوع بالمفتاح النصي القيمة النصية بلا TypeToken (مقاوم لقصّ R8 للتوقيعات)
    private val typeToken: Type = GsonTypes.mapStringOf(String::class.java)

    init {
        load()
        addDefaultEntries()
    }

    /** إضافة إدخالات افتراضية عربية شائعة */
    private fun addDefaultEntries() {
        val defaults = mapOf(
            // اختصارات طبية — مع النقطة فقط لمنع الاستبدال غير المقصود في النصوص العادية
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

            // عملات
            "ريال" to "ريال سعودي",
            "درهم" to "درهم إماراتي",
            "دينار" to "دينار كويتي",
            "جنيه" to "جنيه مصري",

            // أيام الأسبوع
            "أح" to "الأحد",
            "اث" to "الاثنين",
            "ثث" to "الثلاثاء",
            "أرب" to "الأربعاء",
            "خم" to "الخميس",
            "جم" to "الجمعة",
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

        for ((key, value) in defaults) {
            if (!entries.containsKey(key)) {
                entries[key] = value
            }
        }
        save()
    }

    /** تطبيق القاموس على نص */
    fun apply(text: String): String {
        val machine = ahoCorasick ?: synchronized(this) {
            ahoCorasick ?: AhoCorasick(entries.toMap()).also { ahoCorasick = it }
        }
        return machine.apply(text)
    }

    /** إضافة إدخال جديد */
    fun addEntry(abbreviation: String, pronunciation: String): Boolean {
        if (abbreviation.isBlank() || pronunciation.isBlank()) return false
        val key = abbreviation.trim()
        val value = pronunciation.trim()
        if (key.length > MAX_KEY_LENGTH || value.length > MAX_VALUE_LENGTH) return false
        entries[key] = value
        ahoCorasick = null // إبطال الآلة عند تغيير القاموس
        return save()
    }

    /** حذف إدخال */
    fun removeEntry(abbreviation: String): Boolean {
        entries.remove(abbreviation.trim())
        ahoCorasick = null
        return save()
    }

    /** الحصول على جميع الإدخالات */
    fun getAllEntries(): Map<String, String> = entries.toMap()

    /** استيراد قاموس من JSON */
    fun importFromJson(json: String): Boolean {
        if (json.length > MAX_IMPORT_BYTES) return false
        return try {
            val map = gson.fromJson(json, typeToken) as Map<String, String>
            if (map.size > MAX_IMPORT_ENTRIES) return false
            if (map.keys.any { it.isBlank() || it.length > MAX_KEY_LENGTH }) return false
            if (map.values.any { it.length > MAX_VALUE_LENGTH }) return false
            entries.clear()
            entries.putAll(map)
            ahoCorasick = null
            save()
        } catch (e: Exception) {
            false
        }
    }

    /** تصدير القاموس إلى JSON */
    fun exportToJson(): String = gson.toJson(entries)

    private fun load() {
        val sp = prefs ?: return
        val json = sp.getString("dictionary", "{}")
        try {
            val map = gson.fromJson(json, typeToken) as Map<String, String>
            entries.putAll(map)
        } catch (e: Exception) {
            entries.clear()
        }
    }

    private fun save(): Boolean {
        val sp = prefs ?: return false
        return try {
            val json = gson.toJson(entries)
            sp.edit().putString("dictionary", json).apply()
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
        // إدخال المفاتيح الأطول أولاً حتى تُسجَّل المطابقة الأطول في العقد المشتركة
        for ((key, value) in entries.entries.sortedByDescending { it.key.length }) {
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
            // حدود الكلمة: لا حرف (بأي لغة) قبلها ولا بعدها
            if (start > 0 && text[start - 1].isLetter()) continue
            if (i + 1 < n && text[i + 1].isLetter()) continue
            matches.add(Match(start, i + 1, value))
        }

        if (matches.isEmpty()) return text

        // تمريرة الاستبدال: غير متداخل، والأطول أولاً لكل موضع بداية
        matches.sortWith(compareBy<Match> { it.start }.thenByDescending { it.end })
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