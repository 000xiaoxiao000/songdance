plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 36

    // Development helper: if no explicit signing config is provided for release builds,
    // fall back to the local debug keystore so the generated release APK is signed
    // and can be installed on devices. For real releases, do NOT use this — create
    // and reference a proper signing config with your production keystore.
    signingConfigs {
        create("autoDebug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // default debug keystore location used by the Android tooling
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
        }
    }
    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the autoDebug signing config so the output is not left unsigned.
            // If you already configure signingConfigs elsewhere (CI, keystore.properties,
            // etc.) this will simply refer to the debug fallback.
            signingConfig = signingConfigs.getByName("autoDebug")
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

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lottie)
    implementation(libs.androidx.preference.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
