// البند 4 — :core:data: مستودع الإعدادات وطبقة التخزين (SharedPreferences حالياً؛
// DataStore خيار مستقبلي). يكشف واجهة VoicePrefsProvider لطبقة الصوت.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.aymankhattab.nateq.core.data"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:engine"))

    // SettingsRepository: التخزين المشفَّر لأسماء المتصلين (MasterKey/
    // EncryptedSharedPreferences) — نفس إصدار :app.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // UpdateChecker: FileProvider + تنزيل الـ APK؛ ConnectivityMonitor:
    // استشعار حالة الشبكة — نفس إصدار :app.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // toJson → Gson عبر :core:common (api) — لا حاجة لتكرار gson هنا.

    // اختبارات الوحدة (Robolectric) — اختبار SettingsRepository كما في :app
    // من قبل؛ تُشغَّل عبر :core:data:testDebugUnitTest (AGP 9).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}