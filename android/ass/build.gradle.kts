plugins { id("com.android.library") }

android {
    namespace = "dev.pathfin.ass"
    compileSdk = 37
    ndkVersion = "30.0.16248370"
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    api("androidx.media3:media3-exoplayer:1.11.1")
    api("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-extractor:1.11.1")
    testImplementation("junit:junit:4.13.2")
}
