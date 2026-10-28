package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.LinkedHashMap

/**
 * تنبيه حذف وتعديل الرسائل (ع٥٦-أمر 22).
 * يراقب التغيرات في نصوص الرسائل ويكتشف فورياً حذف أو تعديل
 * الرسائل أثناء المحادثة.
 */
object ChatChangeWatcher {

    const val MAX_CAPACITY = 200

    private val CHAT_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web"
    )

    private val memory = object : LinkedHashMap<Int, String>(
        MAX_CAPACITY,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, String>?
        ): Boolean {
            return size > MAX_CAPACITY
        }
    }

    private var currentChatPackage: String? = null

    fun onWindowContentChanged(
        context: Context,
        packageName: String?,
        rootNode: AccessibilityNodeInfo?
    ) {
        if (packageName !in CHAT_PACKAGES) {
            clearMemory()
            return
        }
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_message_change_alert")) return
        if (rootNode == null) return

        if (currentChatPackage != packageName) {
            clearMemory()
            currentChatPackage = packageName
        }

        scanMessages(context, rootNode)
    }

    private fun scanMessages(
        context: Context,
        node: AccessibilityNodeInfo
    ) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 3) {
            val nodeId = node.hashCode()
            val oldText = memory[nodeId]

            if (oldText != null && oldText != text) {
                val lower = text.lowercase()
                if (lower.contains("تم حذف هذه الرسالة") ||
                    lower.contains("this message was deleted")
                ) {
                    val msg = context.getString(R.string.message_deleted)
                    A11ySpeechCoordinator.speakAnnouncement(context, msg)
                } else if (lower.contains("تم التعديل") ||
                    lower.contains("edited")
                ) {
                    val msg = context.getString(R.string.message_edited)
                    A11ySpeechCoordinator.speakAnnouncement(context, msg)
                }
            }
            memory[nodeId] = text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scanMessages(context, child)
        }
    }

    fun clearMemory() {
        memory.clear()
        currentChatPackage = null
    }

    fun size(): Int = memory.size
}
