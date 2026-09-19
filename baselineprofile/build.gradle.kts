// وحدة Baseline Profile (Macrobenchmark) — بند د.6.1
// تُنشئ ملف baseline-prof.txt أثناء CI أو بالأمر generateBaselineProfile
// لتستفيد ART من الترجمة قبل التشغيل وتسرّع الإقلاع الأول + نطق TTS.
// لا تُشغَّل مع الاختبارات العادية (:app:testDebugUnitTest) ولا تنتج APK.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.aymankhattab.nateq.baselineprofile"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    defaultConfig {
        minSdk = 28          // Macrobenchmark يتطلب API 28+
        targetSdk = 37
        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
}



dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
