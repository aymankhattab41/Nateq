plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
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
        versionCode = 6
        versionName = "0.6.0"
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
    // خفيفة الوزن ومقصودة - لا تستخدم SDKs ضخمة من كل شركة، بل REST مباشر
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0") // مكونات واجهة متوافقة مع TalkBack افتراضيًا
    implementation("androidx.preference:preference-ktx:1.2.1")   // شاشة إعدادات جاهزة ومتوافقة إتاحيًا
    // تشفير مفاتيح الـ API (EncryptedSharedPreferences + Android Keystore).
    // نُبقي على alpha06 لأنها آخر نسخة فيها API مشفّر يعمل عبر minSdk 24 دون
    // ComponentFactory/تطبيق DenyList فك جذر؛ الأنساق الأحدث (stable المعلنة
    // كـ 1.1.0 غير نازلة) تعتمد معيّنات مختلفة. التخزين عندنا مؤقت/محلي فقط
    // ولا يجوز ترقية عشوائية تُقلب صيغة التخزين وتكسر مفاتيح المستخدمين.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Gson للـ serialization في PronunciationDictionary (النسخ الاحتياطي للقاموس)
    implementation("com.google.code.gson:gson:2.10.1")

    // كوروتينز لإدارة الطلبات غير المتزامنة بدون تجميد الخدمة
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // حقن التبعيات (Hilt) + ViewModel لحوكمة الشاشات وتفكيك الفصيل الكبير
    implementation("com.google.dagger:hilt-android:2.59")
    ksp("com.google.dagger:hilt-compiler:2.59")
    implementation("androidx.hilt:hilt-navigation-fragment:1.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
