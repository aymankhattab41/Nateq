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
    // حارس fdsan الأصلي يُبنى محلياً بهذه النسخة من NDK (المثبّتة).
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 24
        // قواعد R8 الموروثة (consumer-rules.pro): مكتبات JNI في هذه
        // الوحدة (Vocalizer) تصل لفئاتها وحقولها بالاسم،
        // فتُطبَّق تلقائياً على أي تطبيق مضيف بلا إعادة كتابة.
        consumerProguardFiles("consumer-rules.pro")
        // حارس fdsan يُبنى للمعماريات المدعومة نفسها في :app
        // (arm64-v8a + armeabi-v7a) فلا تُقيَّد الحزمة النهائية.
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }

    androidResources {
        noCompress += listOf("xmf", "dat", "hdr")
    }

    // ع٢٩-5: واجهة AIDL إلى المضيف المعزول (:engine_host) — مُعطَّلة
    // افتراضياً منذ AGP 4.1 فلا تُبنى الملفات بلا تفعيل صريح.
    buildFeatures {
        aidl = true
    }

    // يُبنى libnateqfdsan.so محلياً؛ تُستدعى دالّة تعطيل fdsan عند بدء
    // عملية :engine_host قبل تحميل المحرك (انظر NativeFdsanGuard).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // اختبارات Robolectric تُحمّل موارد الوحدة (نصوص النطق) من أقراصها
    // الذكية/الافتراضية — بدونه تُفشل أي findResource عبر R.string في
    // اختبارات الوحدة (NotFound) وتُبنى التطبيقات تحت حزمة الافتراضي.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // حارسُ «استثناء المؤثر لا يقطع النطق» يستدعي Log.w في مسار
            // الإسناد الفاشل، وandroid.util.Log غير منفَّذ على JVM فيرمي
            // "not mocked" ويُفشل الاختبارَ زوراً. القيمةُ الافتراضية
            // تجعل الاستدعاء صامتاً كما في الأندرويد، فلا يفقد الحارسُ
            // معناه.
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2048m"
            }
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(project(":core:common"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))

    // كوروتينز — الكوروتينات اللاتزامنية لخدمة المحرك ولمزوّدي الأصوات.
    implementation(libs.kotlinx.coroutines.android)


    // **بند 9.4:** المواصفات الصريحة لتوابع androidx.core (NotificationCompat/
    // ContextCompat...) بدل الاعتماد الضمني المتسرب من :core:data — إغلاق
    // هشاشة البناء حين تُعدَّل تبعيات أي وحدة أخرى.
    implementation(libs.androidx.core.ktx)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ع٤١-ب12: التعرّف الضوئي على نصّ الصور (OCR). تأتي المكتبة من JitPack
    // (لا من Maven Central)، وتضمّ Tesseract وLeptonica بلغات JNI. تُشحن
    // لكل معماريات abiFilters في :app (arm64-v8a وarmeabi-v7a)، وبيانات
    // اللغة (ara.traineddata) تُنزَّل عند أول استعمال (OcrScreenReader) لا تُحزَم.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

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
