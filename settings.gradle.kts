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

rootProject.name = "life-list"

// :core is pure Kotlin/JVM on purpose — the rollup is the thing this app exists for,
// so it should be testable in CI in seconds without the Android SDK or an emulator.
include(":core")
// :app is added once the Android module lands — Gradle fails on a
// declared-but-missing module, and CI should stay green in between.
