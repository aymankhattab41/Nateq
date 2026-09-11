// البند 4 — :core:engine: معالج النصوص وخط الإنتاج (pipeline) وتفقيط الأرقام
// وقاموس النطق. يقرأ الإعدادات عبر واجهة SynthesisConfig فقط (لا يعتمد :core:data).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aymankhattab.nateq.core.engine"
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

    // PronunciationDictionary — تخزين مشفّر via EncryptedSharedPreferences/MasterKey
    // (نفس إصدار :app).
    implementation(libs.androidx.security.crypto)

    // اختبارات الوحدة (JVM + Robolectric) — نفس طقم اختبارات :app؛ تُشغَّل عبر
    // :core:engine:testDebugUnitTest (AGP 9 لا يوفر testReleaseUnitTest).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}