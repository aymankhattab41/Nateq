package com.aymankhattab.nateq.settings

import com.aymankhattab.nateq.core.audio.providers.EmbeddedStorage
import java.io.File

/**
 * مساعدات تشخيص شاشة الأصوات — كتلة نقية فُصلت من
 * [VoiceSelectionFragment] دون تغيير سلوك. تُمرَّر المعطيات صراحةً
 * (ملف/بايتات/نص) فلا تعتمد على حالة الواجهة أو السياق.
 */

/** وصف محرك مدمج يظهر في تشخيص التخزين. */
internal data class EmbeddedEngineDiag(
    val id: String,
    val name: String,
    val storageEngine: EmbeddedStorage.Engine
)

/** مجموع أحجام الملفات تحت مجلد (صفر إن لم يكن موجوداً). */
internal fun dirSize(dir: File): Long {
    if (!dir.exists()) return 0L
    var size = 0L
    dir.walk().forEach { file ->
        if (file.isFile) size += file.length()
    }
    return size
}

/** صياغة حجم البايتات بوحدات B/KB/MB/GB (قسمة صحيحة كالسابق). */
internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024
    if (kb < 1024) return "${kb} KB"
    val mb = kb / 1024
    if (mb < 1024) return "${mb} MB"
    val gb = mb / 1024
    return "${gb} GB"
}

/** إخفاء وسط رقم التسجيل مع إبقاء أول/آخر 4 محارف. */
internal fun maskRegistration(reg: String?): String {
    if (reg.isNullOrBlank()) return "(لا يوجد)"
    val trimmed = reg.trim()
    return if (trimmed.length > 8) {
        val first4 = trimmed.take(4)
        val last4 = trimmed.takeLast(4)
        val maskedMiddle = "*".repeat(trimmed.length - 8)
        "$first4$maskedMiddle$last4"
    } else {
        "****"
    }
}
