plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from environment variables (set by the GitHub workflow
// from repository secrets). Without them the APK is signed with the debug key,
// which is fine for testing but must not be used for real releases.
val keystorePath: String? = System.getenv("VL_KEYSTORE")?.takeIf { File(it).exists() }

// License server settings, from the GitHub workflow (repository variables). Empty = Free plan only.
fun env(name: String, default: String = "") = (System.getenv(name) ?: "").ifBlank { default }.replace("\"", "")

android {
    namespace = "com.bizfunnel.vidloot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bizfunnel.vidloot"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        buildConfigField("String", "LICENSE_API", "\"${env("VIDLOOT_LICENSE_API")}\"")
        buildConfigField("String", "LICENSE_PUBKEY", "\"${env("VIDLOOT_LICENSE_PUBKEY")}\"")
        buildConfigField("String", "LICENSE_KID", "\"${env("VIDLOOT_LICENSE_KID", "k1")}\"")
        buildConfigField("String", "LICENSE_PRODUCT", "\"${env("VIDLOOT_LICENSE_PRODUCT_ANDROID", env("VIDLOOT_LICENSE_PRODUCT", "vidloot"))}\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("VL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VL_KEY_ALIAS")
                keyPassword = System.getenv("VL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // One small APK per phone type, plus a universal one that works everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // youtubedl-android runs Python and FFmpeg from the native library folder.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Ed25519 signature checks for the license client.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
