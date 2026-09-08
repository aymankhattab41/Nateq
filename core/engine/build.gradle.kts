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
}