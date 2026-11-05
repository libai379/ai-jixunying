rootProject.name = "ai-jixunying"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared", ":desktopApp", ":androidApp")
// 微信语音（SILK）解码：libjitsi 的纯 Java 移植，见 silk/README.md
include(":silk")
