// البند 4 — :feature:settings: شاشات الإعدادات والـ ViewModels. يقرأ البيانات
// عبر :core:data ويدير النطق عبر :core:audio بلا اعتماد على :app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.aymankhattab.nateq.feature.settings"
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
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests {
            // Robolectric: تضمين موارد الوحدة (layouts/strings) لاختبارات
            // نفخ العروض مثل حوار إعداد جميع اللغات.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":core:audio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.fragment)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
