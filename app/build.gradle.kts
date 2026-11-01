plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.bbcl.snapsave"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bbcl.snapsave"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            // Real phones are arm64; pass -Pabis=x86_64 to get an APK for the emulator.
            include(*(findProperty("abis") as String? ?: "arm64-v8a").split(",").toTypedArray())
            isUniversalApk = false
        }
    }

    // youtubedl-android executes the bundled python/ffmpeg binaries from the
    // extracted native lib dir, so they must not stay compressed inside the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // libpython.zip.so / libffmpeg.zip.so are zip archives, not ELF files.
            keepDebugSymbols += "**/*.zip.so"
            excludes += "lib/x86/**"
        }
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            // youtubedl-android parses yt-dlp output with Jackson reflection; keep R8 off.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    val coil = "3.5.0"
    implementation("io.coil-kt.coil3:coil-compose:$coil")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coil")
}
