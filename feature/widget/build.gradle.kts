// البند 4 — :feature:widget: أداة الساعة الناطقة على الشاشة الرئيسية.
// AppWidgetProvider يعلن الوقت عبر :core:audio ويدير إعداداته عبر :core:data
// بلا اعتماد على :app (يستمد SettingsRepository من AnnouncementAppContext).
plugins {
    id("com.android.library")
}

android {
    namespace = "com.aymankhattab.nateq.feature.widget"
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
    implementation(project(":core:audio"))

    // OU,O�O"O3O1OO"O1O0 O1O0O?O`Oc O`O1O"O:O)O4O3 (junit) O"O EO`O/O'OO`O?O`O:O4 "O"OO"O?OO4
    // SpeakingClockWidget O1O"O? O`O0O?O0O1 O`O0O1O?OO2 (O"O"O:OEO 4O4O1 O`O0O:OEO1O?O`O4 O`O1O"O O?O1O1).
    testImplementation("junit:junit:4.13.2")
}