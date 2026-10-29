plugins { id("com.android.library") }

android {
    namespace = "dev.pathfin.ffmpeg"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro", "video-consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.11.0")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    compileOnly("org.jetbrains.kotlin:kotlin-annotations-jvm:2.4.21")
    api("androidx.media3:media3-decoder:1.11.1")
    api("androidx.media3:media3-exoplayer:1.11.1")
    compileOnly("org.checkerframework:checker-qual:3.43.0")
}
