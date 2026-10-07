plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
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
            api(libs.coroutines.core)
            api(libs.serialization.json)
        }
        // 引擎：电脑和手机都要能单独用，所以放在两边共用的 JVM 源码集里
        val jvmShared by creating {
            dependsOn(commonMain.get())
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
}
