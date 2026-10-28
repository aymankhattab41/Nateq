package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.VisibleForTesting
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository

/**
 * قارئ الجداول بالعناوين (الجولة 56 - إضافات 5 - أمر 5).
 * يقرأ عنوان العمود ورقم الصف ومحتوى الخلية في الجداول
 * التي تحوي أكثر من عمودين.
 */
object TableReader {

    @Volatile
    private var lastColIndex: Int? = null

    @Volatile
    private var lastTableHash: Int? = null

    /**
     * تصفير الحالة للاختبارات.
     */
    fun resetForTesting() {
        lastColIndex = null
        lastTableHash = null
    }

    /**
     * استخراج عنوان العمود من الشجرة أو الخلية.
     */
    fun findColumnHeader(
        tableNode: AccessibilityNodeInfo,
        colIndex: Int
    ): String? {
        val childCount = tableNode.childCount
        for (i in 0 until childCount) {
            val child = tableNode.getChild(i) ?: continue
            val itemInfo = child.collectionItemInfo
            if (itemInfo != null && itemInfo.columnIndex == colIndex) {
                val isHeading = (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P && child.isHeading) ||
                    itemInfo.isHeading ||
                    itemInfo.rowIndex == 0
                if (isHeading) {
                    val txt = child.text?.toString()?.trim()
                    val cd = child.contentDescription?.toString()?.trim()
                    val header = if (!txt.isNullOrBlank()) txt else cd
                    if (!header.isNullOrBlank()) {
                        return header
                    }
                }
            }
        }
        return null
    }

    /**
     * تكوين نص إعلان الخلية وفق القواعد المحددة.
     */
    fun formatCellAnnouncement(
        context: Context,
        columnHeader: String?,
        cellContent: String,
        rowIndex: Int,
        includeHeader: Boolean
    ): String {
        val rowNumber = rowIndex + 1
        return if (includeHeader && !columnHeader.isNullOrBlank()) {
            context.getString(
                R.string.table_cell_format,
                columnHeader,
                cellContent,
                rowNumber
            )
        } else {
            "$cellContent, ${rowNumber}"
        }
    }

    /**
     * معالجة تركيز خلية في جدول.
     */
    fun onCellFocused(
        context: Context,
        node: AccessibilityNodeInfo
    ): Boolean {
        val settings = SettingsRepository.create(context)
        if (!settings.isExtraEnabled("extra_table_reader")) {
            return false
        }

        val itemInfo = node.collectionItemInfo ?: return false
        val rowIndex = itemInfo.rowIndex
        val colIndex = itemInfo.columnIndex

        // البحث عن الحاوية التي تحمل معلومات الجدول
        var container: AccessibilityNodeInfo? = node.parent
        var colCount = 0
        var depth = 0
        while (container != null && depth < 3) {
            val cInfo = container.collectionInfo
            if (cInfo != null) {
                colCount = cInfo.columnCount
                break
            }
            container = container.parent
            depth++
        }

        // الجداول بأكثر من عمودين فقط
        if (colCount <= 2 || container == null) {
            return false
        }

        val tableHash = container.hashCode()
        val repeatAlways = settings.getExtraBoolean(
            "extra_table_reader_repeat_header",
            false
        )

        val colChanged = lastTableHash != tableHash ||
            lastColIndex != colIndex
        val includeHeader = repeatAlways || colChanged

        lastTableHash = tableHash
        lastColIndex = colIndex

        val header = findColumnHeader(container, colIndex)
        val cellText = node.text?.toString()?.trim()
            ?: node.contentDescription?.toString()?.trim().orEmpty()

        if (cellText.isBlank()) {
            return false
        }

        val announcement = formatCellAnnouncement(
            context = context,
            columnHeader = header,
            cellContent = cellText,
            rowIndex = rowIndex,
            includeHeader = includeHeader
        )

        A11ySpeechCoordinator.speakAnnouncement(
            context = context,
            text = announcement,
            isInterruptAllowed = true
        )
        return true
    }
}
