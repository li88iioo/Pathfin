import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名材料放在仓库之外；Gradle/日志和 APK 都不保存密码或 keystore 内容。
val releaseSigningFile = file(providers.gradleProperty("pathfin.signingFile").orNull
    ?: "${System.getProperty("user.home")}/.local/share/pathfin/signing/release.properties")
val releaseSigning = Properties().apply { if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use(::load) }
val verificationBuild = providers.gradleProperty("pathfin.verification").orNull == "true"

android {
    namespace = "dev.pathfin.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.pathfin.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 33
        versionName = "0.1.1"
        resValue("string", "app_name", "Pathfin")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseSigningFile.isFile) create("production") {
            storeFile = file(requireNotNull(releaseSigning.getProperty("storeFile")))
            storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
            keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
            keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }
    buildTypes {
        debug {
            // 仪器回归使用独立包，测试安装/清理不会影响日常开发包的账号与数据。
            applicationIdSuffix = if (verificationBuild) ".verification" else ".debug"
            resValue("string", "app_name", if (verificationBuild) "Pathfin 验证" else "Pathfin 开发版")
        }
        release {
            if (verificationBuild) {
                applicationIdSuffix = ".verification"
                resValue("string", "app_name", "Pathfin 验证")
            }
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // 仅隔离验证包信任本地合成 HTTPS 证书，日常和 release 包不包含该资源或配置。
    if (verificationBuild) {
        sourceSets.getByName("debug") {
            manifest.srcFile("src/verification/AndroidManifest.xml")
            res.directories.add("src/verification/res")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    androidResources {
        generateLocaleConfig = false
        localeFilters += listOf("b+zh+Hans", "en")
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast { check(releaseSigningFile.isFile) {
        "生产构建需要仓库之外的 release.properties；使用 -Ppathfin.signingFile=/absolute/path/release.properties 指定。"
    } }
}
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(verifyReleaseSigning) }

// 文案回归使用固定中文基线；L10nTest 独立切换并验证中英文，不依赖构建机器的语言。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("user.language", "zh")
    systemProperty("user.country", "CN")
}

dependencies {
    implementation(project(":ass"))
    implementation(project(":ffmpeg"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
