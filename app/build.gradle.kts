plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.taketaker"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.taketaker"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/usbiso"))
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // pure-java LAME port; its one javax.sound dependency is stubbed in src/main/java/javax
    implementation("de.sciss:jump3r:1.0.5")
    testImplementation("junit:junit:4.13.2")
}

// One C file, so skip ndk-build/CMake (ndk-build chokes on spaces in the project path)
// and call the NDK's clang directly for each ABI.
val usbIsoAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android29",
    "armeabi-v7a" to "armv7a-linux-androideabi29",
    "x86_64" to "x86_64-linux-android29",
)
val buildUsbIso by tasks.registering {
    val src = file("src/main/cpp/usbiso.c")
    val outDir = layout.buildDirectory.dir("generated/usbiso")
    inputs.file(src)
    outputs.dir(outDir)
    doLast {
        val host = if (System.getProperty("os.name").startsWith("Windows")) "windows-x86_64" else
            if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"
        val clang = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/$host/bin/clang" +
            if (host.startsWith("windows")) ".exe" else "")
        for ((abi, target) in usbIsoAbis) {
            val out = outDir.get().file("$abi/libusbiso.so").asFile
            out.parentFile.mkdirs()
            providers.exec {
                commandLine(clang.absolutePath, "--target=$target", "-shared", "-fPIC", "-O2", "-Wall",
                    "-Wl,-z,max-page-size=16384", "-o", out.absolutePath, src.absolutePath)
            }.result.get().assertNormalExitValue()
        }
    }
}
tasks.named("preBuild") { dependsOn(buildUsbIso) }
