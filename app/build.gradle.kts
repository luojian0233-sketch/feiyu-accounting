import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 版本号与更新源都从 gradle.properties 读，发版时只改一处。
// appVersionCode 必须单调递增，否则安装器不允许覆盖，客户端也检测不到新版。
val appVersionCode = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("1.0")
val appUpdateRepo = providers.gradleProperty("appUpdateRepo").getOrElse("")

// 签名口令放在 ~/.gradle/gradle.properties，不进仓库。
val releaseStoreFile = providers.gradleProperty("FEIYU_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("FEIYU_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("FEIYU_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("FEIYU_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

android {
    namespace = "com.local.autobookkeeping"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.local.autobookkeeping"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "com.local.autobookkeeping.ThemeSwitchTestRunner"
        // 设置页用它拼 GitHub releases API 地址；空串表示未配置更新源。
        buildConfigField("String", "UPDATE_REPO", "\"$appUpdateRepo\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // 真机只需要 arm64；模拟器是 x86_64。其余两个 ABI 纯占体积。
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 只出真机包：四个 ABI 的 OCR so 原本占 40.8MB。
            ndk { abiFilters += "arm64-v8a" }
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠️ 未找到 FEIYU_STORE_* 签名配置，本次 release 将使用 debug 签名，" +
                        "无法覆盖已安装的正式版。请检查 ~/.gradle/gradle.properties",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.text.recognition.chinese)

    testImplementation(libs.junit.jupiter)
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.12.2")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
