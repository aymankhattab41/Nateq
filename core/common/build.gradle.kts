// البند 4 — :core:common: أدوات وحدود واجهات مشتركة بلا اعتماد على أي وحدة
// أخرى (نواة ابتدائية: المفوّضات، JSON، كود اللغة، مقطع النص، مطلقات الإعلانات).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.aymankhattab.nateq.core.common"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 دمج Kotlin: جذر الأداة (JDK 17) وأسماء الأهداف معاً.
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    // المفوّضون الموحّدون (AppDispatchers) — Dispatchers.Main يحتاج منصة أندرويد.
    implementation(libs.kotlinx.coroutines.android)
    // Gson — واجهة NateqJson المركزية والـ ParameterizedType (GsonTypes) تعتمدان عليه.
    // api: NateqJson يكشف أنواع gson (JsonElement/JsonObject...) في API العام.
    api(libs.gson)
}