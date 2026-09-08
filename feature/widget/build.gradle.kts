// البند 4 — :feature:widget: أداة الساعة الناطقة على الشاشة الرئيسية.
// AppWidgetProvider يعلن الوقت عبر :core:audio ويدير إعداداته عبر :core:data
// بلا اعتماد على :app (يستمد SettingsRepository من AnnouncementAppContext).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.aymankhattab.nateq.feature.widget"
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
    implementation(project(":core:data"))
    implementation(project(":core:audio"))
}