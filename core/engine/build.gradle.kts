// البند 4 — :core:engine: معالج النصوص وخط الإنتاج (pipeline) وتفقيط الأرقام
// وقاموس النطق. يقرأ الإعدادات عبر واجهة SynthesisConfig فقط (لا يعتمد :core:data).
plugins {
    id("com.android.library")
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
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // اختبارات الوحدة (JVM + Robolectric) — نفس طقم اختبارات :app؛ تُشغَّل عبر
    // :core:engine:testDebugUnitTest (AGP 9 لا يوفر testReleaseUnitTest).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}