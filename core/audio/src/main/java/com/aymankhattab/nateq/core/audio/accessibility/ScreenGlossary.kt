package com.aymankhattab.nateq.core.audio.accessibility

import android.content.Context
import com.aymankhattab.nateq.core.audio.R
import com.aymankhattab.nateq.core.data.SettingsRepository
import org.json.JSONObject

/**
 * مترجم الشاشة بقاموس محلي بلا اتصال (ع٥٦-أمر 14).
 * يترجم مصطلحات الواجهة الشائعة من الإنجليزية إلى العربية
 * دون أي اتصال بالإنترنت.
 */
object ScreenGlossary {

    private val BUILTIN_GLOSSARY = linkedMapOf(
        "terms of service" to "شروط الخدمة",
        "privacy policy" to "سياسة الخصوصية",
        "sign in" to "تسجيل الدخول",
        "sign out" to "تسجيل الخروج",
        "log in" to "تسجيل الدخول",
        "log out" to "تسجيل الخروج",
        "settings" to "الإعدادات",
        "setting" to "الإعداد",
        "home" to "الرئيسية",
        "back" to "رجوع",
        "next" to "التالي",
        "previous" to "السابق",
        "done" to "تم",
        "cancel" to "إلغاء",
        "ok" to "موافق",
        "submit" to "إرسال",
        "send" to "إرسال",
        "search" to "بحث",
        "notifications" to "الإشعارات",
        "notification" to "إشعار",
        "download" to "تنزيل",
        "downloads" to "التنزيلات",
        "share" to "مشاركة",
        "delete" to "حذف",
        "remove" to "إزالة",
        "edit" to "تعديل",
        "save" to "حفظ",
        "close" to "إغلاق",
        "open" to "فتح",
        "install" to "تثبيت",
        "update" to "تحديث",
        "details" to "التفاصيل",
        "more" to "المزيد",
        "less" to "أقل",
        "refresh" to "تحديث",
        "apply" to "تطبيق",
        "clear" to "مسح",
        "select" to "تحديد",
        "copy" to "نسخ",
        "paste" to "لصق",
        "cut" to "قص",
        "view" to "عرض",
        "profile" to "الملف الشخصي",
        "account" to "الحساب",
        "security" to "الأمان",
        "privacy" to "الخصوصية",
        "help" to "المساعدة",
        "about" to "حول",
        "exit" to "خروج",
        "register" to "تسجيل",
        "allow" to "سماح",
        "deny" to "رفض",
        "permission" to "إذن",
        "permissions" to "الأذونات",
        "retry" to "إعادة المحاولة",
        "loading" to "جاري التحميل",
        "error" to "خطأ",
        "success" to "نجاح",
        "warning" to "تحذير",
        "info" to "معلومات",
        "audio" to "الصوت",
        "video" to "الفيديو",
        "image" to "صورة",
        "photo" to "صورة",
        "camera" to "الكاميرا",
        "microphone" to "الميكروفون",
        "storage" to "وحدة التخزين",
        "file" to "ملف",
        "folder" to "مجلد",
        "play" to "تشغيل",
        "pause" to "إيقاف مؤقت",
        "stop" to "إيقاف",
        "forward" to "تقديم",
        "rewind" to "إرجاع",
        "mute" to "كتم",
        "unmute" to "إلغاء الكتم"
    )

    fun translate(
        text: String?,
        customJson: String? = null
    ): String? {
        if (text.isNullOrBlank()) return null
        val query = text.trim().lowercase()

        if (!customJson.isNullOrBlank()) {
            val custom = parseCustomGlossary(customJson)
            val customMatch = findMatchInMap(query, custom)
            if (customMatch != null) return customMatch
        }

        return findMatchInMap(query, BUILTIN_GLOSSARY)
    }

    private fun findMatchInMap(
        query: String,
        glossary: Map<String, String>
    ): String? {
        val exact = glossary[query]
        if (exact != null) return exact

        val sortedKeys = glossary.keys.sortedByDescending { it.length }
        for (key in sortedKeys) {
            if (query == key || query.contains(key)) {
                return glossary[key]
            }
        }
        return null
    }

    private fun parseCustomGlossary(jsonStr: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        try {
            val json = JSONObject(jsonStr)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k.trim().lowercase()] = json.getString(k).trim()
            }
        } catch (_: Throwable) {
        }
        return map
    }

    fun translateAndSpeak(context: Context, text: String?): Boolean {
        val repo = SettingsRepository.create(context)
        val customJson = repo.getExtraString("extra_custom_glossary_json", "")
        val translation = translate(text, customJson)
        if (translation != null) {
            A11ySpeechCoordinator.speakAnnouncement(context, translation)
            return true
        }
        val notFoundMsg = context.getString(R.string.no_translation_found)
        A11ySpeechCoordinator.speakAnnouncement(context, notFoundMsg)
        return false
    }
}
