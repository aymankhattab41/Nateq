package com.aymankhattab.nateq.core.audio.accessibility

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/**
 * متتبع تشخيص تسجيل أحداث الإضافات ديناميكياً (ع٥٧-أمر 1).
 * يحفظ آخر 50 عملية إعادة تطبيق لأنواع الأحداث وقائمة الإضافات المفعّلة.
 */
object ExtrasDiagnosticsTracker {

    private const val TAG = "ExtrasDiagnostics"
    const val MAX_RECORDS = 50

    data class ReapplyRecord(
        val timestamp: Long,
        val addedTypes: Int,
        val removedTypes: Int,
        val currentTypes: Int,
        val enabledExtras: List<String>
    )

    private val records = LinkedList<ReapplyRecord>()
    private val lock = Any()

    fun record(
        addedTypes: Int,
        removedTypes: Int,
        currentTypes: Int,
        enabledExtras: List<String>
    ) {
        val entry = ReapplyRecord(
            timestamp = System.currentTimeMillis(),
            addedTypes = addedTypes,
            removedTypes = removedTypes,
            currentTypes = currentTypes,
            enabledExtras = ArrayList(enabledExtras)
        )
        synchronized(lock) {
            if (records.size >= MAX_RECORDS) {
                records.removeFirst()
            }
            records.addLast(entry)
        }
        val addedStr = describeEventTypes(addedTypes)
        val removedStr = describeEventTypes(removedTypes)
        val count = enabledExtras.size
        Log.i(
            TAG,
            "Reapply: +[$addedStr] -[$removedStr] totalExtras=$count"
        )
    }

    fun getRecords(): List<ReapplyRecord> {
        synchronized(lock) {
            return ArrayList(records)
        }
    }

    fun clear() {
        synchronized(lock) {
            records.clear()
        }
    }

    fun format(): String {
        val list = getRecords()
        if (list.isEmpty()) {
            return "(لا توجد عمليات إعادة تطبيق لأحداث الإضافات)"
        }
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("=== سجل أحداث الإضافات الديناميكي (${list.size}) ===")
            for ((index, item) in list.withIndex()) {
                val timeStr = sdf.format(Date(item.timestamp))
                val addStr = describeEventTypes(item.addedTypes)
                val remStr = describeEventTypes(item.removedTypes)
                appendLine(
                    "#${index + 1} [$timeStr] مضاف: [$addStr] | " +
                        "محذوف: [$remStr]"
                )
                val hexTypes = Integer.toHexString(item.currentTypes)
                appendLine(
                    "   إجمالي الأنواع: 0x$hexTypes | " +
                        "المفعّلة (${item.enabledExtras.size}): " +
                        item.enabledExtras.joinToString(", ")
                )
            }
        }
    }

    fun describeEventTypes(types: Int): String {
        if (types == 0) return "none"
        val names = mutableListOf<String>()
        if (types and AccessibilityEvent.TYPE_VIEW_SCROLLED != 0) {
            names.add("SCROLLED")
        }
        if (types and AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED != 0) {
            names.add("A11Y_FOCUSED")
        }
        if (types and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0) {
            names.add("CONTENT_CHANGED")
        }
        if (types and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED != 0) {
            names.add("TEXT_CHANGED")
        }
        if (types and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0) {
            names.add("WINDOW_STATE")
        }
        if (types and AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED != 0) {
            names.add("NOTIFICATION")
        }
        if (types and AccessibilityEvent.TYPE_VIEW_CLICKED != 0) {
            names.add("CLICKED")
        }
        return if (names.isEmpty()) "0x${Integer.toHexString(types)}"
        else names.joinToString("|")
    }
}
