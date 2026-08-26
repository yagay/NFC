plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.nfcdoorcard.xposed"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.nfcdoorcard.xposed"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
