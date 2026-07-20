plugins {
    // Task 1: Kotlin JVM so protocol unit tests run without Android SDK.
    // Later tasks switch :app to com.android.application when SDK is available.
    kotlin("jvm") version "1.9.24" apply false
}
