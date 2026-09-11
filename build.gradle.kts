// ملف Gradle الجذري - لا تضع هنا أي dependencies خاصة بالتطبيق
plugins {
    // أندرويد 17 (API 37) يتطلب AGP 9.1+؛ وAGP 9 يدمج Kotlin
    // (Built-in Kotlin) فيستغني عن إضافة org.jetbrains.kotlin.android
    alias(libs.plugins.android.application) apply false

    // com.android.library تُصرَّح هنا أيضاً (apply false) لأنها تُستخدم في
    // كل الوحدات؛ التصريح الجذري هو ما يضع الإضافة على classpath بإصدار
    // معروف فيسكت تدقيق توافق plugin resolution في الوحدات.
    alias(libs.plugins.android.library) apply false

    // KSP 2.3.3+ = الحد الأدنى المتوافق مع AGP 9/Built-in Kotlin
    // (2.3.1 أضاف دعم AGP 9، و2.3.3+ يصلح API المهملات).
    alias(libs.plugins.ksp) apply false

    // Hilt 2.59+ يوثّق التوافق مع AGP 9 (الحد الأدنى في جدول التوافقية).
    alias(libs.plugins.hilt.android) apply false
}
