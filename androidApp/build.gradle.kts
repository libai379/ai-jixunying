import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// 专用签名：和前身 App（com.taiclear.taic，调试签名）不是同一把钥匙，包名也不同，绝不会互相覆盖安装。
// 密钥在 G:/DevCache/signing/，密码在仓库根目录 keystore.properties（不进 git）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.guixing.jixunying"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.guixing.jixunying"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("jxy") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        val jxy = signingConfigs.findByName("jxy")
        debug { if (jxy != null) signingConfig = jxy }
        release {
            isMinifyEnabled = false
            signingConfig = jxy ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.android.embedded)
}
