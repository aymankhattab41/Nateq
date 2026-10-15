package com.aymankhattab.nateq.core.audio.accessibility

import com.aymankhattab.nateq.core.audio.providers.EnginePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEngineSwitcherTest {

    private val engines = listOf(
        EnginePicker.InstalledEngine("one", "One"),
        EnginePicker.InstalledEngine("two", "Two"),
        EnginePicker.InstalledEngine("three", "Three")
    )

    @Test
    fun nextEngine_keepsTheSystemDiscoveryOrder() {
        val next = NextEngineSwitcher.nextEngine(engines, "one")

        assertEquals("two", next?.packageName)
    }

    @Test
    fun nextEngine_wrapsAfterTheLastSystemEngine() {
        val next = NextEngineSwitcher.nextEngine(engines, "three")

        assertEquals("one", next?.packageName)
    }

    @Test
    fun nextEngine_missingSavedEngine_startsWithFirstSystemEngine() {
        val next = NextEngineSwitcher.nextEngine(engines, "removed")

        assertEquals("one", next?.packageName)
    }

    @Test
    fun nextEngine_emptySystemList_doesNotInventAnEngine() {
        assertNull(NextEngineSwitcher.nextEngine(emptyList(), null))
    }
}
