pluginManagement {
    repositories {
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        maven(url = "https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven(url = "https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "wechat_protocol"
