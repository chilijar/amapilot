import java.util.Properties

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 统一签名（方案 B）：debug/release 共用 keystores/release.keystore，
 * 保证 AMap key（绑定包名+SHA1）只需一个。
 * 优先环境变量（CI secrets / local.properties），否则用仓库默认。
 */
fun unifiedSigningConfig(android: com.android.build.gradle.BaseExtension) {
    val env = System.getenv()
    val storePath = env["RELEASE_KEYSTORE_PATH"]
    val storePass = env["RELEASE_KEYSTORE_PASSWORD"]
    val alias = env["RELEASE_KEY_ALIAS"]
    val keyPass = env["RELEASE_KEY_PASSWORD"]
    android.signingConfigs.create("release").apply {
        if (storePath != null && file(storePath).exists() && !storePass.isNullOrBlank()) {
            storeFile = file(storePath)
            storePassword = storePass
            this.keyAlias = alias
            // 空/缺省时回退到 store 密码（PKCS12 keystore 的 key 密码恒等于 store 密码）
            this.keyPassword = keyPass?.takeIf { it.isNotBlank() } ?: storePass
        } else {
            // 仓库默认 keystore（与 AMap key 绑定的 SHA1 一致）
            val fallback = rootProject.file("keystores/release.keystore")
            if (fallback.exists()) {
                storeFile = fallback
                // 凭据只从 local.properties / 环境变量读取，仓库不内置默认值
                storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("KEYSTORE_ALIAS")
                keyPassword = localProperties.getProperty("KEYSTORE_PASSWORD")
            }
        }
    }
}

android {
    namespace = "com.amapilot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.amapilot"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "0.21.0"
        // CI 环境变量优先，本地 local.properties 兜底
        manifestPlaceholders["AMAP_API_KEY"] =
            System.getenv("AMAP_API_KEY") ?: localProperties.getProperty("AMAP_API_KEY") ?: ""
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // 统一签名：debug/release 共用同一 keystore（方案 B）
    unifiedSigningConfig(this)
    buildTypes.all {
        signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.car.app)
    // 高德一体包（官网 2026-09：Navi/3DMap 11.3.100 + Search 9.8.1 + Location
    // 11.3.101，16KB 对齐）。SDK 二进制不入库（高德协议 4.12.8 再分发限制，
    // 见 docs/OPEN_SOURCE_COMPLIANCE.md），由 scripts/fetch-amap-sdk.sh 拉取；
    // 构建产物与 jar 名由脚本内的 md5 pin 锁定版本
    val amapJars = fileTree("libs") { include("AMap3DMap*.jar") }.files.sortedBy { it.name }
    when (amapJars.size) {
        0 -> throw GradleException(
            "高德 SDK 未就绪：先运行 ./scripts/fetch-amap-sdk.sh（见 README「自行构建」）"
        )
        1 -> implementation(files(amapJars.single()))
        else -> throw GradleException(
            "app/libs 下存在多个高德 SDK jar（${amapJars.joinToString { it.name }}），" +
                "请仅保留一个（由 scripts/fetch-amap-sdk.sh 管理）"
        )
    }

    testImplementation(libs.junit)
    // 单测提供 org.json 真实现（android.jar 内是抛 "Stub!" 的桩），
    // ConfigBackup 的 JSON 校验/预览逻辑得以纯 JVM 验证
    testImplementation("org.json:json:20231013")

    debugImplementation(libs.androidx.ui.tooling)
}
