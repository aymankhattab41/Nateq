// البند 4 — :core:data: مستودع الإعدادات وطبقة التخزين (SharedPreferences حالياً؛
// DataStore خيار مستقبلي). يكشف واجهة VoicePrefsProvider لطبقة الصوت.
plugins {
    alias(libs.plugins.android.library)
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
    implementation(libs.androidx.security.crypto)
    // UpdateChecker: FileProvider + تنزيل الـ APK؛ ConnectivityMonitor:
    // استشعار حالة الشبكة — نفس إصدار :app.
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // toJson → Gson عبر :core:common (api) — لا حاجة لتكرار gson هنا.

    // اختبارات الوحدة (Robolectric) — اختبار SettingsRepository كما في :app
    // من قبل؛ تُشغَّل عبر :core:data:testDebugUnitTest (AGP 9).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}