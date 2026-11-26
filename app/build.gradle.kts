plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads env vars (set by GitHub Actions) and falls back to the debug key locally.
val ksPath: String? = System.getenv("LITE_KEYSTORE_PATH")
val hasReleaseKey = !ksPath.isNullOrBlank() && file(ksPath).exists()

android {
    namespace = "com.litepptx.viewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.litepptx.viewer"
        minSdk = 21
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("lite") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("LITE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LITE_KEY_ALIAS")
                keyPassword = System.getenv("LITE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("lite")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/**", "kotlin/**", "DebugProbesKt.bin")
        }
    }
}

// Zero third-party dependencies on purpose: the whole app uses only the Android platform APIs.
dependencies {
}
