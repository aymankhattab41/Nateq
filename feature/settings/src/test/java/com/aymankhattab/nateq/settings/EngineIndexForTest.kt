package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.audio.providers.EnginePicker.InstalledEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** مسار تعيين المحرك المحفوظ إلى موضعه في قائمة المحركات (بعد إزالة
 *  خيار «تلقائي» — لا إزاحة ±1 في المواضع). */
class EngineIndexForTest {

    private val engines = listOf(
        InstalledEngine("com.example.engine.one", "محرك واحد"),
        InstalledEngine("com.example.engine.two", "محرك اثنان"),
        InstalledEngine("com.example.engine.three", "محرك ثلاثة")
    )

    @Test
    fun `محفوظ ضمن القائمة - يرجع موضعه الصحيح بلا إزاحة`() {
        assertEquals(0, engineIndexFor(engines, "com.example.engine.one"))
        assertEquals(1, engineIndexFor(engines, "com.example.engine.two"))
        assertEquals(2, engineIndexFor(engines, "com.example.engine.three"))
    }

    @Test
    fun `لا محرك محفوظ - يرجع أول محرك`() {
        assertEquals(0, engineIndexFor(engines, null))
    }

    @Test
    fun `حزمة غير معروفة - يرجع أول محرك`() {
        assertEquals(0, engineIndexFor(engines, "com.example.engine.missing"))
    }

    @Test
    fun `قائمة فارغة من أي موضع - يرجع صفرا`() {
        assertEquals(0, engineIndexFor(emptyList(), null))
        assertEquals(0, engineIndexFor(emptyList(), "com.example.engine.one"))
    }
}