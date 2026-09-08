// البند 4 — :core:audio: مزوّدو الأصوات ومعالجة التردد/PCM والمتحدث (وصلة
// الخلفية). يعتمد على واجهات :core:engine و:core:data لا على تطبيقاتها.
plugins {
    id("com.android.library")
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
}