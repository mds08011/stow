plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.stow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.stow"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "2.5"
    }

    // Release signing is supplied through environment variables so no keystore or
    // password ever lives in the repo. CI populates these from GitHub Secrets; see
    // docs/release-signing.md. A local release build without them is simply unsigned,
    // and debug builds are unaffected.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("STOW_KEYSTORE_FILE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STOW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STOW_KEY_ALIAS")
                keyPassword = System.getenv("STOW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("STOW_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // OkHttp for networking
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    testImplementation("junit:junit:4.13.2")
    // The android.jar used by unit tests stubs org.json to throw; the real artifact on the
    // test classpath makes the JSON parsing testable off-device.
    testImplementation("org.json:json:20231013")
}
