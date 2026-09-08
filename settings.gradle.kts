pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Nateq"

// البند 4 — التفكيك إلى وحدات معيارية: الوحدات تُنشأ تدرّجياً والملفات
// تُنقل إليها بالأولوية المعمارية (common ← engine ← data ← audio ←
// feature:settings ← app) مع بقاء التطبيق كلّه قابلاً للبناء في كل مرحلة.
include(":app")
include(":core:common")
include(":core:engine")
include(":core:audio")
include(":core:data")
include(":feature:settings")
