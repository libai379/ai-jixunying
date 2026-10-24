plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// GitHub 登录要的应用密钥（docs/账号设计说明.md）：不进仓库，只在本机一个文件里，打包时生成 BuildSecrets.kt 放进程序。
// 文件不在时生成空的，界面上「用 GitHub 登录」提示还没开通。换位置用 -Pjxy.githubSecretFile=路径。
val githubSecretFile = file((findProperty("jxy.githubSecretFile") as String?) ?: "G:/DevCache/secrets/github-oauth-secret.txt")
val genSecrets by tasks.registering {
    val secret = if (githubSecretFile.exists()) githubSecretFile.readText().trim() else ""
    val out = layout.buildDirectory.dir("generated/secrets/kotlin")
    inputs.property("githubSecretHash", secret.hashCode())
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/guixing/jixunying/engine/BuildSecrets.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.guixing.jixunying.engine\n\n// 打包时生成，别提交（build 目录本来就不进仓库）\ninternal object BuildSecrets {\n    val githubSecret: String get() = \"${secret.reversed()}\".reversed()\n}\n")
    }
}

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    android {
        namespace = "com.guixing.jixunying.shared"
        compileSdk = 37
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.material3)
            implementation(libs.cmp.icons)
            // 液态玻璃（2026-10-22，docs/界面换装说明.md）：和癸星科技同一个库、同一版
            implementation(libs.backdrop)
            api(libs.coroutines.core)
            api(libs.serialization.json)
        }
        // 引擎：电脑和手机都要能单独用，所以放在两边共用的 JVM 源码集里
        val jvmShared by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(genSecrets)
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.paho.mqtt)
            }
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.coroutines.swing)
                implementation(libs.pdfbox)
                implementation(libs.zxing.core)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.moquette)
                // 测试里用假的模型服务器
                implementation(libs.ktor.server.cio)
                // 离屏渲染界面截图（ShotsTest，检查界面用）
                implementation(compose.desktop.currentOs)
            }
        }
        androidMain {
            dependsOn(jvmShared)
            dependencies {
                implementation(libs.coroutines.android)
                implementation(libs.pdfbox.android)
            }
        }
    }
}

// 自动测试不去扫开发机上真实的「文档」「桌面」「下载」（文档库的测试自己指定临时文件夹）
tasks.withType<Test>().configureEach {
    systemProperty("jxy.docs.autoscan", "false")
    // 自动测试不开真浏览器（读平台链接用的 Edge）；LiveBrowserTest 自己直接建
    systemProperty("jxy.browser", "off")
    systemProperty("jxy.fx.auto", "false")
    // 壁纸光斑在截图里停着（不然每张截图光斑位置不一样）
    systemProperty("jxy.wallpaper.still", "true")
    // 界面截图（ShotsTest）写成 -Pjxy.shots=文件夹 就能跑：JVM 里读不了 Gradle 的属性，这里转成环境变量。
    // 给 -P 才设，不然测试进程会一直带着上一轮的目录
    (project.findProperty("jxy.shots") as String?)?.let { environment("JXY_SHOTS", it) }
}
