// البند 4 — :core:common: أدوات وحدود واجهات مشتركة بلا اعتماد على أي وحدة
// أخرى (nvابتدائية: المفوّضات، JSON، كود اللغة، مقطع النص، مطلق الإعلانات).
plugins {
    id("com.android.library")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    // Gson — واجهة NateqJson المركزية والـ ParameterizedType (GsonTypes) تعتمدان عليه.
    // api: NateqJson يكشف أنواع gson (JsonElement/JsonObject...) في API العام.
    api("com.google.code.gson:gson:2.12.1")
}