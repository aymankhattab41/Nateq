// ملف Gradle الجذري - لا تضع هنا أي dependencies خاصة بالتطبيق
plugins {
    // أندرويد 17 (API 37) يتطلب AGP 9.1+؛ وAGP 9 يدمج Kotlin
    // (Built-in Kotlin) فيستغني عن إضافة org.jetbrains.kotlin.android
    id("com.android.application") version "9.2.0" apply false
}
