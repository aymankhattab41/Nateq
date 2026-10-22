package com.aymankhattab.nateq.core.audio.engine

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * متتبع آخر النصوص المنطوقة في سجل دائري لآخر 20 نصاً (الجولة 51 - الأمر 3).
 * يُسجّل النصوص الخام قبل أي معالجة لتسهيل تتبعها في أداة تشخيص النطق.
 */
object LastSpokenTextsTracker {

    private const val MAX_ENTRIES = 20
    private val buffer = ConcurrentLinkedQueue<String>()

    fun record(rawText: String) {
        if (rawText.isBlank()) return
        buffer.add(rawText)
        while (buffer.size > MAX_ENTRIES) {
            buffer.poll()
        }
    }

    fun getRecent(): List<String> = buffer.toList().reversed()

    fun clear() {
        buffer.clear()
    }
}
