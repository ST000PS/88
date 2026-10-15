plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Pixel Watch app. Same applicationId and signing key as the phone app, which the Wearable Data
// Layer requires for the two to talk.
android {
    namespace = "com.st000ps.powerstone.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.st000ps.powerstone"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // See app/build.gradle.kts: the shared key lives outside the repo (POWERSTONE_* properties).
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
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = sharedKey ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].apply {
        // The site API, its models, the cookie jar and the phone<->watch protocol are compiled
        // from the phone app's own files (copied in at build time), so the two can't drift apart.
        java.srcDir(layout.buildDirectory.dir("generated/shared/java"))
        // Silkscreen pixel font (SIL OFL), as in the phone app.
        assets.srcDir("../app/src/main/assets")
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
}

val copySharedSources = tasks.register<Sync>("copySharedSources") {
    from("../app/src/main/java") {
        include(
            "com/st000ps/powerstone/data/Api.kt",
            "com/st000ps/powerstone/data/Models.kt",
            "com/st000ps/powerstone/data/PersistentCookieJar.kt",
            "com/st000ps/powerstone/wear/WearProtocol.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/shared/java"))
}
tasks.named("preBuild") { dependsOn(copySharedSources) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")

    implementation("androidx.health:health-services-client:1.0.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
}
