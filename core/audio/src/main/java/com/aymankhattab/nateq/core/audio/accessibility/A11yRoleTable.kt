package com.aymankhattab.nateq.core.audio.accessibility

/**
 * جدول دور العنصر بالعربية (ع٤١-ب3.3) — منطقٌ نقيّ قابل للاختبار.
 * المفتاح هو اسم الصنف المجرّد (بعد آخر نقطة).
 */
object A11yRoleTable {

    private val ROLES: Map<String, String> = mapOf(
        "Button" to "زر",
        "MaterialButton" to "زر",
        "AppCompatButton" to "زر",
        "ImageButton" to "زر صورة",
        "FloatingActionButton" to "زر عائم",
        "EditText" to "حقل نص",
        "AppCompatEditText" to "حقل نص",
        "MaterialEditText" to "حقل نص",
        "AutoCompleteTextView" to "حقل نص",
        "CheckBox" to "مربع اختيار",
        "CheckedTextView" to "عنصر محدد",
        "RadioButton" to "زر اختيار",
        "Switch" to "مفتاح تبديل",
        "SwitchCompat" to "مفتاح تبديل",
        "ToggleButton" to "مفتاح تبديل",
        "ImageView" to "صورة",
        "TextView" to "نص",
        "ListView" to "قائمة",
        "RecyclerView" to "قائمة",
        "GridView" to "شبكة",
        "Spinner" to "قائمة منسدلة",
        "SeekBar" to "شريط تمرير",
        "ProgressBar" to "شريط تقدم",
        "TabLayout" to "تبويب",
        "WebView" to "صفحة ويب",
        "ViewPager" to "صفحات",
        "ViewPager2" to "صفحات",
        "ScrollView" to "منطقة تمرير",
        "Toolbar" to "شريط أدوات"
    )

    private const val DEFAULT_ROLE = "عنصر"

    /** دورٌ عربيٌّ مبسّط للعنصر؛ يعود إلى «عنصر» عند الجهل. */
    fun roleFor(className: String?): String {
        val simple = className
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: return DEFAULT_ROLE
        return ROLES[simple] ?: DEFAULT_ROLE
    }
}
