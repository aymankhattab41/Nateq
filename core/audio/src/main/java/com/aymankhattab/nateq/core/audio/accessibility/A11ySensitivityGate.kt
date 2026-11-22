package com.aymankhattab.nateq.core.audio.accessibility

import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * بوابة حساسية موحدة لفحص الأحداث والعقد قبل نطق صدى الكتابة أو استخراج النص.
 * تفحص:
 * 1. event.isPassword
 * 2. source.isPassword
 * 3. أنواع الإدخال الحساسة (كلمات المرور، الرموز، PIN)
 * 4. العقد الحساسة المتداخلة في الحاويات
 * وتتعامل بأمان تام مع غياب source أو إبطاله دون أي انهيار.
 */
object A11ySensitivityGate {

    private const val MAX_CONTAINER_DEPTH = 3

    /**
     * هل الحدث أو مصدره أو أي عقدة فرعية متداخلة يمثل حقلاً حساساً؟
     */
    fun isSensitive(event: AccessibilityEvent?): Boolean {
        if (event == null) return false
        if (event.isPassword) return true

        val source = runCatching { event.source }.getOrNull() ?: return false
        return try {
            isNodeSensitive(source, 0)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * فحص عقدة معينة وشجرتها الفرعية بحثاً عن مؤشرات كلمة مرور أو PIN.
     */
    fun isNodeSensitive(
        node: AccessibilityNodeInfo?,
        depth: Int = 0
    ): Boolean {
        if (node == null) return false
        if (node.isPassword) return true

        val inputType = node.inputType
        if (isSensitiveInputType(inputType)) return true

        if (depth >= MAX_CONTAINER_DEPTH) return false

        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull()
                ?: continue
            val sensitive = isNodeSensitive(child, depth + 1)
            if (sensitive) return true
        }
        return false
    }

    /**
     * فحص نوع الإدخال inputType لتمييز حقول كلمات المرور والـ PIN
     */
    fun isSensitiveInputType(inputType: Int): Boolean {
        if (inputType == 0) return false
        val clazz = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (clazz == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        }
        if (clazz == InputType.TYPE_CLASS_NUMBER) {
            if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                return true
            }
        }
        return false
    }

    /**
     * تنقيح نص صدى الكتابة إن كان الحقلاً حساساً، مع إرجاع رمز بديل "•"
     * للحفاظ على إتاحة الحقل دون نطق السر.
     */
    fun sanitizeEchoText(
        event: AccessibilityEvent,
        rawEchoText: String?
    ): String? {
        if (rawEchoText.isNullOrBlank()) return null
        if (!isSensitive(event)) return rawEchoText
        return "•"
    }
}
