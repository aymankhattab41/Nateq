package com.aymankhattab.nateq.core.audio.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import java.util.LinkedList

/**
 * مكدس الحافظة (ع٥٦-أمر 8 / ع٥٧-أمر 2).
 * يحفظ آخر 10 نصوص منسوخة في الذاكرة ويتنقل بينها دورياً ويسمح بلصقها.
 */
object ClipboardStack {

    const val MAX_CAPACITY = 10
    const val MAX_SNIPPET_CHARS = 120

    private val stack = LinkedList<String>()
    private val lock = Any()

    @Volatile
    private var selectedIndex = -1

    fun getSelectedIndex(): Int = synchronized(lock) { selectedIndex }

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
            selectedIndex = -1
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
            selectedIndex = -1
            return if (stack.isNotEmpty()) stack.removeFirst() else null
        }
    }

    fun clear() {
        synchronized(lock) {
            stack.clear()
            selectedIndex = -1
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return stack.size
        }
    }

    /**
     * قراءة العنصر التالي دورياً بين العناصر المخزنة (ع٥٧-أمر 2).
     * ينطق «<ترتيبه> من <العدد>: <أول 120 حرفاً من النص>».
     */
    fun readNext(context: Context): String? {
        val (item, ordinal, total) = synchronized(lock) {
            if (stack.isEmpty()) {
                null
            } else {
                selectedIndex = (selectedIndex + 1) % stack.size
                Triple(stack[selectedIndex], selectedIndex + 1, stack.size)
            }
        } ?: run {
            val emptyMsg = context.getString(R.string.clipboard_empty)
            A11ySpeechCoordinator.speakAnnouncement(context, emptyMsg)
            return null
        }

        val snippet = if (item.length > MAX_SNIPPET_CHARS) {
            item.take(MAX_SNIPPET_CHARS)
        } else {
            item
        }
        val msg = context.getString(
            R.string.clipboard_item_read_format,
            ordinal,
            total,
            snippet
        )
        A11ySpeechCoordinator.speakAnnouncement(context, msg)
        return item
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

    /**
     * لصق العنصر المحدد حالياً في الحافظة والنطق به (ع٥٧-أمر 2).
     */
    fun pasteOrSpeak(context: Context): String? {
        val entry = synchronized(lock) {
            if (stack.isEmpty()) {
                null
            } else if (selectedIndex in 0 until stack.size) {
                stack[selectedIndex]
            } else {
                stack.peekFirst()
            }
        }

        if (entry.isNullOrBlank()) {
            val emptyMsg = context.getString(R.string.clipboard_empty)
            A11ySpeechCoordinator.speakAnnouncement(context, emptyMsg)
            return null
        }

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
        cm?.let {
            val clip = ClipData.newPlainText("nateq_clipboard_stack", entry)
            it.setPrimaryClip(clip)
        }

        val msg = context.getString(R.string.clipboard_item_pasted, entry)
        A11ySpeechCoordinator.speakAnnouncement(context, msg)
        return entry
    }
}
