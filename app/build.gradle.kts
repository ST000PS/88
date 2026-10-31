plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.st000ps.powerstone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.st000ps.powerstone"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // The phone and watch apps must share one signing key for the Wearable Data Layer to connect them.
    // The key lives outside the repo; its location and passwords come from the user-level
    // ~/.gradle/gradle.properties (POWERSTONE_*). Without them (e.g. CI) builds use the debug key.
    val sharedKey = providers.gradleProperty("POWERSTONE_KEYSTORE").orNull?.let { path ->
        signingConfigs.create("shared") {
            storeFile = file(path)
            storePassword = providers.gradleProperty("POWERSTONE_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("POWERSTONE_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("POWERSTONE_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug {
            sharedKey?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            // Personal sideload build: the shared key when present, else the local debug key.
            signingConfig = sharedKey ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // The widget render test inflates real layouts and drawables.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Talks to the Pixel Watch app (now playing, controls, EQ, sign-in handoff).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
