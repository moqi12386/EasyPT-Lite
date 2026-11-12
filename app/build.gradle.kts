plugins { id("com.android.application") }

android {
    namespace = "com.easypt.gui"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.easypt.gui.lite"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("easypt") {
            storeFile = file("C:/Users/35923/Desktop/安卓调试台/android/easypt.keystore")
            storePassword = "android"
            keyAlias = "easypt"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("easypt") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("easypt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}