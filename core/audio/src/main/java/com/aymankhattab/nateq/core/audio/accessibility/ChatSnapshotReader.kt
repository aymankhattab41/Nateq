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
