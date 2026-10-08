import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.guixing.jixunying.MainKt"
        providers.gradleProperty("packageJdk").orNull?.let { javaHome = it }
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Xmx1g")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "ai-jixunying"
            packageVersion = "1.2.1"
            description = "AI集训营"
            vendor = "guixing"
            modules("java.instrument", "java.management", "java.prefs", "java.naming", "java.net.http", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata")
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                // 装到当前用户目录，不用管理员权限
                perUserInstall = true
                menuGroup = "AI集训营"
                upgradeUuid = "6f1b8c0e-6c3a-4f1e-9a52-3c1f6b2a7d11"
            }
        }
    }
}
