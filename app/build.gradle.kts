plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.nfcdoorcard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.nfcdoorcard"
        minSdk = 31
        targetSdk = 37
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
