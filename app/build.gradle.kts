plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Renders composables on the JVM through layoutlib, so a screen can be *looked at* in CI.
    // Every screen in this app has shipped at least one bug that a single glance would have
    // caught, because nothing here has ever been run before it reached the phone.
    alias(libs.plugins.paparazzi)
}

android {
    namespace = "dk.lifelist.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dk.lifelist.app"
        minSdk = 29
        targetSdk = 35
        // Bumped by CI from the tag, so a downloaded APK can be traced to a release.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"
    }

    signingConfigs {
        create("sideload") {
            // A committed keystore, with a password in plain sight, on purpose.
            //
            // Android refuses to update an app signed by a different key, and Gradle's
            // built-in debug key is generated per machine — so every CI run produced a
            // differently-signed APK and every update failed until the app was uninstalled.
            //
            // This key protects nothing. It is not a Play upload key and it identifies no
            // one; its only job is to be *the same key every time*, so v0.6 can replace v0.5
            // on the phone. Publishing anywhere real needs a proper key from CI secrets, and
            // that will be a different signing config, not this one with a better password.
            storeFile = file("keystore/sideload.jks")
            storePassword = "sideload"
            keyAlias = "sideload"
            keyPassword = "sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }

    androidResources {
        // The model must stay uncompressed so ONNX Runtime can memory-map it straight out
        // of the APK. Compressed, a 350 MB asset would be inflated to disk on first run —
        // slow, and it would double the storage the app occupies.
        noCompress += "onnx"
    }

    packaging {
        // A 350 MB asset pushes past the legacy zip layout.
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.accompanist.permissions)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
