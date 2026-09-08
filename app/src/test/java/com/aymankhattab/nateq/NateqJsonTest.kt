package com.aymankhattab.nateq

import com.aymankhattab.nateq.util.NateqJson
import com.aymankhattab.nateq.util.optArray
import com.aymankhattab.nateq.util.optBoolean
import com.aymankhattab.nateq.util.optDouble
import com.aymankhattab.nateq.util.optInt
import com.aymankhattab.nateq.util.optLong
import com.aymankhattab.nateq.util.optObject
import com.aymankhattab.nateq.util.optString
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مظلة JSON الموحّدة (البند 3): تسلسل/تجزئة بلا رمي، أنوع عامة
 * مقاومة لقصّ R8، شجرة آيلة بنفس دلالات org.json السابقة، والتمييز بين
 * JSON والصيغة السطرية القديمة لأسماء المتصلين.
 */
class NateqJsonTest {

    @Test
    fun mapStringOf_roundTrip_restoresStringMap() {
        val type = NateqJson.mapStringOf(String::class.java)
        val src = linkedMapOf("a" to "1", "b" to "اسم")
        val json = NateqJson.toJson(src, type)
        val restored = NateqJson.fromJson<Map<String, String>>(json, type)
        assertEquals(src, restored)
    }

    @Test
    fun fromJson_malformedAndBlank_returnNull() {
        val type = NateqJson.mapStringOf(String::class.java)
        assertNull(NateqJson.fromJson<Map<*, *>>(null, type))
        assertNull(NateqJson.fromJson<Map<*, *>>("", type))
        assertNull(NateqJson.fromJson<Map<*, *>>("not json at all", type))
        assertNull(NateqJson.fromJson<Map<*, *>>("[1,2,3]", type))
    }

    @Test
    fun parseObject_returnsNullForNonObjectOrFaultyJson() {
        assertNull(NateqJson.parseObject("[1,2]"))
        assertNull(NateqJson.parseObject(""))
        assertNull(NateqJson.parseObject("garbage"))
        assertNull(NateqJson.parseObject(null))
        val obj = NateqJson.parseObject("""{"a":1}""")
        assertEquals(1, obj?.optInt("a"))
    }

    @Test
    fun parseStringMap_acceptsOnlyStringObject() {
        assertEquals(mapOf("1" to "أحمد"), NateqJson.parseStringMap("""{"1":"أحمد"}"""))
        assertEquals(emptyMap<String, String>(), NateqJson.parseStringMap("{}"))
        assertNull(NateqJson.parseStringMap("""{"1":5}"""))
        assertNull(NateqJson.parseStringMap("+2012\tأنس"))
        assertNull(NateqJson.parseStringMap("garbage"))
        assertNull(NateqJson.parseStringMap("[]"))
    }

    @Test
    fun optHelpers_mirrorOrgJsonSemantics() {
        val root = NateqJson.parseObject(
            """{
            "tag": "v1.2",
            "number": 42,
            "double": 1.75,
            "flag": true,
            "flagText": "true",
            "zero": 0,
            "list": [1,2],
            "nested": {"k":"v"},
            "nullish": null
        }"""
        )!!
        assertEquals("v1.2", root.optString("tag"))
        assertEquals("", root.optString("missing"))
        assertEquals(42, root.optInt("number"))
        assertEquals(0, root.optInt("missing"))
        assertEquals(42L, root.optLong("number"))
        assertEquals(1.75, root.optDouble("double"), 0.0)
        assertTrue(root.optBoolean("flag"))
        assertTrue(root.optBoolean("flagText"))
        assertFalse(root.optBoolean("zero"))
        assertTrue(root.optArray("list") != null)
        assertEquals("v", root.optObject("nested")?.optString("k"))
        assertEquals("", root.optString("nullish"))
    }

    @Test
    fun toJson_treeKeepsInsertionOrder() {
        val obj = JsonObject()
        obj.addProperty("second", 2)
        obj.addProperty("first", 1)
        assertEquals("""{"second":2,"first":1}""", NateqJson.toJson(obj))
    }
}