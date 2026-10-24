plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.b5solucoes.tcllight"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.b5solucoes.tcllight"
        minSdk = 23
        targetSdk = 28
        // CI builds an internal higher-code APK only to verify an actual in-place update.
        versionCode = providers.gradleProperty("verificationVersionCode").orNull?.toInt() ?: 10
        versionName = "0.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        // Direct APK distribution for legacy TV firmware, not a Google Play submission.
        // Keep this policy finding visible; all other release errors remain fatal.
        warning += "ExpiredTargetSdkVersion"
    }
}


// Test tooling is confined to the instrumentation APK; the launcher has no runtime dependencies.
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
