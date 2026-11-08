plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.air.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.air.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("AIR_BUILD_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("AIR_SIGNING_KEYSTORE") ?: "air-release.jks")
            storePassword = System.getenv("AIR_SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("AIR_SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("AIR_SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
