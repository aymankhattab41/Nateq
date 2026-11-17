package com.aymankhattab.nateq.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ع٤٢-3 — حارس انحدار لقرار تحذير محاذاة 16KB من إخراج readelf.
 * منطق الدالتين أدناه مطابق لمنطق سكربت Gradle؛ سكربتات البناء ليست
 * على classpath اختبارات JVM، لذا يُختبر القرار هنا بنسخة نقية.
 */
class ElfAlignmentDecisionTest {

    private val requiredAlignment = 0x4000L

    /** أصغر محاذاة LOAD في إخراج readelf -l، أو null إن لم تُوجد. */
    private fun minLoadAlignment(lines: List<String>): Long? {
        var min: Long? = null
        for (line in lines) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.firstOrNull() != "LOAD") continue
            val raw = fields.lastOrNull()?.removePrefix("0x") ?: continue
            val value = raw.toLongOrNull(16) ?: continue
            min = if (min == null) value else minOf(min, value)
        }
        return min
    }

    /** صحيح حين تستوجب المحاذاة تحذيراً (غائبة أو أصغر من 16KB). */
    private fun needsAlignmentWarning(alignment: Long?): Boolean {
        return alignment == null || alignment < requiredAlignment
    }

    @Test
    fun loadAlignment0x1000_triggersClearWarning() {
        val lines = listOf(
            "  LOAD 0x000000 0x0000000000000000 0x0000000000000000 " +
                "0x0d53f0 0x0d53f0 R E 0x1000",
            "  LOAD 0x0d5530 0x00000000000d6530 0x00000000000d6530 " +
                "0x00306c 0x00cda8 RW  0x1000"
        )
        val alignment = minLoadAlignment(lines)
        assertEquals(0x1000L, alignment)
        assertTrue(needsAlignmentWarning(alignment))
    }

    @Test
    fun loadAlignment0x4000_isAccepted() {
        val lines = listOf(
            "  LOAD 0x000000 0x0 0x0 0x1000 0x1000 R E 0x4000"
        )
        val alignment = minLoadAlignment(lines)
        assertEquals(0x4000L, alignment)
        assertFalse(needsAlignmentWarning(alignment))
    }

    @Test
    fun missingAlignment_warnsInsteadOfClaimingSupport() {
        assertNull(minLoadAlignment(listOf("no LOAD segments here")))
        assertTrue(needsAlignmentWarning(null))
    }

    @Test
    fun oneSmallSegment_warnsEvenIfAnotherIsAligned() {
        val lines = listOf(
            "  LOAD 0x000000 0x0 0x0 0x1000 0x1000 R E 0x1000",
            "  LOAD 0x001000 0x1 0x1 0x1000 0x1000 RW  0x4000"
        )
        assertEquals(0x1000L, minLoadAlignment(lines))
        assertTrue(
            needsAlignmentWarning(
                minLoadAlignment(lines)
            )
        )
    }
}
