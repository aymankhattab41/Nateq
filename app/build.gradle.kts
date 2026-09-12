plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

import java.util.Properties
import com.android.build.api.variant.VariantOutput

// بيانات مفتاح التوقيع تُقرأ من key.properties (مُستثنى من git، لا يُرفع).
// إن لم يجد الملف أو كان ناقصاً يفشل بناء release بخطأ واضح («لم تُضبط كلمة
// مرور المخزن») بدل التراجع الصامت إلى توقيع debug الذي يجعل الـ APK غير قابل
// للنشر — بينما يبقى بناء debug غير مؤثر بالكامل.
val keyProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.aymankhattab.nateq"
    // أندرويد 17 = API 37 (المنصة المستهدفة بالتحديث الأخير)
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aymankhattab.nateq"
        // أندرويد 7.0 = API 24 (الحد الأدنى المطلوب في الخطة)
        minSdk = 24
        // أندرويد 17 = API 37: أحدث نسخة مثبّتة محلياً وهدف التوافق الحالي
        targetSdk = 37
        versionCode = 13
        versionName = "0.13.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keyProperties.getProperty("storeFile") ?: "key/lord-tts.jks")
            storePassword = keyProperties.getProperty("storePassword")
            keyAlias = keyProperties.getProperty("keyAlias")
            keyPassword = keyProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true // لتقليل حجم الـ APK قدر الإمكان
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // الدائم: توقيع release من الـ keystore فقط. نقص بياناته = فشل
            // صريح في البناء (لا debug fallback أبداً — غير قابل للنشر).
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9 دمج Kotlin: يُضبط جذر الأداة (JDK 17) وأسماء الأهداف معاً
    // (النطم kotlinOptions القديم أُزيل من DSL).
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        viewBinding = true
    }
}

// إعادة تسمية مخرجات APK بمسمى ثابت lord_tts.apk بدل app-release.apk
// (androidComponents هي واجهة AGP الحديثة، تبقى صالحة في AGP 9+ بدل
//  applicationVariants/outputs القديمة التي أُزيلت).
androidComponents {
    onVariants(androidComponents.selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("lord_tts.apk")
        }
    }
}

dependencies {
    // البند 4 — الوحدات التي تفكّك إليها :app شفرة المصدر تدرّجياً:
    // تتبنى :app كل الوحدات وتوزّع الشفرة عليها لاحقاً (تبقى الملفات في
    // مكانها الحالي حتى تُنقل بالكامل في مراحل لاحقة).
    implementation(project(":core:common"))
    implementation(project(":core:engine"))
    implementation(project(":core:audio"))
    implementation(project(":core:data"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:widget"))

    // خفيفة الوزن ومقصودة - لا تستخدم SDKs ضخمة من كل شركة، بل REST مباشر
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material) // مكونات واجهة متوافقة مع TalkBack افتراضيًا
    implementation(libs.androidx.preference.ktx)   // شاشة إعدادات جاهزة ومتوافقة إتاحيًا
    // تشفير مفاتيح الـ API (EncryptedSharedPreferences + Android Keystore).
    // نُبقي على alpha06 لأنها آخر نسخة فيها API مشفّر يعمل عبر minSdk 24 دون
    // ComponentFactory/تطبيق DenyList فك جذر؛ الأنساق الأحدث (stable المعلنة
    // كـ 1.1.0 غير نازلة) تعتمد معيّنات مختلفة. التخزين عندنا مؤقت/محلي فقط
    // ولا يجوز ترقية عشوائية تُقلب صيغة التخزين وتكسر مفاتيح المستخدمين.
    implementation(libs.androidx.security.crypto)

    // Gson للـ serialization في PronunciationDictionary (النسخ الاحتياطي للقاموس)
    implementation(libs.gson)

    // كوروتينز لإدارة الطلبات غير المتزامنة بدون تجميد الخدمة
    implementation(libs.kotlinx.coroutines.android)

    // حقن التبعيات (Hilt) + ViewModel لحوكمة الشاشات وتفكيك الفصيل الكبير
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.fragment)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
