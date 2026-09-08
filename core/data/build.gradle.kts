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
}