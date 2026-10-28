package com.aymankhattab.nateq.core.audio.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.LinkedList

/**
 * مكدس الحافظة (ع٥٦-أمر 8).
 * يحفظ آخر 10 نصوص منسوخة في الذاكرة لتسهيل التبديل بينها ولصقها.
 */
object ClipboardStack {

    const val MAX_CAPACITY = 10

    private val stack = LinkedList<String>()
    private val lock = Any()

    fun addEntry(text: String, isSensitive: Boolean = false): Boolean {
        if (isSensitive) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        synchronized(lock) {
            if (stack.peekFirst() == trimmed) {
                return false
            }
            stack.remove(trimmed)
            if (stack.size >= MAX_CAPACITY) {
                stack.removeLast()
            }
            stack.addFirst(trimmed)
            return true
        }
    }

    fun getEntries(): List<String> {
        synchronized(lock) {
            return ArrayList(stack)
        }
    }

    fun peek(): String? {
        synchronized(lock) {
            return stack.peekFirst()
        }
    }

    fun pop(): String? {
        synchronized(lock) {
            return if (stack.isNotEmpty()) stack.removeFirst() else null
        }
    }

    fun clear() {
        synchronized(lock) {
            stack.clear()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return stack.size
        }
    }

    fun onPrimaryClipChanged(context: Context) {
        val repo = SettingsRepository.create(context)
        if (!repo.isExtraEnabled("extra_clipboard_stack")) return

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return

        val desc = clip.description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE")
                == true
            ) {
                return
            }
        }

        val text = clip.getItemAt(0)?.text?.toString() ?: return
        addEntry(text)
    }

    fun pasteOrSpeak(context: Context): String? {
        val entry = peek()
        if (entry.isNullOrBlank()) {
            val emptyMsg = context.getString(R.string.clipboard_empty)
            A11ySpeechCoordinator.speakAnnouncement(context, emptyMsg)
            return null
        }
        val msg = context.getString(R.string.clipboard_item_pasted, entry)
        A11ySpeechCoordinator.speakAnnouncement(context, msg)
        return entry
    }
}
