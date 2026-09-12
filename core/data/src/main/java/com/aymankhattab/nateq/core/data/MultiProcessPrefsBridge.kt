package com.aymankhattab.nateq.core.data

import android.content.Context
import android.util.Xml
import java.io.File
import java.io.FileInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * جسر ملف الإعدادات المشترك بين العمليتين (main و :tts).
 *
 * SharedPreferences يحتفظ بكاش في الذاكرة لكل عملية، ولا توجد "إعادة فتح"
 * داخل نفس العملية، ولا يُعتمد MODE_MULTI_PROCESS المكروه (API 23+). لذا
 * تُعالَج حداثة بيانات العملية الأخرى بقراءة ملف القرص الفعلي صراحةً عبر
 * هذا الجسر — منطق الهضم (الاختيار/الترحيل) يبقى في [SettingsRepository].
 *
 * البناء بقاعدة بيانات التطبيق (File) يجعل الجسر قابلاً للاختبار دون
 * Robolectric بالنسبة لقراءة/هضم الملف، مع تبسيط مخزن الإعدادات العملاق.
 */
class MultiProcessPrefsBridge(private val appDataDir: File) {

    /** بناء من سياق التطبيق (dataDir). */
    constructor(context: Context) :
        this(File(context.applicationInfo.dataDir))

    /** مسار ملف تفضيلات مسجَّل بالاسم (تحت مجلد shared_prefs). */
    fun fileFor(name: String): File =
        File(appDataDir, "shared_prefs/$name.xml")

    /** آخر زمن تعديل لملفٍ مسجَّل — حارس كشف الكتابة من العملية الأخرى. */
    fun lastModified(name: String): Long =
        runCatching { fileFor(name).lastModified() }.getOrDefault(0L)

    /** هل تغيّر توقيت الملف عن زمنٍ محفوظ سابقاً؟ */
    fun isChanged(name: String, previous: Long): Boolean =
        lastModified(name) != previous

    /**
     * يقرأ ملف تفضيلات XML بتنسيق SharedPreferences ويعيد خريطته القيمية.
     * صيغة الملف موثقة داخل AOSP (SharedPreferencesImpl.fromXml) وثابتة
     * لسنوات — المخزن الوحيد لهذا المشروع: map من int/boolean/float/string
     * و string-set/string.
     */
    fun parse(name: String): Map<String, Any?> {
        val parser = Xml.newPullParser()
        parser.setInput(FileInputStream(fileFor(name)), "utf-8")
        var type = parser.eventType
        val map = LinkedHashMap<String, Any?>()
        // حالة عنصر <set> النشط (مجموعة نصوص): الاسم وجمع القيم حتى وسم
        // الإغلاق المقابل.
        var setKey: String? = null
        var setValues: java.util.HashSet<String>? = null
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                val tag = parser.name
                val key = parser.getAttributeValue(null, "name")
                when (tag) {
                    // الجذر فقط: بلا قيم مباشرة.
                    "map" -> {}
                    // عنصر مجموعة نصوص: قيمُه وسم <string> متتالٍ بلا name.
                    "set" -> {
                        setKey = key
                        setValues = java.util.HashSet()
                    }
                    // وسم نصي: إن كنا داخل مجموعة يلتحق بها، وإلا قيمة مستقلة.
                    "string" -> if (setValues != null) {
                        setValues.add(parser.nextText())
                    } else if (key != null) {
                        map[key] = parser.nextText()
                    }
                    else -> if (key != null) {
                        val raw = parser.getAttributeValue(null, "value")
                        map[key] = parseScalar(tag, raw)
                    }
                }
            } else if (type == XmlPullParser.END_TAG &&
                parser.name == "set" && setKey != null && setValues != null
            ) {
                map[setKey] = setValues
                setKey = null
                setValues = null
            }
            type = parser.next()
        }
        return map
    }

    /** يحوّل قيمة نصية لوسم عددي/منطقي؛ أي فشل يُترك نصاً خاماً. */
    private fun parseScalar(tag: String, value: String?): Any? = try {
        when (tag) {
            "int" -> value?.toInt()
            "long" -> value?.toLong()
            "float" -> value?.toFloat()
            "boolean" -> value?.toBoolean()
            "string" -> value
            else -> value
        }
    } catch (_: Throwable) {
        value
    }
}