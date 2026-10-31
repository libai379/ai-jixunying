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
            packageVersion = "1.13.0"
            description = "AI集训营"
            // 发布者（Windows「程序和功能」里显示）；和 model/Presets.kt 的 APP_DEVELOPER 一致（用户 2026-10-14 定）
            vendor = "癸星科技"
            copyright = "© 2026 癸星科技"
            // jdk.httpserver：Claude Code 当总指挥用的本机 MCP 服务（DesktopMcpServer，2026-10-22）
            modules("java.instrument", "java.management", "java.prefs", "java.naming", "java.net.http", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata", "jdk.httpserver")
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                // 装到当前用户目录，不用管理员权限
                perUserInstall = true
                menuGroup = "AI集训营"
                upgradeUuid = "6f1b8c0e-6c3a-4f1e-9a52-3c1f6b2a7d11"
                // 安装包、exe、开始菜单的图标（tools/make_icons.py 生成）；以前没配，用的是 Compose 默认图标
                iconFile.set(project.file("icons/ai-jixunying.ico"))
            }
        }
    }
}

// 检查更新用的电脑整包（2026-10-30）：createDistributable 出来的整个程序文件夹打成 zip，发版时和安装程序一起传上 GitHub
// （改名成 AI-Jixunying-<版本>-<日期>-windows-update.zip）。电脑上点「更新」下这个，在原来的文件夹里换掉再打开，
// 不用安装程序，桌面图标、开机自启都还指着同一个地方（DesktopPlatform.swapFromZip）。
tasks.register<Zip>("packageUpdateZip") {
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName.set("ai-jixunying-windows-update.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/update"))
}
