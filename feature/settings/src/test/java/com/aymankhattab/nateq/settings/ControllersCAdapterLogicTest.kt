package com.aymankhattab.nateq.settings

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.junit.Assert.assertEquals
import org.junit.Test

/** اختبارات منطق مسندات القوائم (Controllers C): معيار DiffUtil
 *  لإدخالات القاموس. */
class ControllersCAdapterLogicTest {

    private data class Op(
        val kind: Char,
        val position: Int,
        val count: Int = 0
    ) {
        override fun toString(): String = "$kind@$position"
    }

    private class RecordingCallback(private val ops: MutableList<Op>) :
        ListUpdateCallback {
        override fun onInserted(position: Int, count: Int) {
            ops.add(Op('I', position, count))
        }

        override fun onRemoved(position: Int, count: Int) {
            ops.add(Op('R', position, count))
        }

        override fun onMoved(fromPosition: Int, toPosition: Int) {
            ops.add(Op('M', fromPosition, toPosition))
        }

        override fun onChanged(position: Int, count: Int, payload: Any?) {
            ops.add(Op('C', position, count))
        }
    }

    /** يحسب ويدفع فرق القائمتين عبر معيار الإنتاج [DictEntryDiff]. */
    private fun diffOps(
        old: List<Pair<String, String>>,
        new: List<Pair<String, String>>
    ): List<Op> {
        val ops = mutableListOf<Op>()
        val result = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = new.size
                override fun areItemsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean = DictEntryDiff.areItemsTheSame(
                    old[oldItemPosition],
                    new[newItemPosition]
                )
                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean = DictEntryDiff.areContentsTheSame(
                    old[oldItemPosition],
                    new[newItemPosition]
                )
            },
            true
        )
        result.dispatchUpdatesTo(RecordingCallback(ops))
        return ops
    }

    @Test
    fun diffEmitsOnlyRemoveChangeInsert() {
        val old = listOf("a" to "1", "b" to "2", "c" to "3")
        val new = listOf("a" to "1", "b" to "22", "d" to "4")
        val ops = diffOps(old, new)
        assertEquals(
            setOf("R@2", "C@1", "I@2"),
            ops.map { it.toString() }.toSet()
        )
    }

    @Test
    fun diffIsEmptyForIdenticalLists() {
        val list = listOf("a" to "1", "b" to "2")
        assertEquals(emptyList<Op>(), diffOps(list, list))
    }

    @Test
    fun diffDetectsSingleAddition() {
        val old = listOf("a" to "1")
        val new = listOf("a" to "1", "b" to "2")
        assertEquals(
            setOf("I@1"),
            diffOps(old, new).map { it.toString() }.toSet()
        )
    }

    @Test
    fun diffDetectsSingleKeyChange() {
        val old = listOf("x" to "1")
        val new = listOf("y" to "1")
        assertEquals(
            setOf("R@0", "I@0"),
            diffOps(old, new).map { it.toString() }.toSet()
        )
    }
}