package com.aymankhattab.nateq.core.audio.accessibility

/**
 * رسالة دردشة مسطّحة استُخرجت من عقد الشاشة (ع٤١-ب5).
 *
 * @param sender اسم المرسل إن أمكن استنتاجه، وإلا null.
 * @param body نص الرسالة.
 */
data class ChatMessage(
    val sender: String?,
    val body: String
) {
    /** بصمة تمييز الرسالة لمنع تكرار العدّ. */
    fun fingerprint(): Int = (sender.orEmpty() + "\u0000" + body).hashCode()
}

/**
 * قارئ لقطة الشاشة لمحادثات الدردشة (ع٤١-ب5).
 * يستخرج عُقد الرسائل من شجرة الشاشة اعتماداً على [SelectorPacks]
 * دون الاعتماد على معرّفات حزمةٍ بعينها (معرّفات مرنة).
 */
object ChatSnapshotReader {

    /**
     * يستخرج رسائل الدردشة من قائمة عُقد الشاشة بترتيبها الممرَّر.
     * يُهمل العقد غير المرئية، والفارغة، وعُقد كلمات المرور (بوسطة
     * الخصوصية — ع٤١-ب0.3).
     */
    fun messagesFrom(views: List<NodeView>): List<ChatMessage> {
        if (views.isEmpty()) return emptyList()
        val result = ArrayList<ChatMessage>()
        for (view in views) {
            if (!view.isVisibleToUser) continue
            if (view.isPassword) continue
            if (!SelectorPacks.isMessageViewId(view.viewIdResourceName)) {
                continue
            }
            val body = view.text?.toString()?.trim().orEmpty()
            if (body.isEmpty()) continue
            result.add(ChatMessage(sender = senderOf(view), body = body))
        }
        return result
    }

    /**
     * استخراج عنوان أو اسم المحادثة الموثوق من عقد الشاشة (البند 15).
     * يبحث في معرّفات العناوين ورؤوس المحادثات المعتمدة.
     */
    fun extractConversationTitle(views: List<NodeView>): String? {
        if (views.isEmpty()) return null
        for (view in views) {
            if (!view.isVisibleToUser) continue
            val id = view.viewIdResourceName?.lowercase().orEmpty()
            if (id.contains("conversation_contact_name") ||
                id.contains("conversation_title") ||
                id.contains("chat_title") ||
                id.contains("contact_name") ||
                id.contains("action_bar_title") ||
                id.contains("toolbar_title")
            ) {
                val t = view.text?.toString()?.trim()
                    ?: view.contentDescription?.toString()?.trim()
                if (!t.isNullOrBlank() && t.length in 1..80 &&
                    !t.contains('\n')
                ) {
                    return t
                }
            }
        }
        val topCandidate = views.firstOrNull { v ->
            v.isVisibleToUser &&
                v.boundsInScreen.top <= 250 &&
                v.boundsInScreen.height() in 20..180 &&
                !v.isClickable &&
                !v.text.isNullOrBlank() &&
                v.text.toString().trim().length in 1..80 &&
                !v.text.toString().contains('\n') &&
                !SelectorPacks.isMessageViewId(v.viewIdResourceName)
        }?.text?.toString()?.trim()

        return topCandidate?.takeIf { it.isNotBlank() }
    }

    /**
     * يستنتج اسم المرسل من contentDescription إن كان على هيئة
     * «المرسل: النص»، وإلا يعيد null.
     */
    private fun senderOf(view: NodeView): String? {
        val cd = view.contentDescription?.toString()?.trim().orEmpty()
        if (cd.isEmpty()) return null
        val colon = cd.indexOfAny(charArrayOf(':', '\u061B', '\uFF1A'))
        if (colon <= 0) return null
        val name = cd.substring(0, colon).trim()
        return name.ifBlank { null }
    }
}
