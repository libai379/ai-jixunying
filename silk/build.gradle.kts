// 微信语音（SILK）解码（2026-11-05）：libjitsi 里 SILK 的纯 Java 移植，只收了解码要用的 67 个文件，原样未改；
// 外加本项目写的入口 WeixinSilk.java。说明和出处见 silk/README.md。电脑和手机都用这一份。
plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile>().configureEach {
    // 手机也要用：按 Java 11 出字节码
    options.release = 11
    options.encoding = "UTF-8"
}
