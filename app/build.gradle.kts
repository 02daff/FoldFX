plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.foldfx"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.foldfx"
        minSdk = 30          // Sensor.TYPE_HINGE_ANGLE needs Android 11+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// No extra libraries needed: everything uses the plain Android framework.
dependencies {}
