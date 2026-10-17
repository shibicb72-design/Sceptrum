// Stored device model shared by every driver. Pure Kotlin/JVM with Wire, so the
// app serialises it through the encrypted DataStore without Android-only code.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wire)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

wire {
    kotlin {}
}

dependencies {
    api(libs.wire.runtime)
}
