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
        // LibXray / community Xray Android bindings (optional)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "TelegramProxy"
include(":app")
