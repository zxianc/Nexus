plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nexus.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexus.assistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-mvp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/androidMain/java")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // sherpa-onnx Android AAR v1.13.4 (local libs/; see README)
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    testImplementation("junit:junit:4.13.2")
}
