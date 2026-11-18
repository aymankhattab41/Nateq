package com.aymankhattab.nateq.core.audio.providers

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * حارس الكاش: كان فحص التثبيت يُعاد حسابه في كل نداء من مسار النطق
 * الساخن (ست مرات في الخدمة)، فصار مربوطاً برقم إصدار التخزين.
 */
class VocalizerInstallationCacheTest {

    @Before
    fun setUp() {
        VocalizerInstallationCache.resetForTest()
    }

    private fun snapshot(version: Int, calls: IntArray) =
        VocalizerInstallationCache.Snapshot(
            version = version,
            families = setOf("family-$version"),
            voices = listOf(
                InstalledVocalizerVoice("Maged $version", "ar")
            )
        ).also { calls[0]++ }

    @Test
    fun cache_isNotRecomputedWithoutBump_andRecomputesAfterBump_breakFix() {
        val calls = intArrayOf(0)

        val first = VocalizerInstallationCache.snapshot(5) {
            snapshot(5, calls)
        }
        val second = VocalizerInstallationCache.snapshot(5) {
            snapshot(5, calls)
        }
        val third = VocalizerInstallationCache.snapshot(5) {
            snapshot(5, calls)
        }
        assertEquals(1, calls[0])
        assertEquals("family-5", first.families.first())
        assertEquals(first, second)
        assertEquals(second, third)

        val afterBump = VocalizerInstallationCache.snapshot(6) {
            snapshot(6, calls)
        }
        assertEquals(2, calls[0])
        assertEquals("family-6", afterBump.families.first())
    }

    @Test
    fun readingSnapshot_doesNotInvalidateIt_breakFix() {
        val calls = intArrayOf(0)
        val first = VocalizerInstallationCache.snapshot(3) {
            snapshot(3, calls)
        }
        repeat(20) {
            VocalizerInstallationCache.snapshot(3) {
                snapshot(3, calls)
            }
        }
        assertEquals(1, calls[0])
        assertEquals("Maged 3", first.voices.first().name)
    }
}
