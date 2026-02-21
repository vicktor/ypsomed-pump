plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ypsopump.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":pump-common"))

    // Nordic BLE Library
    implementation("no.nordicsemi.android:ble:2.11.0")
    implementation("no.nordicsemi.android.support.v18:scanner:1.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // gRPC (for Proregia server key exchange)
    implementation("io.grpc:grpc-okhttp:1.68.0")
    implementation("io.grpc:grpc-stub:1.68.0")

    // Google Play Integrity API (for key exchange token)
    implementation("com.google.android.play:integrity:1.4.0")
}
