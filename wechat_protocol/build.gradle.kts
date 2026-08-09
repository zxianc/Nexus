plugins {
    kotlin("jvm") version "2.4.10"
}

group = "com.nexus.wechat"
version = "0.1.0"

dependencies {
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnit()
}
