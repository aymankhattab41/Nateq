// ملف Gradle الجذري - لا تضع هنا أي dependencies خاصة بالتطبيق
plugins {
    // أندرويد 17 (API 37) يتطلب AGP 9.1+؛ وAGP 9 يدمج Kotlin
    // (Built-in Kotlin) فيستغني عن إضافة org.jetbrains.kotlin.android
    id("com.android.application") version "9.2.0" apply false

    // KSP 2.3.3+ = الحد الأدنى المتوافق مع AGP 9/Built-in Kotlin
    // (2.3.1 أضاف دعم AGP 9، و2.3.3+ يصلح API المهملات).
    id("com.google.devtools.ksp") version "2.3.3" apply false

    // Hilt 2.59+ يوثّق التوافق مع AGP 9 (الحد الأدنى في جدول التوافقية).
    id("com.google.dagger.hilt.android") version "2.59" apply false
}
