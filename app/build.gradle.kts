plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mc.fuwuqi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mc.fuwuqi"
        minSdk = 24
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"
    }

    signingConfigs {
        create("lime") {
            storeFile = file("lime.keystore")
            storePassword = "lime123456"
            keyAlias = "lime"
            keyPassword = "lime123456"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lime")
        }
        release {
            signingConfig = signingConfigs.getByName("lime")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    androidResources {
        localeFilters += listOf("zh", "en")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.json:json:20240303")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
