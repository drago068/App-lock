plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.privatelock"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    val secretDialerCode = providers.gradleProperty("privateLockSecretCode").get()
    require(secretDialerCode.matches(Regex("[0-9]{1,16}"))) {
        "privateLockSecretCode must contain 1 to 16 digits"
    }

    defaultConfig {
        applicationId = "com.privatelock"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["secretDialerCode"] = secretDialerCode
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.crypto.tink:tink-android:1.15.0")
}
