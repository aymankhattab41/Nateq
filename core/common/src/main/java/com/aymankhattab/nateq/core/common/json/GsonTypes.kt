package com.aymankhattab.nateq.core.common.json

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * أدوات لبناء أنواع عامة (Generic) لـ Gson بطريقة آمنة لا تتأثر بـ R8/ProGuard.
 *
 * استبدال `TypeToken<Map<String, X>>() {}.type` — الذي يُفشل في نسخات release
 * عند قصّ R8 للتوقيعات العامة (IllegalStateException: TypeToken must be created
 * with a type argument). ببناء [ParameterizedType] صراحةً لا نحتاج الاحتفاظ بـ
 * Signature on anonymous classes.
 */
object GsonTypes {

    /** `Map<String, valueClass>` ككائن [Type]. */
    fun mapStringOf(valueClass: Class<*>): Type = ParameterizedTypes.of(
        Map::class.java,
        arrayOf(java.lang.String::class.java, valueClass)
    )
}

/** بنّاء [ParameterizedType] بسيط. */
private class ParameterizedTypes(
    private val rawType: Type,
    private val typeArguments: Array<Type>
) : ParameterizedType {

    override fun getRawType(): Type = rawType

    override fun getOwnerType(): Type? = null

    override fun getActualTypeArguments(): Array<Type> = typeArguments

    override fun equals(other: Any?): Boolean {
        if (other !is ParameterizedType) return false
        return rawType == other.rawType &&
            typeArguments.contentEquals(other.actualTypeArguments) &&
            ownerType == other.ownerType
    }

    override fun hashCode(): Int {
        var h = rawType.hashCode()
        for (t in typeArguments) h = h * 31 + t.hashCode()
        return h
    }

    override fun toString(): String {
        val args = typeArguments.joinToString(", ") { if (it is Class<*>) it.name else it.toString() }
        return if (typeArguments.isNotEmpty()) "$rawType<$args>" else rawType.toString()
    }

    companion object {
        fun of(rawType: Type, typeArguments: Array<Type>): Type =
            ParameterizedTypes(rawType, typeArguments)
    }
}