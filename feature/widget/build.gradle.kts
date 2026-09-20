// البند 4 — :feature:widget: أداة الساعة الناطقة على الشاشة الرئيسية.
// AppWidgetProvider يعلن الوقت عبر :core:audio ويدير إعداداته عبر :core:data
// بلا اعتماد على :app (يستمد SettingsRepository من AnnouncementAppContext).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aymankhattab.nateq.feature.widget"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":core:audio"))

    // اختبارات الوحدة (JUnit) لمنطق أداة الساعة (SpeakingClockWidgetLogicTest):
    // تُشغَّل عبر feature:widget:testDebugUnitTest — لوجيك نقي بلا Robolectric.
    testImplementation(libs.junit)
}
