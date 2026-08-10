import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nexus.tim.hook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexus.tim.hook"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-keep.pro")
    }

    buildTypes {
        debug {
            // Keep symbols/logs for LSPosed field debugging.
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    implementation(project(":tim_protocol"))
    compileOnly("de.robv.android.xposed:api:82")
    implementation("org.json:json:20240303")
}
