plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.nfcdoorcard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.nfcdoorcard"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.activity:activity:1.10.0")
    compileOnly("io.github.libxposed:api:102.0.0")
}
