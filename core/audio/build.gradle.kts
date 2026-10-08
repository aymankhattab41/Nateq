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
        // قواعد R8 الموروثة (consumer-rules.pro): مكتبات JNI في هذه
        // الوحدة (Vocalizer/Acapela/Sherpa) تصل لفئاتها وحقولها بالاسم،
        // فتُطبَّق تلقائياً على أي تطبيق مضيف بلا إعادة كتابة.
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }

    // ع٢٩-5: واجهة AIDL إلى المضيف المعزول (:engine_host) — مُعطَّلة
    // افتراضياً منذ AGP 4.1 فلا تُبنى الملفات بلا تفعيل صريح.
    buildFeatures {
        aidl = true
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

    // Sherpa-ONNX — محرك توليد الصوت العصبي المحلي (Piper / Coqui / VITS)
    implementation(libs.sherpa.onnx)

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
