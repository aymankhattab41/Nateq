package com.aymankhattab.nateq.core.audio.announcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبار بوابة تهيئة المحرك [InitGate] (منطق نقي بلا أندرويد) — سجل
 *  التدقيق بند [5]: طلبُ محركٍ مختلف عن التهيئة الجارية لا يُنطق بمحرك
 *  التهيئة الخطأ، بل يُسلسل إعادة تهيئةٍ لمحركه بعدها. الفردي القديم لم
 *  يكن يأخذ محرك التهيئة الجارية في الحسبان لحظة الحسم ضده. */
class InitGateTest {

    private val results = mutableListOf<Pair<String?, Boolean>>()

    private fun record(engine: String?): (Boolean) -> Unit = { ok ->
        results.add(engine to ok)
    }

    /**
     * يحاكي سلوك [startInit]: يُصرّف نداءات الدور المنتهية فوراً بعد
     * [InitGate.complete]، ثم يُسلسل إعادة تهيئة المحرك التالي إن وُجد.
     */
    private fun simulateComplete(
        gate: InitGate,
        success: Boolean
    ): String? {
        val completion = gate.complete(success)
        completion.served.forEach { it(success) }
        return completion.nextEngine
    }

    @Test
    fun `first request starts the init`() {
        val gate = InitGate()
        assertEquals(
            "لا تهيئة جارية: المتصل يبدأ",
            InitGate.Decision.START,
            gate.enqueue("e1", record("e1"))
        )
        val next = simulateComplete(gate, true)
        assertNull("لا محرك متبقٍ يُعاد تهيئته", next)
        assertEquals(listOf("e1" to true), results)
    }

    @Test
    fun `requests with the same engine join and are served together`() {
        val gate = InitGate()
        gate.enqueue("e1", record("e1"))
        assertEquals(
            "تهيئةٌ قائمة: طلبُ نفس المحرك ينضم",
            InitGate.Decision.JOIN,
            gate.enqueue("e1", record("e1"))
        )
        val next = simulateComplete(gate, true)
        assertNull(next)
        assertEquals(
            listOf("e1" to true, "e1" to true), results
        )
    }

    @Test
    fun `different engine waits then chains its own init`() {
        val gate = InitGate()
        val aCalls = mutableListOf<Boolean>()
        val bCalls = mutableListOf<Boolean>()
        gate.enqueue("e1") { aCalls += it }
        gate.enqueue("e2") { bCalls += it }

        // اكتملت تهيئة e1: لا يُنطق طلبُ e2 بمحرك e1 أبداً.
        val next1 = simulateComplete(gate, true)
        assertEquals(
            "يُصرف طلبُ محرك التهيئة فقط",
            listOf(true), aCalls
        )
        assertEquals(
            "طلبُ e2 لم يُصرَف من تهيئة e1",
            emptyList<Boolean>(), bCalls
        )
        assertEquals(
            "e2 يُعاد تهيئته بعدها", "e2", next1
        )

        // المتصل يُسلسل إعادة تهيئةٍ لمحرك e2 (كما يفعل startInit بعد
        // complete في AnnouncementSpeaker): إكمالها يخدم طلبَ e2.
        val next2 = simulateComplete(gate, true)
        assertEquals(listOf(true), bCalls)
        assertNull(next2)
    }

    @Test
    fun `mixed engines serve each engine only at its own completion`() {
        val gate = InitGate()
        gate.enqueue("e1", record("e1"))
        gate.enqueue("e2", record("e2"))
        gate.enqueue("e1", record("e1"))
        gate.enqueue("e3", record("e3"))

        // ترتيب الوصول يُحافظ عليه عبر السلاسل: e1 كاملة، ثم e2، ثم e3.
        val next1 = simulateComplete(gate, true)
        assertEquals(
            "e2 يسبق e3 في سلسلة إعادة التهيئة",
            "e2", next1
        )
        val next2 = simulateComplete(gate, true)
        assertEquals("بعد e2 يبقى e3", "e3", next2)
        val next3 = simulateComplete(gate, true)
        assertNull(next3)

        assertEquals(
            "ترتيب السلاسل يحافظ على ترتيب وصول المحركات المتنافسة، " +
            "وكل نداءٍ يُصرف بمحرك التهيئة التي انتهت: طلباتُ A تُصرَف " +
            "مع اكتمال A، ثم B، ثم C — ولا يُنطق أيُّ طلب بمحركٍ غيره",
            listOf(
                "e1" to true, "e1" to true,
                "e2" to true, "e3" to true
            ),
            results
        )
    }

    @Test
    fun `failed init drains every request and rests the gate`() {
        val gate = InitGate()
        gate.enqueue("e1", record("e1"))
        gate.enqueue("e2", record("e2"))

        val next = simulateComplete(gate, false)
        assertNull("الفشل لا يُسلسل إعادة تهيئة", next)
        assertEquals(
            listOf("e1" to false, "e2" to false), results
        )

        // البوابة عادت للراحة: طلبٌ جديد يبدأ تهيئةً جديدة.
        assertEquals(
            InitGate.Decision.START,
            gate.enqueue("e3", record("e3"))
        )
        simulateComplete(gate, true)
        assertEquals(
            "تهيئةٌ جديدة ناجحة بعد الفشل السابق",
            "e3", results.last().first
        )
        assertTrue(results.last().second)
    }
}