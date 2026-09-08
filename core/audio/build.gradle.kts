// البند 4 — :core:audio: نواة تخليق الصوت — المزوّدون (VoiceProvider/EnginePicker/
// SystemVoiceProvider)، الكتالوج، مقسم الكتابات، إعادة أخذ العينات (PcmResampler)،
// معالج الطلبات، وخدمة المحرك نفسها (NateqTtsService). يعتمد على :core:engine
// و:core:data و:core:common بلا أي اعتماد على :app.
plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.aymankhattab.nateq.core.audio"
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

    // كوروتينز — الكوروتينات اللاتزامنية لخدمة المحرك ولمزوّدي الأصوات.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Hilt — NateqTtsService مُعلَّم بـ @AndroidEntryPoint وتُحقن فيه إعدادات
    // التطبيق وقاموس النطق (نفس إصدار :app).
    implementation("com.google.dagger:hilt-android:2.59")
    ksp("com.google.dagger:hilt-compiler:2.59")

    // اختبارات الوحدة (JVM + Robolectric) — نفس طقم اختبارات :app.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}