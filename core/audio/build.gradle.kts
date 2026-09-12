// البند 4 — :core:audio: نواة تخليق الصوت — المزوّدون (VoiceProvider/EnginePicker/
// SystemVoiceProvider)، الكتالوج، مقسم الكتابات، إعادة أخذ العينات (PcmResampler)،
// معالج الطلبات، وخدمة المحرك نفسها (NateqTtsService). يعتمد على :core:engine
// و:core:data و:core:common بلا أي اعتماد على :app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
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
    implementation(libs.kotlinx.coroutines.android)

    // **بند 9.4:** المواصفات الصريحة لتوابع androidx.core (NotificationCompat/
    // ContextCompat...) بدل الاعتماد الضمني المتسرب من :core:data — إغلاق
    // هشاشة البناء حين تُعدَّل تبعيات أي وحدة أخرى.
    implementation(libs.androidx.core.ktx)

    // Hilt — NateqTtsService مُعلَّم بـ @AndroidEntryPoint وتُحقن فيه إعدادات
    // التطبيق وقاموس النطق (نفس إصدار :app).
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // اختبارات الوحدة (JVM + Robolectric) — نفس طقم اختبارات :app.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}