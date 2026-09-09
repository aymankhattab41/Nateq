package com.aymankhattab.nateq.util

import com.aymankhattab.nateq.core.common.json.GsonTypes
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.lang.reflect.Type

/**
 * المظلة الموحّدة الوحيدة لكل تسلسل/تجزئة JSON في التطبيق (البند 3).
 *
 * كل نص JSON يمرّ عبر [gson] — مثيل Gson واحد مهيّأ مركزياً بدل مثيلات
 * متفرقة افتراضية في كل ملف، وتجمع هنا أيضاً:
 *  - بنّاءة الأنواع العامة المقاومة لقصّ R8 ([mapStringOf] عبر GsonTypes)
 *    بدل TypeToken المنعكس الهشّ.
 *  - دوال التجزئة «بلا رمي» ([fromJson]/[parseElement]/[parseObject]) التي
 *    تعيد null عند الفساد بدل إسقاط المتصل.
 *  - مساعدات شجرة تحاكي سلوك `optX` في org.json (المصدر السابق) حتى لا
 *    تتغيّر دلالات القراءة الدفاعية بعد هجرة النسخ الاحتياطي وواجهات الشبكة
 *    من org.json إلى Gson.
 */
object NateqJson {

    /** مثيل Gson المركزي الوحيد (خيارات افتراضية: لا تُصاد أسماء null). */
    val gson: Gson = Gson()

    // ===== تسلسل/تجزئة على كائنات =====

    /** تسلسل كائن إلى JSON مع [Type] صريح (عند الحاجة). */
    fun toJson(src: Any?, type: Type): String = gson.toJson(src, type)

    /** تسلسل كائن إلى JSON (لخريطة/قائمة/أي نموذج بسيط). */
    fun toJson(src: Any?): String = gson.toJson(src)

    /** تجزئة بلا رمي: نص فاسد/غائب/غير مطابق للشكل ← null بدل استثناء. */
    fun <T> fromJson(json: String?, type: Type): T? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, type)
        } catch (_: Exception) {
            null
        }
    }

    /** `Map<String, valueClass>` ككائن Type آمن مع R8 (لا TypeToken). */
    fun mapStringOf(valueClass: Class<*>): Type =
        GsonTypes.mapStringOf(valueClass)

    // ===== شجرة JSON (حلّت محل org.json) =====

    /** تجزئة نص إلى عُنصر شجرة بلا رمي: فاسد/غائب ← null. */
    fun parseElement(json: String?): JsonElement? {
        if (json.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(json)
        } catch (_: Exception) {
            null
        }
    }

    /** تجزئة نص إلى كائن جذر {…} بلا رمي — للنسخ الاحتياطي والاستجابات. */
    fun parseObject(json: String?): JsonObject? =
        parseElement(json)?.takeIf { it.isJsonObject }?.asJsonObject

    /** تجزئة خريطة نصية (كل القيم نصوص فقط). نص ليس
     *  كائناً/قيمة غير نصية ← null. */
    fun parseStringMap(json: String?): Map<String, String>? {
        val root = parseObject(json) ?: return null
        val out = LinkedHashMap<String, String>()
        for ((key, value) in root.entrySet()) {
            if (!value.isJsonPrimitive ||
                !value.asJsonPrimitive.isString) return null
            out[key] = value.asString
        }
        return out
    }
}

// ===== مساعدات قراءة دفاعية بنفس دلالات org.json ====
// (optString/optJSONObject/…)

/** العُنصر كائناً ثمّ optObject، وإلا null — محاكاة `optJSONObject`. */
fun JsonElement.optObject(): JsonObject? =
    takeIf { it.isJsonObject }?.asJsonObject

/** العُنصر مصفوفة ثمّ optArray، وإلا null — محاكاة `optJSONArray`. */
fun JsonElement.optArray(): JsonArray? =
    takeIf { it.isJsonArray }?.asJsonArray

/** نصّية آمنة بقيمة افتراضية (الأرقام تُحوَّل أسلافها) — محاكاة `optString`. */
fun JsonElement.optString(default: String = ""): String =
    takeIf { it.isJsonPrimitive }?.asString ?: default

/** طويلة آمنة (رقم أولاً ثم نص يُحلَّل) — محاكاة `optLong`. */
fun JsonElement.optLong(default: Long = 0L): Long {
    if (!isJsonPrimitive) return default
    val p = asJsonPrimitive
    return try {
        when {
            p.isNumber -> p.asLong
            p.isString -> p.asString.toLongOrNull() ?: default
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}

/** عشري آمن (رقم أولاً ثم نص يُحلَّل) — محاكاة `optDouble`. */
fun JsonElement.optDouble(default: Double = 0.0): Double {
    if (!isJsonPrimitive) return default
    val p = asJsonPrimitive
    return try {
        when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.toDoubleOrNull() ?: default
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}

/** صحيحة آمنة بمحاكاة `optInt` (طويلة مقلوبة فعلياً
 *  ثم قصّ — كما في org.json). */
fun JsonElement.optInt(default: Int = 0): Int =
    optLong(default.toLong()).toInt()

/** منطقية آمنة (منطقي، أو نص true/false، أو رقم غير
 *  صفري) — محاكاة `optBoolean`. */
fun JsonElement.optBoolean(default: Boolean = false): Boolean {
    if (!isJsonPrimitive) return default
    val p = asJsonPrimitive
    return when {
        p.isBoolean -> p.asBoolean
        p.isNumber -> p.asLong != 0L
        p.isString -> p.asString.toBooleanStrictOrNull() ?: default
        else -> default
    }
}

// ===== وصول أعضاء JsonObject آمن (محاكاة optX لمفاتيح الكائن) =====

/** العضو الموجود وغير-null، وإلا null (يُخفي JsonNull). */
fun JsonObject.optMember(name: String): JsonElement? =
    get(name)?.takeIf { !it.isJsonNull }

/** `obj.optString(name, "")` ثمّ عملياً `optObject().getString(name)`. */
fun JsonObject.optString(name: String, default: String = ""): String =
    optMember(name)?.optString(default) ?: default

fun JsonObject.optObject(name: String): JsonObject? =
    optMember(name)?.optObject()

fun JsonObject.optArray(name: String): JsonArray? = optMember(name)?.optArray()

fun JsonObject.optLong(name: String, default: Long = 0L): Long =
    optMember(name)?.optLong(default) ?: default

fun JsonObject.optInt(name: String, default: Int = 0): Int =
    optMember(name)?.optInt(default) ?: default

fun JsonObject.optDouble(name: String, default: Double = 0.0): Double =
    optMember(name)?.optDouble(default) ?: default

fun JsonObject.optBoolean(name: String, default: Boolean = false): Boolean =
    optMember(name)?.optBoolean(default) ?: default