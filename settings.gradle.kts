pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Gradle fetches the JDK the build asks for (`core` pins jvmToolchain(17)) rather than
// failing on whatever happens to be installed. A fresh laptop and CI then agree, and the
// "Cannot find a Java installation ... languageVersion=17" trap in VERIFICATION.md §6 goes
// away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
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
// :app landed 18 Aug 2026. It depends on :core for the rollup and its presentation, so
// there is exactly one implementation of "what does the app say" and it is the tested one.
include(":app")
