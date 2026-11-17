package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.common.VisibleEngines
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [بناء 10728] اختبار مجموعة المحركات النشطة: المحرك المخفي لا يعود إلى
 * الاكتشاف عبر تفضيلات اللغة/الفئة، والقائمة الفارغة لا تُفرِّغ القوائم.
 */
class ActiveEnginesSetTest {

    @Before
    fun resetEngineFilter() {
        VisibleEngines.resetForTest()
    }

    @After
    fun clearEngineFilter() {
        VisibleEngines.resetForTest()
    }

    @Test
    fun `hidden engine from preferences stays out of active set`() {
        VisibleEngines.setFilter(true, setOf("com.google.android.tts"))
        val result = activeEnginesOrNull(
            allowed = VisibleEngines.visibleSet(),
            candidates = listOf(
                "acapela_embedded",
                "com.google.android.tts",
                null,
                ""
            ),
            isVisible = VisibleEngines::isVisible
        )
        assertEquals(setOf("com.google.android.tts"), result)
    }

    @Test
    fun `empty selection yields null so no list is emptied`() {
        VisibleEngines.setFilter(true, emptySet())
        val result = activeEnginesOrNull(
            allowed = VisibleEngines.visibleSet(),
            candidates = listOf(null, ""),
            isVisible = VisibleEngines::isVisible
        )
        assertNull(result)
    }

    @Test
    fun `duplicate candidates are added once`() {
        VisibleEngines.setFilter(
            true,
            setOf("com.google.android.tts", "com.svox.pico")
        )
        val result = activeEnginesOrNull(
            allowed = VisibleEngines.visibleSet(),
            candidates = listOf(
                "com.google.android.tts",
                "com.google.android.tts",
                "com.svox.pico"
            ),
            isVisible = VisibleEngines::isVisible
        )
        assertEquals(
            setOf("com.google.android.tts", "com.svox.pico"),
            result
        )
    }
}
