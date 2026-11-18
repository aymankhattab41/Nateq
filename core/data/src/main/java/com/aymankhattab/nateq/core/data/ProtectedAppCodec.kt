package com.aymankhattab.nateq.core.data

/**
 * تحويل قائمة التطبيقات المحمية إلى/من تمثيل JSON المخزَّن في الإعدادات.
 * فُصلت من [SettingsRepository] بلا تغيير سلوك: الترميز مطابقٌ حرفياً
 * (`packageName`/`label`/`mode`) وفكُّ الترميز يحتفظ بالقيم الافتراضية
 * ونفس مسار ابتلاع الاستثناء وإرجاع قائمة فارغة.
 */

/** يبني نص JSON المخزَّن لقائمة التطبيقات المحمية. */
internal fun encodeProtectedApps(apps: List<ProtectedApp>): String {
    val arr = org.json.JSONArray()
    for (app in apps) {
        val obj = org.json.JSONObject().apply {
            put("packageName", app.packageName)
            put("label", app.label)
            put("mode", app.mode)
        }
        arr.put(obj)
    }
    return arr.toString()
}

/** يفكّ نص JSON المخزَّن؛ أي خلل في الشكل يُنتج قائمة فارغة (كالسابق). */
internal fun decodeProtectedApps(raw: String): List<ProtectedApp> = try {
    val arr = org.json.JSONArray(raw)
    val list = mutableListOf<ProtectedApp>()
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        list.add(
            ProtectedApp(
                packageName = obj.getString("packageName"),
                label = obj.optString(
                    "label",
                    obj.getString("packageName")
                ),
                mode = obj.optString(
                    "mode",
                    ProtectedApp.MODE_FULL_DISABLE
                )
            )
        )
    }
    list
} catch (_: Exception) {
    emptyList()
}
